package com.mccal.folio.keys

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the keyboard and turns touches into presses. What it knows about the field arrives as [FieldRules]; what it
 * does leaves through [listener]; where the keys go is [Geometry]'s business, so that part is tested without a phone.
 *
 * The look follows what phone keyboards actually do rather than what is easiest to draw: a rounded panel inset from
 * the edges instead of a full-bleed slab, a toolbar above the keys so the board isn't a naked grid, drawn icons
 * instead of Unicode glyphs in whatever font the system hands over, and a preview above the key under your finger.
 *
 * Key labels are sized from the key, the way AOSP does it, not from the system font scale: at 200% text the
 * alternative is a keyboard that tears itself apart.
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onBackspace()

        /**
         * A repeat from holding the key down. Separate from [onBackspace] because the first delete answers the
         * expensive question - is anything selected? - and asking the app again eighteen times a second costs a
         * blocking round trip per character.
         */
        fun onBackspaceRepeat() = onBackspace()

        fun onDeleteWord()
        fun onShift()
        fun onLayer(layer: Layer)
        fun onAction()
        fun onSwitchKeyboard()
        fun onCursor(steps: Int)
        fun onSelectAll()
        fun onCopy()
        fun onPaste()

        /** Swap the letters for the emoji. */
        fun onEmojiPanel()

        /** Swap the letters for what has been copied lately. */
        fun onClipboardPanel()

        /** A word from the strip, tapped. */
        fun onSuggestion(word: String)

        /** The emoji at the end of the strip, tapped: it goes in after the word. */
        fun onSuggestedEmoji(emoji: String) {}
        fun onHide()

        /** Hand over to the phone's voice keyboard. */
        fun onVoice() {}

        /** Swap the letters for the arrows and selection keys. */
        fun onCursorPad() {}

        /** The strip's question, answered: Keep or Always when [accepted], No when not. */
        fun onOffer(offer: Insights.Offer, accepted: Boolean) {}

        /** The strip's tip was answered with Got it. */
        fun onTipDone(tip: Tip) {}

        /**
         * A gesture with a tip was used, so there is no need to teach it. Called once per swipe, when it begins,
         * never per step. Gestures added later call this with their own [Tip].
         */
        fun onGesture(tip: Tip) {}

        /** The one-handed rail was used: back to the full width, or over to the other edge. Already on screen. */
        fun onOneHanded(side: OneHanded) {}

        /** Undo and redo, from the toolbar or a swipe up on Z. */
        fun onUndo() {}
        fun onRedo() {}
        fun onCut() {}

        /** Sliding from shift: move the cursor [steps] characters with Shift held, so the selection grows. */
        fun onSelectMove(steps: Int) {}

        /** A choice from the Style menu, for the selected text. */
        fun onStyle(style: TextStyle) {}
    }

    var listener: Listener? = null

    /**
     * What to offer above the keys: the word as typed first, then what it might have been.
     *
     * Empty whenever there is no word in progress, and the toolbar comes back in its place - the row is one height
     * either way, so nothing on the screen moves as you start and finish a word.
     */
    var suggestions: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            tools = placeToolbar()
            keyNodes.invalidateRoot()
            invalidate()
        }

    /**
     * The emoji the word being typed is the name of, and that name: the strip's last place before the mic. Null when
     * the word has none, and whenever [suggestions] is empty, since it only ever sits beside them.
     */
    var suggestedEmoji: SuggestedEmoji? = null
        set(value) {
            if (field == value) return
            field = value
            tools = placeToolbar()
            keyNodes.invalidateRoot()
            invalidate()
        }

    /**
     * Whether the first of [suggestions] is what was typed, drawn quieter than the rest. After a space there is
     * nothing typed, and the strip holds what might come next, all of them alike.
     */
    var typedFirst = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * A question for the strip: keep a word it keeps putting back, or make a fix it keeps making into a rule.
     *
     * It waits for a gap. While a word is being typed the strip is showing words, so the question sits behind them
     * and takes the toolbar's place once there is nothing else to show. The next key typed puts it away.
     */
    var offer: Insights.Offer? = null
        set(value) {
            if (field == value) return
            field = value
            tools = placeToolbar()
            keyNodes.invalidateRoot()
            invalidate()
        }

    /**
     * A gesture tip for the strip, handed over by the service at a gap between words. It sits where the question does,
     * behind the question and behind any words, and goes on the next key typed the same way.
     */
    var tip: Tip? = null
        set(value) {
            if (field == value) return
            field = value
            tools = placeToolbar()
            keyNodes.invalidateRoot()
            invalidate()
        }

    /**
     * How much of the field is selected, when anything is. The toolbar then counts it and offers Style, Cut, Copy and
     * Paste, since those are what a selection is for; it goes back to the usual buttons when the selection does.
     */
    var selection: Selected? = null
        set(value) {
            if (field == value) return
            field = value
            if (value == null) closeStyleMenu()
            tools = placeToolbar()
            keyNodes.invalidateRoot()
            invalidate()
        }

    /** Whether the question is on screen now, rather than waiting behind the word being typed. */
    internal val offerShowing: Boolean get() = tools.firstOrNull()?.key?.kind == KeyKind.OFFER

    /** Whether the tip is on screen now. */
    internal val tipShowing: Boolean get() = tools.firstOrNull()?.key?.kind == KeyKind.TIP

    /** What the person has chosen: which of the keyboard's habits are switched on. */
    var settings: Settings = Settings()
        set(value) {
            field = value
            theme = Theme.of(context, value.appearance, value.highContrast, value.keyStyle, value.pureBlack)
            // The toolbar's keys depend on two of these switches, so it is placed again now rather than waiting on a
            // layout pass that only comes if the size changed.
            tools = placeToolbar()
            keyNodes.invalidateRoot()
            requestLayout()
            invalidate()
        }

    /** Which language's accents sit behind the keys. */
    var language: Language = Language.ENGLISH
        set(value) {
            field = value
            invalidate()
        }

    var rules: FieldRules = FieldRules()
        set(value) {
            field = value
            // A password field has no strip and no mic, so the toolbar is placed again for it.
            tools = placeToolbar()
            keyNodes.invalidateRoot()
            invalidate()
        }

    /**
     * Whether the phone has a voice keyboard to hand over to. Without one the mic would be a key that does nothing,
     * so it isn't drawn at all. The service asks Android and sets this; the view never guesses.
     */
    var voiceAvailable: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            tools = placeToolbar()
            keyNodes.invalidateRoot()
            invalidate()
        }

    private val showVoice get() = voiceAvailable && ToolKey.VOICE in settings.toolbar && !rules.password

    /**
     * The letters under the emoji search, rather than the keyboard.
     *
     * The same view, so the letters there are typed exactly like the letters anywhere else: the same slide between
     * keys, the same two-thumb rollover, the same backspace repeat. What changes is only the frame. There is no toolbar,
     * because the search row sits where it would be; no board of its own, because [EmojiSearchPanel] draws one board
     * behind both; and short rows sit in the middle at the size of the top row's keys, since this board is shorter
     * and stretched keys would look like a different keyboard.
     */
    var searchKeys: Boolean = false
        set(value) {
            field = value
            tools = placeToolbar()
            keyNodes.invalidateRoot()
            requestLayout()
            invalidate()
        }

    var rows: List<Row> = emptyList()
        set(value) {
            field = value
            // Fingers already down keep the key they pressed. The board is rebuilt constantly while typing - one
            // capital letter turns shift off again and replaces every key - and a thumb halfway through the next
            // letter when that happens must still get the letter it pressed.
            requestLayout()
            invalidate()
        }

    var shift: Shift = Shift.OFF
        set(value) { field = value; invalidate() }

    private val dp = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        // The weight every phone keyboard uses for its letters; the regular face looks washed out on a key.
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = 11 * context.resources.displayMetrics.density
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val scratch = RectF()        // reused: a keyboard shouldn't allocate while it draws
    private var theme = Theme.of(context, Appearance.SYSTEM, false)
    private var placedKeys: List<Placement> = emptyList()
    private var tools: List<Placement> = emptyList()
    private var bottomInset = BottomRoom.GESTURE_BAND_DP * resources.displayMetrics.density
    private var sideInset = 0f

    private val panelPad get() = PANEL_PAD_DP * dp
    private val toolbarHeight get() = if (searchKeys) 0f else TOOLBAR_DP * dp

    /** One press per finger. A keyboard that tracks a single pointer drops letters the moment someone types fast. */
    private val presses = HashMap<Int, Press>()

    /**
     * [origin] is the key the finger landed on and owns the press's gestures; [placement] is the key it is over now.
     * They differ while someone slides, which at speed is most presses: a thumb travelling to the next letter lifts a
     * few pixels off the one it meant, and a keyboard that insists the lift land back inside the original rectangle
     * simply loses the letter.
     */
    /**
     * The row of alternates above a held key.
     *
     * [choice] follows the finger, so sliding along the row picks one and letting go takes it. Letting go without
     * having moved takes the first, which is what the keycap promised in its corner.
     */
    private class Popup(val items: List<String>, val boxes: List<Box>, var choice: Int)

    private var popup: Popup? = null

    private class Press(val origin: Placement, val downX: Float, val downY: Float) {
        var placement: Placement = origin
        var swiping = false
        var cursorAnchor = downX

        /** A swipe up on one of the edit keys has begun: the press is that gesture now, and never a letter. */
        var editing = false

        /** The edit letting go would do: set while the finger is past the flick distance, cleared if it comes back. */
        var edit: EditSwipe? = null

        /** The press has already produced what it was going to: a repeat, or the alternate from holding it. */
        var handled = false

        /** Pending hold, cancelled the moment the finger lifts or moves on. */
        var hold: Runnable? = null
    }

    private val repeat = Handler(Looper.getMainLooper())
    private var repeatingFor: Press? = null
    private val repeatBackspace = object : Runnable {
        override fun run() {
            val press = repeatingFor ?: return
            press.handled = true
            putOfferAway()
            listener?.onBackspaceRepeat()
            repeat.postDelayed(this, REPEAT_MS)
        }
    }

    private val keyNodes = KeyNodes()
    /**
     * The key click, and whether Bluetooth audio is on. The service starts and stops it with the keyboard's window,
     * and hands the same one to the emoji search's letters, so only one callback is ever registered.
     */
    internal var feedback = Feedback(context)

    init {
        isHapticFeedbackEnabled = true
        setWillNotDraw(false)
        ViewCompat.setAccessibilityDelegate(this, keyNodes)
    }

    /** Everything a finger might have left behind. Called when a new field opens, in case one did. */
    fun forgetTouches() {
        closePopup()
        closeStyleMenu()
        for (press in presses.values) cancelHold(press)
        presses.clear()
        stopRepeat()
        invalidate()
    }

    private fun stopRepeat() {
        repeat.removeCallbacks(repeatBackspace)
        repeatingFor = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        feedback.stop()
        stopRepeat()   // a keyboard hidden mid-hold must not keep deleting
        closePopup()
        closeStyleMenu()
        for (press in presses.values) cancelHold(press)
        presses.clear()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        // Always leave a strip at the bottom, even when the system reports no navigation inset: that is where the
        // hide-keyboard button and the home gesture live, and a keyboard flush to the edge takes both away.
        val navigation = insets.getInsets(WindowInsets.Type.navigationBars()).bottom.toFloat()
        bottomInset = BottomRoom.band(
            BottomRoom.navigationMode(context),
            navigation,
            BottomRoom.folioButtonsDp(context.contentResolver),
            dp,
        )
        val cutout = insets.getInsets(WindowInsets.Type.displayCutout())
        sideInset = max(cutout.left, cutout.right).toFloat()
        requestLayout()
        return insets
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        // Only the phone's night mode can have changed; the person's own choices still stand. This used to be
        // Theme.of(context), which dropped a forced light or dark, and high contrast, on every rotation and unfold.
        theme = Theme.of(context, settings.appearance, settings.highContrast, settings.keyStyle, settings.pureBlack)
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val height = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightSpec)
        } else {
            Geometry.height(
                rows.size, resources.configuration.screenHeightDp.toFloat(), dp, bottomInset,
                extra = toolbarHeight + panelPad,
                share = Geometry.SHARE * settings.size.share,
            )
        }
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), height)
    }

    /**
     * How the keyboard sits in the window it was given. A phone keyboard stretched across an unfolded Fold gives keys
     * no thumb can reach, so a wide window splits (as Samsung's does), a middling one is capped and centred (as a
     * tablet keyboard is), and a phone fills the width - or, asked to be one-handed, sits against one edge with the
     * rail beside it.
     */
    private enum class Shape { FULL, CAPPED, SPLIT, ONE_HANDED }

    private var shape = Shape.FULL

    /** The board's two edges, and the keys' two edges inside it. The whole width, unless the keyboard is one-handed. */
    private var boardLeft = 0f
    private var boardRight = 0f
    private var keysLeft = 0f
    private var keysRight = 0f

    /** The one-handed rail's two buttons. Empty whenever the keyboard fills its window. */
    private var rail: List<Placement> = emptyList()

    /**
     * Width alone does not say how big a screen is.
     *
     * A phone on its side and an unfolded Fold are both over 600 dp across, and they want opposite things. The one
     * that tells them apart is height: a phone in landscape is short, and every keyboard worth using fills its
     * width there rather than splitting it, because there is no reach problem to solve and no height to spare.
     * Splitting is for a screen that is genuinely large in both directions.
     */
    private fun shapeFor(widthDp: Float, heightDp: Float) = when {
        // Asked for outright, or refused outright. Only a window with room for it can be split either way.
        settings.split == Split.NEVER -> if (widthDp >= CAP_AT_DP) Shape.CAPPED else Shape.FULL
        settings.split == Split.ALWAYS && widthDp >= SPLIT_AT_DP -> Shape.SPLIT
        heightDp < SHORT_DP -> Shape.FULL
        widthDp >= SPLIT_AT_DP -> Shape.SPLIT
        widthDp >= CAP_AT_DP -> Shape.CAPPED
        else -> Shape.FULL
    }

    /**
     * Where a one-handed board goes in a window [across] pixels wide, or null when it doesn't apply.
     *
     * Only where the keyboard would otherwise fill the width. A window big enough to split or centre the keys has no
     * reach problem left to solve, so an unfolded Fold simply ignores the setting. The cutout's side is kept clear on
     * both edges, the way the full keyboard keeps it, so the rail never ends up under a camera.
     */
    private fun oneHandedSpan(across: Float): Pair<Float, Float>? {
        if (settings.oneHanded == OneHanded.OFF || searchKeys || across <= 0f) return null
        if (shapeFor(across / dp, resources.configuration.screenHeightDp.toFloat()) != Shape.FULL) return null
        // A phone on its side has no reach problem either: both thumbs are on the long edge, and a 360 dp keyboard
        // with the rest of the width empty only wastes it. Gboard switches one-handed off in landscape too.
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) return null
        val keys = Geometry.oneHandedDp((across - 2 * sideInset) / dp)?.times(dp) ?: return null
        return if (settings.oneHanded == OneHanded.RIGHT) across - sideInset - keys to across else 0f to sideInset + keys
    }

    /**
     * The part of a window [across] pixels wide that the letters' board takes, so a panel opened in its place can
     * take the same part. The whole width unless the keyboard is one-handed.
     */
    fun boardSpan(across: Int): Pair<Int, Int> =
        oneHandedSpan(across.toFloat())?.let { (left, right) -> left.roundToInt() to right.roundToInt() } ?: (0 to across)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        arrange()
    }

    /** Places everything for the size the view has now. Also run straight away when the rail moves the keyboard. */
    private fun arrange() {
        val span = oneHandedSpan(width.toFloat())
        shape = if (span != null) Shape.ONE_HANDED else shapeFor(width / dp, resources.configuration.screenHeightDp.toFloat())
        val edge = panelPad + sideInset + Geometry.SIDE_PAD_DP * dp
        boardLeft = span?.first ?: 0f
        boardRight = span?.second ?: width.toFloat()
        keysLeft = if (boardLeft > 0f) boardLeft + panelPad + Geometry.SIDE_PAD_DP * dp else edge
        keysRight = if (boardRight < width) boardRight - panelPad - Geometry.SIDE_PAD_DP * dp else width - edge
        val usable = width - 2 * edge
        val top = toolbarHeight + panelPad
        val bottom = bottomInset + panelPad

        placedKeys = when (shape) {
            Shape.FULL -> Geometry.place(
                rows, width, height, dp,
                sideInset = sideInset + panelPad, bottomInset = bottom, top = top,
                evenKeys = searchKeys, centre = searchKeys,
            )
            Shape.CAPPED -> {
                // Centred and no wider than a large phone: the keys stay the size hands expect.
                val capped = min(usable, CAP_WIDTH_DP * dp)
                Geometry.place(
                    rows, width, height, dp, bottomInset = bottom, top = top,
                    startX = (width - capped) / 2, fillWidth = capped,
                    evenKeys = searchKeys, centre = searchKeys,
                )
            }
            Shape.SPLIT -> {
                val gutter = min(max(usable * 0.16f, 80 * dp), 220 * dp)
                val half = (usable - gutter) / 2
                val (leftRows, rightRows) = Layouts.split(rows)
                Geometry.place(
                    leftRows, width, height, dp, bottomInset = bottom, top = top,
                    startX = edge, fillWidth = half, evenKeys = true,
                ) + Geometry.place(
                    rightRows, width, height, dp, bottomInset = bottom, top = top,
                    startX = edge + half + gutter, fillWidth = half, evenKeys = true, alignEnd = true,
                )
            }
            Shape.ONE_HANDED -> Geometry.place(
                rows, width, height, dp, bottomInset = bottom, top = top,
                startX = keysLeft, fillWidth = keysRight - keysLeft,
            )
        }
        rail = if (shape == Shape.ONE_HANDED) placeRail() else emptyList()
        tools = placeToolbar()
        keyNodes.invalidateRoot()
        openPendingHold()
    }

    /**
     * The rail beside a one-handed keyboard: Full width above, the other side below, in the middle of its height.
     *
     * Each is the rail's whole width across and taller than a fingertip, though what is drawn is smaller: the rail
     * is slim, and a button drawn as big as its target would crowd it.
     */
    private fun placeRail(): List<Placement> {
        val (left, right) = if (settings.oneHanded == OneHanded.RIGHT) {
            max(sideInset, boardLeft - Geometry.RAIL_DP * dp) to boardLeft
        } else {
            boardRight to min(width - sideInset, boardRight + Geometry.RAIL_DP * dp)
        }
        val cy = (panelPad + height - bottomInset - panelPad) / 2
        val tall = RAIL_BUTTON_DP * dp
        val gap = RAIL_GAP_DP * dp / 2
        return listOf(
            Placement(
                Key(context.getString(R.string.one_handed_full_width), KeyKind.FULL_WIDTH),
                Box(left, cy - gap - tall, right, cy - gap),
            ),
            Placement(
                Key(context.getString(R.string.one_handed_other_side), KeyKind.OTHER_SIDE),
                Box(left, cy + gap, right, cy + gap + tall),
            ),
        )
    }

    /** The rail's own strip of board, and its two buttons drawn like the keys that aren't letters. */
    private fun drawRail(canvas: Canvas) {
        if (rail.isEmpty()) return
        val column = rail.first().box
        // The gap between the rail and the keyboard is the same gap the board keeps from the screen's edge.
        val left = if (settings.oneHanded == OneHanded.RIGHT) column.left else column.left + panelPad
        val right = if (settings.oneHanded == OneHanded.RIGHT) column.right - panelPad else column.right
        val across = right - left
        scratch.set(left, panelPad, right, height - panelPad)
        fill.color = theme.board
        canvas.drawRoundRect(scratch, min(PANEL_RADIUS_DP * dp, across / 2), min(PANEL_RADIUS_DP * dp, across / 2), fill)
        val side = min(across - 8 * dp, RAIL_KEY_DP * dp)
        val cx = (left + right) / 2
        val radius = theme.keyRadiusDp * dp
        for (placement in rail) {
            val box = placement.box
            val cy = (box.top + box.bottom) / 2
            fill.color = if (isHeld(placement)) blend(theme.altKey, theme.pressTint) else theme.altKey
            scratch.set(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
            canvas.drawRoundRect(scratch, radius, radius, fill)
            stroke.color = theme.label
            stroke.strokeWidth = max(1.5f * dp, side * 0.045f)
            when (placement.key.kind) {
                KeyKind.FULL_WIDTH -> Icons.maximize(canvas, cx, cy, side * 0.5f, stroke)
                else -> Icons.swap(canvas, cx, cy, side * 0.5f, stroke)
            }
        }
    }

    /**
     * The toolbar: Hide, then the buttons someone chose, in their order.
     *
     * Every button gets at least [MIN_TOOL_DP]. A window too narrow for all of them - a cover screen, a phone split
     * in two - drops them from the end of the list, the ones that were added last, and never Hide, which is the way
     * out. Nothing is squeezed below a size a thumb can hit.
     */
    private fun placeToolbar(): List<Placement> {
        if (width == 0 || searchKeys) return emptyList()
        val selected = selection
        if (selected != null && settings.selectionTools && !rules.password) return placeSelection(selected)
        if (suggestions.isNotEmpty() && !rules.password) return placeSuggestions()
        if (offer != null && !rules.password) return placeOffer()
        if (tip != null && !rules.password) return placeTip()
        val left = keysLeft
        val right = keysRight
        val top = panelPad
        val bottom = top + toolbarHeight
        // The mic only where there is a voice keyboard to hand over to, as before the toolbar could be arranged.
        val chosen = settings.toolbar.filter { it != ToolKey.VOICE || showVoice }.map(::toolKey)
        val fits = max(1, ((right - left) / (MIN_TOOL_DP * dp)).toInt())
        val items = (listOf(Key("Hide", KeyKind.HIDE)) + chosen).take(fits)
        // Even across the whole width: a row of icons bunched in one corner is the difference between a toolbar and
        // a few buttons someone left there. Hide and the first button sit at the start, the rest at the end.
        val slot = min((right - left) / items.size, TOOL_SLOT_DP * dp)
        val placed = ArrayList<Placement>(items.size)
        val lead = min(2, items.size)
        for (index in 0 until lead) {
            placed += Placement(items[index], Box(left + index * slot, top, left + (index + 1) * slot, bottom))
        }
        val rest = items.drop(lead)
        var x = right - rest.size * slot
        for (key in rest) {
            placed += Placement(key, Box(x, top, x + slot, bottom))
            x += slot
        }
        return placed
    }

    private fun toolKey(tool: ToolKey): Key = when (tool) {
        ToolKey.EMOJI -> Key("Emoji", KeyKind.EMOJI)
        ToolKey.UNDO -> Key("Undo", KeyKind.UNDO)
        ToolKey.REDO -> Key("Redo", KeyKind.REDO)
        ToolKey.CURSOR_PAD -> Key("Cursor pad", KeyKind.CURSOR_PAD)
        ToolKey.SELECT_ALL -> Key("Select all", KeyKind.SELECT_ALL)
        ToolKey.CUT -> Key("Cut", KeyKind.CUT)
        ToolKey.COPY -> Key("Copy", KeyKind.COPY)
        ToolKey.PASTE -> Key("Paste", KeyKind.PASTE)
        ToolKey.CLIPBOARD -> Key("Clipboard", KeyKind.CLIPBOARD)
        ToolKey.VOICE -> Key("Voice", KeyKind.VOICE)
    }

    /**
     * The toolbar while text is selected: Hide, how much is selected, Style, then Cut, Copy and Paste at the end
     * where they always are. The count takes whatever is left, and is sized down to fit rather than cut short.
     */
    private fun placeSelection(selected: Selected): List<Placement> {
        // The keys' own edges, as the toolbar uses: one-handed, the whole width would put Paste over the rail.
        val left = keysLeft
        val right = keysRight
        val top = panelPad
        val bottom = top + toolbarHeight
        val slot = min((right - left) / SELECTION_SLOTS, TOOL_SLOT_DP * dp)
        val style = context.getString(R.string.selection_style)
        text.textSize = selectionTextSize()
        sizedAt = -1f
        val styleWidth = max(slot, text.measureText(style) + 2 * OFFER_BUTTON_PAD_DP * dp)
        val cutLeft = right - 3 * slot
        val styleLeft = cutLeft - styleWidth
        return listOf(
            Placement(Key("Hide", KeyKind.HIDE), Box(left, top, left + slot, bottom)),
            Placement(Key(countLabel(selected), KeyKind.SELECTION), Box(left + slot, top, styleLeft, bottom)),
            Placement(Key(style, KeyKind.STYLE), Box(styleLeft, top, cutLeft, bottom)),
            Placement(Key("Cut", KeyKind.CUT), Box(cutLeft, top, cutLeft + slot, bottom)),
            Placement(Key("Copy", KeyKind.COPY), Box(cutLeft + slot, top, cutLeft + 2 * slot, bottom)),
            Placement(Key("Paste", KeyKind.PASTE), Box(cutLeft + 2 * slot, top, right, bottom)),
        )
    }

    private fun selectionTextSize() = min(toolbarHeight * 0.36f, 15 * dp)

    /** "23 characters · 4 words", or "2,000+ characters" when there was too much to read. */
    internal fun countLabel(selected: Selected): String {
        val number = java.text.NumberFormat.getIntegerInstance()
        if (selected.capped) return context.getString(R.string.selection_many, number.format(Selected.CAP))
        val characters = resources.getQuantityString(
            R.plurals.selection_characters, selected.characters, number.format(selected.characters),
        )
        val words = resources.getQuantityString(R.plurals.selection_words, selected.words, number.format(selected.words))
        return context.getString(R.string.selection_count, characters, words)
    }

    /**
     * The strip: what was typed, then the alternatives, in equal shares of the row.
     *
     * Equal shares rather than shares by word length, because a strip whose buttons move as you type is a strip
     * people mis-tap. The first is always the literal, so taking back a suggestion is always in the same place.
     */
    private fun placeSuggestions(): List<Placement> {
        val left = keysLeft
        val right = keysRight
        val top = panelPad
        val bottom = top + toolbarHeight
        // The mic keeps the last slot while a word is being typed, where Gboard, Samsung and SwiftKey all keep it:
        // voice is most wanted exactly when typing has started to feel slow.
        // The emoji, when there is one, takes a place the size of the mic's just before it. On a cover screen or half
        // a split keyboard it is the words that give up the room, never the mic or the emoji.
        val emoji = suggestedEmoji
        val icons = (if (showVoice) 1 else 0) + (if (emoji != null) 1 else 0)
        val icon = if (icons > 0) min((right - left) / (suggestions.size + icons), TOOL_SLOT_DP * dp) else 0f
        val mic = if (showVoice) icon else 0f
        val emojiWidth = if (emoji != null) icon else 0f
        val words = right - mic - emojiWidth
        val slot = (words - left) / suggestions.size
        val placed = suggestions.mapIndexedTo(ArrayList(suggestions.size + 2)) { index, word ->
            Placement(
                Key(word, KeyKind.SUGGESTION, output = word),
                Box(left + index * slot, top, left + (index + 1) * slot, bottom),
            )
        }
        if (emoji != null) {
            val label = context.getString(R.string.suggested_emoji, emoji.name)
            placed += Placement(
                Key(label, KeyKind.SUGGESTED_EMOJI, output = emoji.glyph),
                Box(words, top, words + emojiWidth, bottom),
            )
        }
        if (mic > 0f) placed += Placement(Key("Voice", KeyKind.VOICE), Box(right - mic, top, right, bottom))
        return placed
    }

    /**
     * The question, then its two answers at the end of the row where a thumb already goes for the strip.
     *
     * The answers reach from the top of the window to the keys, so each is a full 48 dp to hit though the row it
     * sits in is shorter; nothing is above them to take a stray touch instead.
     */
    private fun placeOffer(): List<Placement> {
        val asked = offer ?: return emptyList()
        val left = keysLeft
        val right = keysRight
        val top = 0f
        val bottom = panelPad + toolbarHeight
        val narrow = (boardRight - boardLeft) / dp < NARROW_OFFER_DP
        val question = context.getString(
            when {
                asked.keep && narrow -> R.string.offer_keep_short
                asked.keep -> R.string.offer_keep
                narrow -> R.string.offer_always_short
                else -> R.string.offer_always
            },
            asked.typed, asked.replacement.orEmpty(),
        )
        val yes = context.getString(if (asked.keep) R.string.offer_answer_keep else R.string.offer_answer_always)
        val no = context.getString(R.string.offer_answer_no)
        text.textSize = offerTextSize()
        sizedAt = -1f
        fun widthOf(label: String) = max(OFFER_BUTTON_DP * dp, text.measureText(label) + 2 * OFFER_BUTTON_PAD_DP * dp)
        val noLeft = right - widthOf(no)
        val yesLeft = noLeft - OFFER_GAP_DP * dp - widthOf(yes)
        return listOf(
            Placement(Key(question, KeyKind.OFFER), Box(left, panelPad, yesLeft - OFFER_GAP_DP * dp, bottom)),
            Placement(Key(yes, KeyKind.OFFER_YES), Box(yesLeft, top, noLeft - OFFER_GAP_DP * dp, bottom)),
            Placement(Key(no, KeyKind.OFFER_NO), Box(noLeft, top, right, bottom)),
        )
    }

    private fun offerTextSize() = min(toolbarHeight * 0.38f, 15 * dp)

    /**
     * The bulb, the tip, and Got it at the end of the row where the question's answers go, with the same reach.
     *
     * The tip is one sentence and not cut short if it can help it: on a narrow window it is drawn smaller, down to
     * [MIN_COUNT_DP], before it is ever ellipsized. A screen reader gets all of it either way.
     */
    private fun placeTip(): List<Placement> {
        val shown = tip ?: return emptyList()
        val left = keysLeft
        val right = keysRight
        val narrow = (boardRight - boardLeft) / dp < NARROW_OFFER_DP
        val said = context.getString(if (narrow) shown.short else shown.text)
        val done = context.getString(R.string.tip_got_it)
        text.textSize = offerTextSize()
        sizedAt = -1f
        val doneLeft = right - max(OFFER_BUTTON_DP * dp, text.measureText(done) + 2 * OFFER_BUTTON_PAD_DP * dp)
        return listOf(
            Placement(Key(said, KeyKind.TIP), Box(left, panelPad, doneLeft - OFFER_GAP_DP * dp, panelPad + toolbarHeight)),
            Placement(Key(done, KeyKind.TIP_DONE), Box(doneLeft, 0f, right, panelPad + toolbarHeight)),
        )
    }

    /** The next key typed answers nothing, and the question goes. Only once it has been seen: one waiting stays. */
    private fun putOfferAway() {
        if (offerShowing) offer = null
        if (tipShowing) tip = null
    }

    /** Text from a key, a flick or a held key's row: the one place it leaves, so the question can go first. */
    private fun type(value: String) {
        putOfferAway()
        listener?.onText(value)
    }

    private fun drawOffer(canvas: Canvas, placement: Placement) {
        val box = placement.box
        text.textSize = offerTextSize()
        sizedAt = -1f
        // Drawn in the toolbar's own band; the extra reach above it is for fingers, not for the eye.
        val bandTop = panelPad
        val cy = (bandTop + box.bottom) / 2
        val baseline = cy - (text.descent() + text.ascent()) / 2
        when (placement.key.kind) {
            KeyKind.OFFER -> {
                val room = box.right - box.left
                var label = placement.key.label
                if (text.measureText(label) > room) {
                    val fits = text.breakText(label, true, room - text.measureText("…"), null)
                    label = label.take(fits).trimEnd() + "…"
                }
                text.color = theme.label
                text.textAlign = Paint.Align.LEFT
                canvas.drawText(label, box.left, baseline, text)
                text.textAlign = Paint.Align.CENTER
            }
            KeyKind.TIP -> {
                val glyph = min(toolbarHeight * 0.5f, 20 * dp)
                stroke.color = theme.label
                stroke.strokeWidth = max(1.5f * dp, glyph * 0.08f)
                Icons.bulb(canvas, box.left + glyph / 2 + 2 * dp, cy, glyph, stroke)
                val start = box.left + glyph + 8 * dp
                val room = box.right - start
                var label = placement.key.label
                val wide = text.measureText(label)
                if (wide > room) text.textSize = max(MIN_COUNT_DP * dp, text.textSize * room / wide)
                if (text.measureText(label) > room) {
                    val fits = text.breakText(label, true, room - text.measureText("…"), null)
                    label = label.take(fits).trimEnd() + "…"
                }
                val line = cy - (text.descent() + text.ascent()) / 2
                text.color = theme.label
                text.textAlign = Paint.Align.LEFT
                canvas.drawText(label, start, line, text)
                text.textAlign = Paint.Align.CENTER
            }
            KeyKind.OFFER_YES, KeyKind.OFFER_NO, KeyKind.TIP_DONE -> {
                val yes = placement.key.kind != KeyKind.OFFER_NO
                val pill = OFFER_PILL_DP * dp
                scratch.set(box.left, cy - pill / 2, box.right, cy + pill / 2)
                fill.color = if (yes) theme.accent else theme.altKey
                canvas.drawRoundRect(scratch, pill / 2, pill / 2, fill)
                text.color = if (yes) theme.onAccent else theme.label
                canvas.drawText(placement.key.label, (box.left + box.right) / 2, baseline, text)
            }
            else -> Unit
        }
    }

    override fun onDraw(canvas: Canvas) {
        // The panel floats: the app shows through around it, the way a phone keyboard looks. Under the emoji search
        // the panel around it has already drawn the board.
        if (!searchKeys) {
            scratch.set(boardLeft + panelPad, panelPad, boardRight - panelPad, height - panelPad)
            fill.color = theme.board
            canvas.drawRoundRect(scratch, PANEL_RADIUS_DP * dp, PANEL_RADIUS_DP * dp, fill)
        }

        drawToolbar(canvas)
        drawRail(canvas)

        val radius = theme.keyRadiusDp * dp
        for (placement in placedKeys) {
            val box = placement.box
            val key = placement.key
            val held = isHeld(placement)
            fill.color = when {
                key.kind == KeyKind.ACTION -> theme.accent
                key.kind != KeyKind.CHAR && key.kind != KeyKind.SPACE -> theme.altKey
                else -> theme.key
            }
            if (held) fill.color = blend(fill.color, theme.pressTint)
            scratch.set(box.left, box.top, box.right, box.bottom)
            canvas.drawRoundRect(scratch, radius, radius, fill)
            drawKeyFace(canvas, placement, held)
        }
        drawStyleMenu(canvas)
        drawPreview(canvas)
        drawPopup(canvas)
    }

    /** The row of alternates, drawn last so it sits over everything. */
    private fun drawPopup(canvas: Canvas) {
        val open = popup ?: return
        val radius = theme.keyRadiusDp * dp
        val outer = open.boxes.first()
        val last = open.boxes.last()
        scratch.set(outer.left - 3 * dp, outer.top - 3 * dp, last.right + 3 * dp, last.bottom + 3 * dp)
        fill.color = theme.preview
        canvas.drawRoundRect(scratch, radius, radius, fill)
        for ((index, box) in open.boxes.withIndex()) {
            if (index == open.choice) {
                scratch.set(box.left, box.top, box.right, box.bottom)
                fill.color = theme.accent
                canvas.drawRoundRect(scratch, radius, radius, fill)
            }
            val offset = sizeLabel(box.height * 0.46f)
            text.color = if (index == open.choice) theme.onAccent else theme.label
            canvas.drawText(
                open.items[index], (box.left + box.right) / 2, (box.top + box.bottom) / 2 + offset, text,
            )
        }
    }

    /** Whether a finger is on this key. A handful of identity checks, rather than hashing every key every frame. */
    private fun isHeld(placement: Placement): Boolean {
        if (presses.isEmpty()) return false
        for (press in presses.values) if (press.placement === placement) return true
        return false
    }

    /**
     * The pill in the band below the keys, for when the keyboard can be resized.
     *
     * Not drawn yet. With gesture navigation the system puts its own home bar in the same strip, and two parallel
     * pills thirty pixels apart read as a mistake - especially on the Fold's cover screen, where the band is
     * tightest. It comes back when it does something.
     */
    @Suppress("unused")
    private fun drawHandle(canvas: Canvas) {
        val bandTop = height - bottomInset - panelPad
        val centre = (bandTop + height - panelPad) / 2
        val w = HANDLE_W_DP * dp / 2
        val h = HANDLE_H_DP * dp / 2
        scratch.set(width / 2f - w, centre - h, width / 2f + w, centre + h)
        fill.color = theme.hint
        canvas.drawRoundRect(scratch, h, h, fill)
    }

    private fun drawKeyFace(canvas: Canvas, placement: Placement, held: Boolean) {
        val box = placement.box
        val key = placement.key
        val cx = (box.left + box.right) / 2
        val cy = (box.top + box.bottom) / 2
        val icon = min(box.height, box.width) * 0.46f
        val onAction = key.kind == KeyKind.ACTION
        val ink = if (onAction) theme.onAccent else theme.label
        fill.color = ink
        stroke.color = ink
        stroke.strokeWidth = max(1.6f * dp, icon * 0.085f)

        when (key.kind) {
            KeyKind.SHIFT -> {
                // Off and one-shot are outlines; caps lock fills, so the three states can be told apart.
                val pen = if (shift == Shift.OFF) stroke else fill
                Icons.shift(canvas, cx, cy, icon * 0.92f, pen, shift == Shift.LOCKED)
            }
            KeyKind.BACKSPACE -> Icons.backspace(canvas, cx, cy, icon, stroke, stroke)
            KeyKind.GLOBE -> Icons.globe(canvas, cx, cy, icon, stroke)
            KeyKind.EMOJI -> Icons.smiley(canvas, cx, cy, icon, stroke, fill)
            KeyKind.ACTION -> if (key.label.length > 4) drawLabel(canvas, key.label, cx, cy, box, ink)
                else Icons.enter(canvas, cx, cy, icon * 1.1f, stroke)
            // The space bar is labelled where its width is known, which is here rather than in the row.
            // The second half of a split space bar is blank: the language said once is enough.
            KeyKind.SPACE -> if (key.label.isNotEmpty()) drawLabel(
                canvas, spaceLabel(box.width / dp, language, key.label), cx, cy, box, ink,
            )
            else -> drawLabel(canvas, key.label, cx, cy, box, ink)
        }

        // The corner hint is the long-press alternate, or an arrow on the five keys a swipe up edits with; a
        // password field keeps its own counsel.
        val cornerHint = key.hint ?: if (editFor(key) != null) EDIT_HINT else null
        if (cornerHint != null && !rules.password && !held) {
            hint.color = theme.hint
            canvas.drawText(cornerHint, box.right - 6 * dp, box.top + 14 * dp, hint)
        }
    }

    private var sizedAt = -1f
    private var baseline = 0f

    /**
     * Sets the label size and returns how far the baseline sits below the middle.
     *
     * Asking a Paint for its ascent and descent means asking the font for its metrics, and setting a text size throws
     * away what it knew. Keys in a row are all the same height, so the answer is worked out about once per row rather
     * than thirty times a frame.
     */
    private fun sizeLabel(size: Float): Float {
        if (size != sizedAt) {
            text.textSize = size
            sizedAt = size
            baseline = -(text.descent() + text.ascent()) / 2
        }
        return baseline
    }

    private fun drawLabel(canvas: Canvas, label: String, cx: Float, cy: Float, box: Box, ink: Int) {
        val offset = sizeLabel(
            if (label.length == 1) box.height * 0.52f else max(12 * dp, min(box.height * 0.30f, 16 * dp)),
        )
        text.color = ink
        canvas.drawText(label, cx, cy + offset, text)
    }

    private fun drawToolbar(canvas: Canvas) {
        for (placement in tools) {
            val box = placement.box
            val cx = (box.left + box.right) / 2
            val cy = (box.top + box.bottom) / 2
            val size = min(box.height * 0.62f, 26 * dp)
            stroke.color = theme.label
            stroke.strokeWidth = max(1.5f * dp, size * 0.072f)
            fill.color = theme.label
            if (placement.key.kind in OFFER_KINDS) {
                drawOffer(canvas, placement)
                continue
            }
            if (placement.key.kind == KeyKind.SELECTION || placement.key.kind == KeyKind.STYLE) {
                drawSelectionPart(canvas, placement)
                continue
            }
            if (placement.key.kind == KeyKind.SUGGESTION) {
                // The first is what was actually typed, and is drawn quieter than the alternatives so the eye goes
                // to what is being offered rather than to what it already knows it wrote.
                val literal = typedFirst && placement === tools.first()
                text.textSize = min(box.height * 0.40f, 17 * dp)
                sizedAt = -1f
                // A narrow strip - a cover screen, half a split keyboard, an emoji beside the mic - is where a long
                // word would run into its neighbour, so it is set smaller to fit its share instead.
                val room = box.width - 2 * OFFER_GAP_DP * dp
                val wide = text.measureText(placement.key.label)
                if (wide > room && room > 0f) text.textSize = max(MIN_COUNT_DP * dp, text.textSize * room / wide)
                text.color = if (literal) theme.hint else theme.label
                canvas.drawText(
                    placement.key.label, cx, cy - (text.descent() + text.ascent()) / 2, text,
                )
                if (!literal) {
                    fill.color = theme.hint
                    canvas.drawRect(box.left, cy - size * 0.5f, box.left + max(1f, dp * 0.5f), cy + size * 0.5f, fill)
                }
                continue
            }
            if (placement.key.kind == KeyKind.SUGGESTED_EMOJI) {
                // After a line like the ones between the words, and in the emoji's own colors.
                text.textSize = min(box.height * 0.46f, 20 * dp)
                sizedAt = -1f
                text.color = theme.label
                canvas.drawText(placement.key.output, cx, cy - (text.descent() + text.ascent()) / 2, text)
                fill.color = theme.hint
                canvas.drawRect(box.left, cy - size * 0.5f, box.left + max(1f, dp * 0.5f), cy + size * 0.5f, fill)
                continue
            }
            Icons.tool(canvas, placement.key.kind, cx, cy, size, stroke, fill)
        }
    }

    /** The count, as plain text sized down until it fits, and Style, as a button with its name on it. */
    private fun drawSelectionPart(canvas: Canvas, placement: Placement) {
        val box = placement.box
        val cy = (box.top + box.bottom) / 2
        text.textSize = selectionTextSize()
        sizedAt = -1f
        if (placement.key.kind == KeyKind.STYLE) {
            val pill = OFFER_PILL_DP * dp
            scratch.set(box.left + 2 * dp, cy - pill / 2, box.right - 2 * dp, cy + pill / 2)
            fill.color = if (isHeld(placement)) blend(theme.altKey, theme.pressTint) else theme.altKey
            canvas.drawRoundRect(scratch, pill / 2, pill / 2, fill)
            text.color = theme.label
            canvas.drawText(placement.key.label, (box.left + box.right) / 2, cy - (text.descent() + text.ascent()) / 2, text)
            return
        }
        val room = box.width - 2 * OFFER_GAP_DP * dp
        var label = placement.key.label
        val wide = text.measureText(label)
        if (wide > room) text.textSize = max(MIN_COUNT_DP * dp, text.textSize * room / wide)
        if (text.measureText(label) > room) {
            val fits = text.breakText(label, true, room - text.measureText("…"), null)
            label = label.take(fits).trimEnd() + "…"
        }
        text.color = theme.label
        canvas.drawText(label, (box.left + box.right) / 2, cy - (text.descent() + text.ascent()) / 2, text)
    }

    /**
     * What the bubble above a finger says: the character under it, or while a swipe up on an edit key is past the
     * flick distance, what letting go will do - "Copy" - so an edit is never a surprise. The character is never shown
     * in a password field; the edit is, since it says nothing about what is typed there.
     */
    private fun previewLabel(press: Press): String? {
        val key = press.placement.key
        if (key.kind != KeyKind.CHAR) return null
        press.edit?.let { return it.label }
        if (rules.password || !settings.keyPreview || press.editing) return null
        return key.label
    }

    /** What the bubbles above the fingers say now. */
    internal val previewLabels: List<String> get() = presses.values.mapNotNull(::previewLabel)

    private fun drawPreview(canvas: Canvas) {
        for (press in presses.values) {
            val label = previewLabel(press) ?: continue
            val box = press.placement.box
            val h = box.height * 1.05f
            val word = press.edit != null
            text.textSize = if (word) min(h * 0.34f, 17 * dp) else h * 0.54f
            // Set straight on the paint, so the next label sized through sizeLabel must not trust what it last set.
            sizedAt = -1f
            val w = max(max(box.width * 1.25f, 34 * dp), if (word) text.measureText(label) + 24 * dp else 0f)
            val edge = panelPad + w / 2
            val cx = ((box.left + box.right) / 2).coerceIn(edge, max(edge, width - edge))
            val bottom = box.top - 6 * dp
            if (bottom - h < 0) continue      // the top row has nowhere to put it
            scratch.set(cx - w / 2, bottom - h, cx + w / 2, bottom)
            fill.color = theme.preview
            canvas.drawRoundRect(scratch, theme.keyRadiusDp * dp, theme.keyRadiusDp * dp, fill)
            text.color = theme.label
            canvas.drawText(label, cx, (scratch.top + scratch.bottom) / 2 - (text.descent() + text.ascent()) / 2, text)
        }
    }

    // ---- the Style menu -----------------------------------------------------------------------------------------

    /**
     * The four styles, over the keys.
     *
     * Inside the keyboard's own window because an input method cannot draw outside it: the menu covers the keys
     * rather than floating above the text. [choices] are the four buttons; [note] is read, not pressed.
     */
    private class StyleMenu(val panel: Box, val choices: List<Placement>, val note: Placement, val layout: StaticLayout) {
        val items: List<Placement> get() = choices + note
    }

    private var styleMenu: StyleMenu? = null
    private val notePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /** What the menu is showing, choices then the note. Empty when it is closed. */
    internal val styleMenuItems: List<Placement> get() = styleMenu?.items.orEmpty()

    private fun openStyleMenu() {
        if (placedKeys.isEmpty()) return
        val keysTop = placedKeys.minOf { it.box.top }
        val keysBottom = placedKeys.maxOf { it.box.bottom }
        // Over the keys and no further: one-handed, the rail beside them stays where it is and can still be used.
        val left = keysLeft
        val right = keysRight
        val pad = MENU_PAD_DP * dp
        val gap = OFFER_GAP_DP * dp
        val row = MENU_ROW_DP * dp
        val column = (right - left - 2 * pad - gap) / 2
        val choices = TextStyle.entries.mapIndexed { index, style ->
            val x = left + pad + (index % 2) * (column + gap)
            val y = keysTop + pad + (index / 2) * (row + gap)
            Placement(Key(style.label, KeyKind.STYLE_CHOICE, output = style.name), Box(x, y, x + column, y + row))
        }
        val noteTop = keysTop + pad + 2 * (row + gap)
        val note = Placement(
            Key(context.getString(R.string.style_note), KeyKind.STYLE_NOTE),
            Box(left + pad, noteTop, right - pad, max(noteTop, keysBottom - pad)),
        )
        notePaint.textSize = 13 * dp
        notePaint.color = theme.label
        val layout = StaticLayout.Builder.obtain(note.key.label, 0, note.key.label.length, notePaint, note.box.width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        styleMenu = StyleMenu(Box(left, keysTop, right, keysBottom), choices, note, layout)
        keyNodes.invalidateRoot()
        invalidate()
    }

    private fun closeStyleMenu() {
        if (styleMenu == null) return
        styleMenu = null
        keyNodes.invalidateRoot()
        invalidate()
    }

    private fun drawStyleMenu(canvas: Canvas) {
        val menu = styleMenu ?: return
        val radius = theme.keyRadiusDp * dp
        val panel = menu.panel
        scratch.set(panel.left, panel.top, panel.right, panel.bottom)
        fill.color = theme.preview
        canvas.drawRoundRect(scratch, radius, radius, fill)
        for (choice in menu.choices) {
            val box = choice.box
            scratch.set(box.left, box.top, box.right, box.bottom)
            fill.color = if (isHeld(choice)) blend(theme.key, theme.pressTint) else theme.key
            canvas.drawRoundRect(scratch, radius, radius, fill)
            // Each choice shows itself: "Bold" in bold letters. What it is called is what TalkBack reads.
            val sample = TextStyle.valueOf(choice.key.output).on(choice.key.label)
            text.textSize = min(box.height * 0.40f, 18 * dp)
            sizedAt = -1f
            text.color = theme.label
            val cy = (box.top + box.bottom) / 2
            canvas.drawText(sample, (box.left + box.right) / 2, cy - (text.descent() + text.ascent()) / 2, text)
        }
        val note = menu.note.box
        canvas.save()
        canvas.clipRect(note.left, note.top, note.right, note.bottom)
        canvas.translate(note.left, note.top + max(0f, (note.height - menu.layout.height) / 2))
        menu.layout.draw(canvas)
        canvas.restore()
    }

    private fun blend(color: Int, tint: Int): Int {
        val a = Color.alpha(tint) / 255f
        fun mix(c: Int, t: Int) = (c * (1 - a) + t * a).roundToInt().coerceIn(0, 255)
        return Color.rgb(
            mix(Color.red(color), Color.red(tint)),
            mix(Color.green(color), Color.green(tint)),
            mix(Color.blue(color), Color.blue(tint)),
        )
    }

    /** Where the keys and the toolbar actually ended up. A test should ask rather than work it out a second time. */
    internal val placements: List<Placement> get() = placedKeys
    internal val toolbarPlacements: List<Placement> get() = tools
    internal val railPlacements: List<Placement> get() = rail

    /** What the open row of alternates is offering, and which one is chosen. A test should not have to guess. */
    internal val popupItems: List<String> get() = popup?.items.orEmpty()
    internal val popupChoice: Int get() = popup?.choice ?: -1
    internal val popupBoxes: List<Box> get() = popup?.boxes.orEmpty()

    /** Which of the three shapes the window got. */
    internal val shapeName: String get() = shape.name

    /**
     * Opens the row of alternates for a key, so a picture of it can be taken without waiting on a finger.
     *
     * Remembered rather than opened straight away, because a render sets the keyboard up before it has been laid
     * out, and there are no keys to hang a row of alternates over until there is a layout.
     */
    internal fun holdForRender(label: String) {
        pendingHold = label
        if (width > 0) openPendingHold()
    }

    private var pendingHold: String? = null

    private fun openPendingHold() {
        val label = pendingHold ?: return
        val placement = placedKeys.firstOrNull { it.key.label == label } ?: return
        val items = Alternates.forKey(placement.key.label, placement.key.hint, language)
        // The same rule a finger gets: one alternate is taken, not offered.
        if (items.size < 2) return
        popup = openPopup(placement, items)
    }

    /** The rail first, then the Style menu: both sit close enough to keys that a key's slop would take them. */
    private fun keyAt(x: Float, y: Float): Placement? =
        rail.firstOrNull { it.box.contains(x, y) } ?: styleMenu?.choices?.firstOrNull { it.box.contains(x, y) }
            ?: nearest(placedKeys, x, y) ?: nearest(tools, x, y) ?: nearest(rail, x, y)

    /**
     * The key under a point: the one containing it, or failing that the closest one within [HIT_SLOP_DP].
     *
     * The gap between keys is real estate a finger lands on constantly, and so is the rounded edge of the panel.
     * Giving those to the nearest key is what every keyboard does; the slop stops a tap far below the board from
     * being answered by the bottom row.
     */
    private fun nearest(list: List<Placement>, x: Float, y: Float): Placement? {
        var closest: Placement? = null
        var best = Float.MAX_VALUE
        for (placement in list) {
            if (placement.box.contains(x, y)) return placement
            val distance = placement.box.distanceTo(x, y)
            if (distance < best) {
                best = distance
                closest = placement
            }
        }
        return if (best <= HIT_SLOP_DP * dp) closest else null
    }

    // ---- touch ------------------------------------------------------------------------------------------------

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                down(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    move(event.getPointerId(index), event.getX(index), event.getY(index))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                if (up(event.getPointerId(index), event.getX(index), event.getY(index))) performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                closePopup()
                for (press in presses.values) cancelHold(press)
                presses.clear()
                stopRepeat()
                invalidate()
            }
        }
        return true
    }

    private fun down(pointer: Int, x: Float, y: Float) {
        if (popup != null && presses.isEmpty()) closePopup()   // left open by a press that never ended
        styleMenu?.let { menu ->
            // Any key closes the menu, and does what it does; a touch on the menu itself, between its choices, only
            // closes it. Style is left to close it, so tapping it again doesn't open it straight back up.
            val over = keyAt(x, y)
            if (over?.key?.kind != KeyKind.STYLE_CHOICE && over?.key?.kind != KeyKind.STYLE) {
                closeStyleMenu()
                if (menu.panel.contains(x, y)) return
            }
        }
        val placement = keyAt(x, y) ?: return
        val press = Press(placement, x, y)
        presses[pointer] = press
        Haptics.feel(this, settings.vibration)
        if (settings.sound) feedback.play(placement.key.kind, settings.muteWithBluetooth)
        if (placement.key.kind == KeyKind.BACKSPACE) {
            repeatingFor = press
            repeat.postDelayed(repeatBackspace, FIRST_REPEAT_MS)
        }
        startHold(press)
        invalidate()
    }

    /**
     * What flicking a key gives, or null when it gives nothing.
     *
     * **Down** is the character printed in the key's corner - the digit on the top row. It is the same thing
     * holding the key gives, without the wait, and it is already written on the keycap so nothing is hidden.
     * **Up** is the capital. Both are the fastest way to reach a character that would otherwise cost a whole
     * layer change or a shift.
     */
    private fun flick(key: Key, up: Boolean): String? {
        if (key.kind != KeyKind.CHAR) return null
        if (up) {
            if (!settings.flickForCapital) return null
            val capital = key.output.uppercase()
            return capital.takeIf { it != key.output }
        }
        if (!settings.flickForAlternate) return null
        return key.hint
    }

    /**
     * Holding a key types the alternate printed in its corner.
     *
     * The corner of every top-row key says what holding it gives you, which until now it did not: a keyboard that
     * prints a promise on the keycap has to keep it. The delay is the system's own, so it follows whatever someone
     * has set for touch and hold in accessibility.
     */
    private fun startHold(press: Press) {
        if (press.origin.key.kind != KeyKind.CHAR) return
        val hint = press.origin.key.hint
        val items = if (settings.accents) {
            Alternates.forKey(press.origin.key.label, hint, language)
        } else {
            listOfNotNull(hint)   // the corner digit still works; only the accents are switched off
        }
        if (items.isEmpty()) return
        val task = Runnable {
            Haptics.feel(this, settings.vibration, Haptics.Touch.HOLD)
            if (items.size == 1) {
                // Nothing to choose between, so holding simply gives it.
                press.handled = true
                type(items.first())
            } else {
                popup = openPopup(press.origin, items)
            }
            invalidate()
        }
        press.hold = task
        repeat.postDelayed(task, ViewConfiguration.getLongPressTimeout().toLong())
    }

    /**
     * Lays the alternates out in a row over the key, kept inside the board.
     *
     * Centred on the key where it can be, and pushed back inside the panel where it cannot - a row of accents half
     * off the edge of the screen is a row of accents you cannot reach.
     */
    private fun openPopup(on: Placement, items: List<String>): Popup {
        val box = on.box
        // Narrower than a key: nine of them at full key width is a row that spans the whole board and stops
        // looking like it belongs to the key underneath it. Never below a fingertip, though.
        val item = max(box.width * 0.78f, POPUP_MIN_DP * dp)
        val width = item * items.size
        val centre = (box.left + box.right) / 2
        val left = (centre - width / 2).coerceIn(keysLeft, max(keysLeft, keysRight - width))
        val height = box.height * 1.15f
        val bottom = box.top - POPUP_LIFT_DP * dp
        val top = max(panelPad, bottom - height)
        val boxes = items.indices.map { Box(left + it * item, top, left + (it + 1) * item, bottom) }
        // The one under the finger to begin with is the first: letting go without moving keeps the old behaviour.
        return Popup(items, boxes, 0)
    }

    private fun closePopup(): Popup? {
        val open = popup ?: return null
        popup = null
        invalidate()
        return open
    }

    private fun cancelHold(press: Press) {
        press.hold?.let { repeat.removeCallbacks(it) }
        press.hold = null
    }

    /** Whether a swipe up on [key] edits, and which edit: only on the five keys, and only while the switch is on. */
    private fun editFor(key: Key): EditSwipe? = key.edit?.takeIf { settings.editSwipes && key.kind == KeyKind.CHAR }

    private fun edit(action: EditSwipe) {
        val l = listener ?: return
        when (action) {
            EditSwipe.UNDO -> l.onUndo()
            EditSwipe.CUT -> l.onCut()
            EditSwipe.COPY -> l.onCopy()
            EditSwipe.PASTE -> l.onPaste()
            EditSwipe.SELECT_ALL -> l.onSelectAll()
        }
    }

    /**
     * How many characters a sideways swipe has travelled since the last step, for the space bar's cursor and shift's
     * selection alike. The first call is the swipe beginning: it is anchored a step behind, so crossing the threshold
     * moves one character straight away rather than asking for the journey all over again.
     */
    private fun cursorSteps(press: Press, x: Float, dx: Float): Int {
        cancelHold(press)
        if (!press.swiping) {
            press.swiping = true
            press.cursorAnchor = x - sign(dx) * CURSOR_STEP_DP * dp
        }
        val steps = ((x - press.cursorAnchor) / (CURSOR_STEP_DP * dp)).toInt()
        if (steps != 0) press.cursorAnchor += steps * CURSOR_STEP_DP * dp
        return steps
    }

    /** Swipe the space bar to move the cursor, and the backspace to take a word at a time. */
    private fun move(pointer: Int, x: Float, y: Float) {
        val press = presses[pointer] ?: return
        val dy = y - press.downY
        val open = popup
        if (open != null) {
            val over = open.boxes.indexOfFirst { x >= it.left && x < it.right }
            // Off the end of the row keeps the nearest one rather than losing the choice altogether.
            val choice = if (over >= 0) over else if (x < open.boxes.first().left) 0 else open.items.lastIndex
            if (choice != open.choice) {
                open.choice = choice
                invalidate()
            }
            return
        }
        val dx = x - press.downX
        when (press.origin.key.kind) {
            // The space bar is wide and a fast thumb wanders across it. The swipe only begins after a deliberate
            // journey - a whole key's worth - and counts its characters from there, so a drifted space is a space.
            // Down off the space bar puts the keyboard away, the way swiping a sheet down closes it. Checked
            // before the cursor, because a downward journey is not a sideways one however far it goes.
            KeyKind.SPACE -> if (
                settings.swipeDownToHide && !press.swiping && dy > HIDE_DP * dp && abs(dy) > abs(dx)
            ) {
                cancelHold(press)
                press.swiping = true
                listener?.onHide()
            } else if (settings.cursorSwipe && (press.swiping || abs(dx) > CURSOR_START_DP * dp)) {
                if (!press.swiping) listener?.onGesture(Tip.CURSOR_SWIPE)
                val steps = cursorSteps(press, x, dx)
                if (steps != 0) listener?.onCursor(steps)
            }
            // Shift and a slide sideways selects, the way the space bar moves the cursor and with the same distances,
            // with Shift held on every step. A tap is still shift; so is a press that never travels that far.
            KeyKind.SHIFT -> if (settings.shiftSelect && (press.swiping || abs(dx) > CURSOR_START_DP * dp)) {
                if (!press.swiping) listener?.onGesture(Tip.SHIFT_SELECT)
                val steps = cursorSteps(press, x, dx)
                if (steps != 0) listener?.onSelectMove(steps)
            }
            KeyKind.BACKSPACE -> if (settings.deleteWordSwipe && dx < -DELETE_WORD_DP * dp && !press.swiping) {
                cancelHold(press)
                press.swiping = true
                if (repeatingFor === press) stopRepeat()
                listener?.onDeleteWord()
                listener?.onGesture(Tip.DELETE_WORD)
            }
            // Sliding from one letter to the next is how a fast typist corrects mid-press, and how they leave a key
            // at all. Only letters follow the finger: sliding off shift and letting go is how you take it back.
            KeyKind.CHAR -> {
                // Once a swipe up on an edit key has begun, the press is that and nothing else. Letting go past the
                // flick distance does the edit; bringing the finger back first is how to change your mind.
                if (press.editing) {
                    val armed = if (dy < -GESTURE_DP * dp) editFor(press.origin.key) else null
                    if (armed != press.edit) {
                        press.edit = armed
                        invalidate()
                    }
                    return
                }
                // A deliberate flick up or down, before anything else gets a say. It has to be a long way and
                // clearly more vertical than sideways, because the drift of ordinary fast typing is neither - and
                // a keyboard that mistook drift for a gesture would be the space bar problem all over again.
                if (!press.swiping && abs(dy) > GESTURE_DP * dp && abs(dy) > abs(dx) * VERTICAL_BIAS) {
                    val edit = editFor(press.origin.key)
                    if (edit != null && dy < 0) {
                        cancelHold(press)
                        press.swiping = true
                        press.editing = true
                        press.edit = edit
                        Haptics.feel(this, settings.vibration)
                        invalidate()
                        return
                    }
                    val flicked = flick(press.origin.key, up = dy < 0)
                    if (flicked != null) {
                        cancelHold(press)
                        press.swiping = true
                        press.handled = true
                        type(flicked)
                        Haptics.feel(this, settings.vibration)
                        invalidate()
                        return
                    }
                }
                // Not the moment the finger crosses the seam: at speed a thumb is already travelling towards the
                // next letter as it lifts, and a key that changed on the midpoint would turn "the" into "yjr". The
                // letter only changes once the finger is properly clear of the one it is on.
                if (press.placement.box.distanceTo(x, y) <= HYSTERESIS_DP * dp) return
                cancelHold(press)
                val over = keyAt(x, y)
                if (over != null && over !== press.placement && over.key.kind == KeyKind.CHAR) {
                    press.placement = over
                    invalidate()
                }
            }
            else -> Unit
        }
    }

    /** True when the lift was a press on a key, which is what [performClick] is for. */
    private fun up(pointer: Int, x: Float, y: Float): Boolean {
        val press = presses.remove(pointer) ?: return false
        cancelHold(press)
        closePopup()?.let { open ->
            if (repeatingFor === press) stopRepeat()
            type(open.items[open.choice])
            invalidate()
            return true
        }
        if (repeatingFor === press) stopRepeat()
        invalidate()
        press.edit?.let {
            edit(it)
            listener?.onGesture(Tip.EDIT_SWIPES)
            return true
        }
        if (press.swiping || press.handled) return false
        if (takenBack(press.origin.key.kind) && slidOff(press.origin, x, y)) return false
        dispatch(press.placement.key)
        return true
    }

    /**
     * Whether sliding off a key before letting go takes it back.
     *
     * Worth having for the keys where pressing the wrong one costs something: shift and the layer keys change the
     * whole board, return sends the message, and the toolbar acts on the text. Sliding off them is the only escape
     * route once a finger is down.
     *
     * Not worth having for the keys you type with. A thumb on the space bar rolls - it is the widest key, it sits
     * against the bottom edge, and it is pressed more than any other - so it lands on one edge and lets go past the
     * other. Insisting the finger come back up inside the same rectangle threw those away and made the commonest
     * key on the keyboard feel like it needed aiming at. Space and backspace now commit wherever the finger lifts,
     * the same as a letter does.
     */
    /**
     * Whether a finger that went down on [origin] and lifted at (x, y) meant to take the press back.
     *
     * Only two things count: ending on a different key, or sliding up off the keys toward the app, which is the
     * escape route. Rolling off the bottom or the side does not. Return sits in the bottom corner, and a thumb that
     * lifted a few millimetres past the panel's edge used to cancel the press, so Search and Go sometimes did nothing.
     */
    private fun slidOff(origin: Placement, x: Float, y: Float): Boolean {
        val over = keyAt(x, y)
        if (over != null) return over !== origin
        val keysTop = placedKeys.minOfOrNull { it.box.top } ?: return true
        return y < keysTop
    }

    private fun takenBack(kind: KeyKind) = when (kind) {
        KeyKind.CHAR, KeyKind.SPACE, KeyKind.BACKSPACE -> false
        else -> true
    }

    private fun dispatch(key: Key) {
        val l = listener ?: return
        when (key.kind) {
            KeyKind.CHAR, KeyKind.SPACE, KeyKind.BACKSPACE, KeyKind.ACTION -> putOfferAway()
            else -> Unit
        }
        // Any key closes the Style menu. Only a choice in it, or Style itself, has more to do with it.
        if (key.kind != KeyKind.STYLE && key.kind != KeyKind.STYLE_CHOICE) closeStyleMenu()
        when (key.kind) {
            KeyKind.CHAR, KeyKind.SPACE -> l.onText(key.output)
            KeyKind.BACKSPACE -> l.onBackspace()
            KeyKind.SHIFT -> l.onShift()
            KeyKind.LAYER -> l.onLayer(Layer.valueOf(key.output))
            KeyKind.ACTION -> l.onAction()
            KeyKind.GLOBE -> l.onSwitchKeyboard()
            KeyKind.CLIPBOARD -> l.onClipboardPanel()
            KeyKind.HIDE -> l.onHide()
            KeyKind.SELECT_ALL -> l.onSelectAll()
            KeyKind.COPY -> l.onCopy()
            KeyKind.PASTE -> l.onPaste()
            KeyKind.EMOJI -> l.onEmojiPanel()
            KeyKind.SUGGESTION -> l.onSuggestion(key.output)
            KeyKind.SUGGESTED_EMOJI -> l.onSuggestedEmoji(key.output)
            KeyKind.VOICE -> l.onVoice()
            KeyKind.CURSOR_PAD -> l.onCursorPad()
            KeyKind.FULL_WIDTH -> oneHanded(OneHanded.OFF, l)
            KeyKind.OTHER_SIDE ->
                oneHanded(if (settings.oneHanded == OneHanded.RIGHT) OneHanded.LEFT else OneHanded.RIGHT, l)
            KeyKind.UNDO -> l.onUndo()
            KeyKind.REDO -> l.onRedo()
            KeyKind.CUT -> l.onCut()
            KeyKind.STYLE -> if (styleMenu == null) openStyleMenu() else closeStyleMenu()
            KeyKind.STYLE_CHOICE -> {
                closeStyleMenu()
                l.onStyle(TextStyle.valueOf(key.output))
            }
            KeyKind.OFFER, KeyKind.SELECTION, KeyKind.STYLE_NOTE, KeyKind.TIP -> Unit
            KeyKind.TIP_DONE -> tip?.let { shown ->
                tip = null
                l.onTipDone(shown)
            }
            KeyKind.OFFER_YES, KeyKind.OFFER_NO -> offer?.let { asked ->
                offer = null
                l.onOffer(asked, accepted = key.kind == KeyKind.OFFER_YES)
            }
        }
    }

    /** Moved on screen at once, rather than after the service has saved it and handed the settings back. */
    private fun oneHanded(side: OneHanded, l: Listener) {
        settings = settings.copy(oneHanded = side)
        if (width > 0) {
            arrange()
            invalidate()
        }
        l.onOneHanded(side)
    }

    // ---- screen readers ---------------------------------------------------------------------------------------

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        keyNodes.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /** Every key a screen reader can find: the keys, the toolbar, the Style menu while it is open, then the rail. */
    private fun nodes(): List<Placement> = placedKeys + tools + styleMenuItems + rail

    private fun nodeAt(id: Int): Placement? = nodes().getOrNull(id)

    /**
     * The keys are drawn, not laid out, so a screen reader would find one blank rectangle. Each key is published as a
     * virtual view with the name it should be read by: "Backspace", not a glyph.
     */
    private inner class KeyNodes : ExploreByTouchHelper(this@KeyboardView) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            // The menu sits over the keys, so under it only the menu is there to be found.
            styleMenu?.let { menu ->
                if (menu.panel.contains(x, y)) {
                    val index = menu.items.indexOfFirst { it.box.contains(x, y) }
                    return if (index < 0) HOST_ID else placedKeys.size + tools.size + index
                }
            }
            val all = nodes()
            val index = all.indexOfFirst { it.box.contains(x, y) }
            return if (index < 0) HOST_ID else index
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            val panel = styleMenu?.panel
            for ((index, placement) in nodes().withIndex()) {
                val box = placement.box
                val hidden = panel != null && index < placedKeys.size &&
                    panel.contains((box.left + box.right) / 2, (box.top + box.bottom) / 2)
                if (!hidden) ids.add(index)
            }
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val placement = nodeAt(id)
            if (placement == null) {
                node.contentDescription = ""
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            node.contentDescription = Spoken.name(placement.key, shift, settings.editSwipes)
            if (placement.key.kind in READ_ONLY_KINDS) {
                // The question is read, not pressed: its answers are the two buttons beside it.
                node.className = "android.widget.TextView"
            } else {
                node.className = "android.widget.Button"
                node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            }
            val box = placement.box
            node.setBoundsInParent(
                Rect(box.left.toInt(), box.top.toInt(), box.right.roundToInt(), box.bottom.roundToInt()),
            )
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
            val placement = nodeAt(id) ?: return false
            dispatch(placement.key)
            sendEventForVirtualView(id, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    private companion object {
        const val FIRST_REPEAT_MS = 400L
        const val REPEAT_MS = 55L
        const val CURSOR_STEP_DP = 12f    // travel per character the cursor moves
        const val CURSOR_START_DP = 40f   // travel before a drifting thumb counts as a swipe at all
        const val DELETE_WORD_DP = 24f
        const val HIT_SLOP_DP = 14f       // how far outside a key still belongs to it
        const val HYSTERESIS_DP = 12f     // how far clear of a key a finger must be to have left it
        const val POPUP_MIN_DP = 30f      // no alternate narrower than a fingertip
        const val GESTURE_DP = 28f        // a flick, rather than the drift of typing fast
        const val HIDE_DP = 34f           // down off the space bar puts the keyboard away
        const val VERTICAL_BIAS = 1.4f    // and clearly more up-and-down than side-to-side
        const val POPUP_LIFT_DP = 4f
        const val HANDLE_W_DP = 44f
        const val HANDLE_H_DP = 4f
        const val PANEL_PAD_DP = 6f      // the gap around the panel, so it floats rather than fills
        const val PANEL_RADIUS_DP = 22f
        const val TOOLBAR_DP = 42f
        const val TOOL_SLOT_DP = 56f
        const val CAP_AT_DP = 480f      // wider than a large phone: stop stretching, start centring
        const val CAP_WIDTH_DP = 460f
        const val SPLIT_AT_DP = 600f    // an unfolded Fold or a tablet: split, the way Samsung does
        const val SHORT_DP = 480f       // below this the window is a phone on its side, however wide
        const val NARROW_OFFER_DP = 400f  // narrower than this, the strip's question is asked in fewer words
        const val OFFER_BUTTON_DP = 56f   // no answer narrower than this, whatever its label
        const val OFFER_BUTTON_PAD_DP = 14f
        const val OFFER_GAP_DP = 6f
        const val OFFER_PILL_DP = 32f
        const val RAIL_BUTTON_DP = 52f    // each rail button's target, taller than a fingertip
        const val RAIL_GAP_DP = 8f
        const val RAIL_KEY_DP = 40f       // and what is drawn of it
        /** The strip's question and its answers, and the tip and its Got it, which are drawn the same way. */
        val OFFER_KINDS = setOf(KeyKind.OFFER, KeyKind.OFFER_YES, KeyKind.OFFER_NO, KeyKind.TIP, KeyKind.TIP_DONE)

        /** Read by a screen reader, not pressed: the strip's question, the count of what is selected, the menu's note. */
        val READ_ONLY_KINDS = setOf(KeyKind.OFFER, KeyKind.SELECTION, KeyKind.STYLE_NOTE, KeyKind.TIP)
        const val EDIT_HINT = "↑"            // the corner of the five keys a swipe up edits with
        const val MIN_TOOL_DP = 44f        // no toolbar button narrower than this; the list is cut from the end first
        const val SELECTION_SLOTS = 7f     // Hide, about two for the count, Style, Cut, Copy and Paste
        const val MIN_COUNT_DP = 11f       // the count shrinks to fit, but no smaller than this
        const val MENU_PAD_DP = 8f
        const val MENU_ROW_DP = 48f        // each style is a full fingertip tall
    }
}
