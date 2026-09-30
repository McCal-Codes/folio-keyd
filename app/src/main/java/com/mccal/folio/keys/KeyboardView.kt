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

        /** A word from the strip, held, and Don't suggest chosen from the menu that opens under it. */
        fun onForgetSuggestion(word: String) {}

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

        /**
         * Backspace went down. The moment to decide which way this press deletes: with shift held down by another
         * finger ([shiftHeld]), it takes the character after the cursor, for the tap and for every repeat of the hold
         * that may follow.
         */
        fun onBackspaceStart(shiftHeld: Boolean) {}

        /** Keyd's languages that are turned on in Android, in Android's order, for the list the globe opens. */
        fun languageChoices(): List<Language> = emptyList()

        /** A language picked from the globe's list. */
        fun onLanguage(language: Language) {}

        /** Other keyboards, from the globe's list: Android's own picker. */
        fun onOtherKeyboards() {}

        /** Language settings, from the globe's list: Keyd's own page for them. */
        fun onLanguageSettings() {}
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
            // The menu is about one word in one strip; a new strip is the next letter typed, or that word gone.
            closeForgetMenu()
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
            // Split once here rather than on every press of the period.
            periodItems = Settings.symbolList(value.periodSymbols)
            theme = Theme.of(context, value.appearance, value.highContrast, value.keyStyle, value.pureBlack)
            // The toolbar's keys depend on two of these switches, so it is placed again now rather than waiting on a
            // layout pass that only comes if the size changed.
            tools = placeToolbar()
            keyNodes.invalidateRoot()
            requestLayout()
            invalidate()
        }

    /**
     * Whether the password manager's chips are over the strip. Its own buttons and words step aside while they are,
     * so the space between two chips is not a Hide or a word waiting under a finger. What is typed still goes on
     * being suggested underneath, and comes back the moment the chips go.
     */
    var autofilling: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            tools = placeToolbar()
            keyNodes.invalidateRoot()
            invalidate()
        }

    /** The colors the keys are drawn in now, for the password manager to draw its chips to match. */
    internal val currentTheme: Theme get() = theme

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

    /**
     * Whether nothing typed here is kept: a password field, a field whose app asked for no personalized learning
     * (a private tab, most often), or an app someone turned learning off for. The service decides, since it is the one
     * that knows the per-app settings; the view only says so, at the left of the toolbar, or as just the icon at the
     * left of the strip while there are words in it, so the badge never costs a suggestion its room.
     *
     * A password field gets it too. Nothing else there said what was off, and the same words for every field Keyd
     * keeps nothing from is easier to learn than one sign for passwords and another for the rest.
     */
    var notLearning: Boolean = false
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
            val sameHeight = value.size == field.size
            field = value
            // Fingers already down keep the key they pressed. The board is rebuilt constantly while typing - one
            // capital letter turns shift off again and replaces every key - and a thumb halfway through the next
            // letter when that happens must still get the letter it pressed.
            //
            // The height depends only on how many rows there are, so a board with the same number (a shift change,
            // most of the time) is placed again where it already is. Asking for a layout there would have the whole
            // input window measured and laid out again after nearly every capital letter, for nothing.
            if (sameHeight && isLaidOut && !isLayoutRequested) arrange() else requestLayout()
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
    private class Popup(val items: List<String>, val boxes: List<Box>, var choice: Int) {
        /** The same row as keys, so a screen reader can find each one and type it. */
        val placements: List<Placement> = items.mapIndexed { index, item -> Placement(Key(item), boxes[index]) }
    }

    private var popup: Popup? = null
        set(value) {
            field = value
            keyNodes.invalidateRoot()
        }

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

        /** Where the finger is now, for the two-finger swipe, which has to know where the other finger is too. */
        var x = downX
        var y = downY

        /** The press was one of two fingers that swiped to undo or redo. It types nothing, whatever it was on. */
        var twoFinger = false
    }

    private val repeat = Handler(Looper.getMainLooper())
    private var repeatingFor: Press? = null
    private val repeatBackspace = object : Runnable {
        override fun run() {
            val press = repeatingFor ?: return
            press.handled = true
            putOfferAway()
            listener?.onBackspaceRepeat()
            repeat.postDelayed(this, settings.backspaceSpeed.millis)
        }
    }

    /** What holding the period offers, from [Settings.periodSymbols]. */
    private var periodItems: List<String> = Settings.symbolList(Settings.DEFAULT_PERIOD_SYMBOLS)

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
        closeLanguageMenu()
        closeForgetMenu()
        pill = null
        repeat.removeCallbacks(pillFade)
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
        closeLanguageMenu()
        closeForgetMenu()
        repeat.removeCallbacks(pillFade)
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

    /** The gap between a split keyboard's halves, which the strip's words stay out of. Both 0 when not split. */
    private var gapLeft = 0f
    private var gapRight = 0f

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
    /**
     * The parts of the strip something can be placed in, left and right in this view's pixels: across the keys, or
     * one per half when the keyboard is split, so nothing to press sits on the gap between them.
     */
    internal val stripLanes: List<Pair<Int, Int>> get() = lanes

    /** The bottom of the strip, in this view's pixels. Its top is the board's. */
    internal val stripBottom: Int get() = (panelPad + toolbarHeight).roundToInt()

    private var lanes: List<Pair<Int, Int>> = emptyList()

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
        gapLeft = 0f
        gapRight = 0f
        lanes = listOf(keysLeft.roundToInt() to keysRight.roundToInt())

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
                gapLeft = edge + half
                gapRight = edge + half + gutter
                lanes = listOf(
                    edge.roundToInt() to (edge + half).roundToInt(),
                    (edge + half + gutter).roundToInt() to (edge + 2 * half + gutter).roundToInt(),
                )
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
        if (width == 0 || searchKeys || autofilling) return emptyList()
        val selected = selection
        if (selected != null && settings.selectionTools && !rules.password) return placeSelection(selected)
        if (suggestions.isNotEmpty() && !rules.password) return privateStrip(placeSuggestions())
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
        var rest = items.drop(lead)
        var restSlot = slot
        if (notLearning) {
            // The badge takes the start in place of Hide and the first button, as the mockup has it; the editing
            // buttons stay at the end. Hide is still a swipe down off the space bar, and the system's own button.
            // For the words to be read whole, the buttons first close up, never below a fingertip; only if that is
            // not enough does the end give up a button, and the badge's words are drawn a little smaller before that.
            val label = context.getString(R.string.not_learning_here)
            val wanted = privateWidth(label)
            fun fits() = right - left - rest.size * restSlot >= wanted * PRIVATE_SQUEEZE
            if (rest.isNotEmpty()) restSlot = min(slot, max(PRIVATE_TOOL_DP * dp, (right - left - wanted) / rest.size))
            while (rest.isNotEmpty() && !fits()) rest = rest.dropLast(1)
            placed += Placement(Key(label, KeyKind.PRIVATE), Box(left, top, right - rest.size * restSlot, bottom))
        } else {
            for (index in 0 until lead) {
                placed += Placement(items[index], Box(left + index * slot, top, left + (index + 1) * slot, bottom))
            }
        }
        var x = right - rest.size * restSlot
        for (key in rest) {
            placed += Placement(key, Box(x, top, x + restSlot, bottom))
            x += restSlot
        }
        return placed
    }

    /**
     * The strip with room made at its start for the eye, when nothing here is learned: the words are narrowed to
     * the right of it, and the emoji and the mic are left where they are. Added last, so the first word is still the
     * first placement, which is how the strip knows which one is what was typed.
     *
     * On a split keyboard only the left half's words make room, inside that half, so none is pushed onto the gap
     * and the right half still starts where its keys do.
     */
    private fun privateStrip(strip: List<Placement>): List<Placement> {
        if (!notLearning) return strip
        val start = keysLeft
        val words = strip.filter { it.key.kind == KeyKind.SUGGESTION }
        val split = gapRight > gapLeft && words.none { it.box.left < gapLeft && it.box.right > gapLeft + 0.5f }
        fun squeezed(it: Placement) = it.key.kind == KeyKind.SUGGESTION && (!split || it.box.right <= gapLeft + 0.5f)
        val end = if (split) gapLeft
        else strip.filter { it.key.kind != KeyKind.SUGGESTION }.minOfOrNull { it.box.left } ?: keysRight
        val badge = min(PRIVATE_SLOT_DP * dp, (end - start) / (strip.count(::squeezed) + 1))
        val scale = (end - start - badge) / (end - start)
        fun moved(x: Float) = start + badge + (x - start) * scale
        val label = context.getString(R.string.not_learning_here)
        return strip.map {
            if (!squeezed(it)) it
            else Placement(it.key, Box(moved(it.box.left), it.box.top, moved(it.box.right), it.box.bottom))
        } + Placement(Key(label, KeyKind.PRIVATE), Box(start, strip.first().box.top, start + badge, strip.first().box.bottom))
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
     * How many words the strip has room for: one per [WORD_SLOT_DP] of its width, so three on a narrow phone, four
     * on a Fold's cover screen, as 0.4.0 already offered there, and five unfolded. Never fewer than three, the most a phone keyboard has ever offered, and never more
     * than five, past which a strip is a list to read rather than something to glance at.
     *
     * Counted across the whole strip, a split keyboard's gap included: the words go either side of it.
     */
    internal fun wordSlots(): Int =
        ((keysRight - keysLeft) / dp / WORD_SLOT_DP).toInt().coerceIn(MIN_WORDS, MAX_WORDS)

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
        val suggestions = suggestions.take(wordSlots())
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
        val placed = ArrayList<Placement>(suggestions.size + 2)
        fun share(from: Int, until: Int, start: Float, end: Float) {
            val slot = (end - start) / (until - from)
            for (index in from until until) {
                val word = suggestions[index]
                val at = start + (index - from) * slot
                placed += Placement(Key(word, KeyKind.SUGGESTION, output = word), Box(at, top, at + slot, bottom))
            }
        }
        if (gapRight > gapLeft && words > gapRight) {
            // Split: the first half of the words over the left keys and the rest over the right, with the emoji and
            // the mic, so the gap between the halves never runs through a word.
            val leftCount = (suggestions.size + 1) / 2
            share(0, leftCount, left, gapLeft)
            if (leftCount < suggestions.size) share(leftCount, suggestions.size, gapRight, words)
        } else {
            share(0, suggestions.size, left, words)
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
        drawLanguageMenu(canvas)
        drawForgetMenu(canvas)
        drawPreview(canvas)
        drawPopup(canvas)
        drawPill(canvas)
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
                // No line where a word starts a split keyboard's right half: the gap already divides them.
                if (!literal && abs(box.left - gapRight) > 0.5f) {
                    fill.color = theme.hint
                    canvas.drawRect(box.left, cy - size * 0.5f, box.left + max(1f, dp * 0.5f), cy + size * 0.5f, fill)
                }
                continue
            }
            if (placement.key.kind == KeyKind.PRIVATE) {
                drawPrivate(canvas, placement)
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

    /**
     * The eye with a line through it, then "Not learning here" in the keys' own label color, sized down to fit before
     * it is ever cut short. In the strip there is only room for the eye, which is drawn alone in the middle of its
     * place; a screen reader reads the words either way.
     */
    private fun privateGlyph() = min(toolbarHeight * 0.46f, 18 * dp)

    /** How wide the badge is with its words at their usual size: the eye, the gap, the words, and a margin each side. */
    private fun privateWidth(label: String): Float {
        text.textSize = selectionTextSize()
        sizedAt = -1f
        return 2 * PRIVATE_PAD_DP * dp + privateGlyph() + PRIVATE_GAP_DP * dp + text.measureText(label)
    }

    private fun drawPrivate(canvas: Canvas, placement: Placement) {
        val box = placement.box
        val cy = (box.top + box.bottom) / 2
        val glyph = privateGlyph()
        stroke.color = theme.label
        stroke.strokeWidth = max(1.5f * dp, glyph * 0.09f)
        text.textSize = selectionTextSize()
        sizedAt = -1f
        val pad = PRIVATE_PAD_DP * dp
        val start = box.left + pad + glyph + PRIVATE_GAP_DP * dp
        val room = box.right - start - pad
        if (room < text.textSize * 3) {
            Icons.eyeOff(canvas, (box.left + box.right) / 2, cy, glyph, stroke)
            return
        }
        Icons.eyeOff(canvas, box.left + pad + glyph / 2, cy, glyph, stroke)
        var label = placement.key.label
        val wide = text.measureText(label)
        if (wide > room) text.textSize = max(MIN_COUNT_DP * dp, text.textSize * room / wide)
        if (text.measureText(label) > room) {
            val fits = text.breakText(label, true, room - text.measureText("…"), null)
            label = label.take(fits).trimEnd() + "…"
        }
        text.color = theme.label
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(label, start, cy - (text.descent() + text.ascent()) / 2, text)
        text.textAlign = Paint.Align.CENTER
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
        if (key.kind != KeyKind.CHAR || press.twoFinger) return null
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

    // ---- the globe's language list ------------------------------------------------------------------------------

    /**
     * The languages, then the two ways out, in a column above the globe.
     *
     * Held open, [opener] is the finger that held the globe: sliding it up over the list picks with [choice], and
     * letting go on one takes it, the way the accents work. Let go anywhere else and the list stays open for a tap.
     * [divider] is where the line between the languages and the two ways out is drawn.
     */
    private class LanguageMenu(val panel: Box, val items: List<Placement>, val divider: Float) {
        var opener: Press? = null
        var choice = -1
    }

    private var languageMenu: LanguageMenu? = null

    /** What the list is showing: the languages, then Other keyboards and Language settings. Empty when it is closed. */
    internal val languageMenuItems: List<Placement> get() = languageMenu?.items.orEmpty()

    private fun openLanguageMenu(globe: Placement, opener: Press?) {
        closePopup()
        closeStyleMenu()
        val languages = listener?.languageChoices().orEmpty().ifEmpty { listOf(language) }
        val keys = languages.map { Key(it.ownName, KeyKind.LANGUAGE, output = it.name) } + listOf(
            Key(context.getString(R.string.globe_other_keyboards), KeyKind.OTHER_KEYBOARDS),
            Key(context.getString(R.string.globe_language_settings), KeyKind.LANGUAGE_SETTINGS),
        )
        text.textSize = MENU_TEXT_DP * dp
        sizedAt = -1f
        val pad = MENU_INSET_DP * dp
        val gap = MENU_DIVIDER_DP * dp
        val widest = keys.maxOf { text.measureText(it.label) }
        val across = min(keysRight - keysLeft, max(MENU_MIN_WIDTH_DP * dp, widest + 2 * MENU_TEXT_PAD_DP * dp + MENU_CHECK_DP * dp))
        val left = globe.box.left.coerceIn(keysLeft, max(keysLeft, keysRight - across))
        val bottom = globe.box.top - POPUP_LIFT_DP * dp
        // As tall as a fingertip where there is room, and shorter rather than off the top where there is not.
        // Never shorter than its own text, though: past that the list climbs over the toolbar rather than overlap.
        val row = max(MENU_MIN_ITEM_DP * dp, min(MENU_ITEM_DP * dp, (bottom - panelPad - 2 * pad - gap) / keys.size))
        val top = bottom - 2 * pad - gap - row * keys.size
        var y = top + pad
        val items = keys.mapIndexed { index, key ->
            if (index == languages.size) y += gap
            Placement(key, Box(left + pad, y, left + across - pad, y + row)).also { y += row }
        }
        val divider = top + pad + languages.size * row + gap / 2
        languageMenu = LanguageMenu(Box(left, top, left + across, bottom), items, divider).also { it.opener = opener }
        keyNodes.invalidateRoot()
        invalidate()
    }

    private fun closeLanguageMenu() {
        if (languageMenu == null) return
        languageMenu = null
        keyNodes.invalidateRoot()
        invalidate()
    }

    private fun drawLanguageMenu(canvas: Canvas) {
        val menu = languageMenu ?: return
        val radius = theme.keyRadiusDp * dp
        val panel = menu.panel
        scratch.set(panel.left, panel.top, panel.right, panel.bottom)
        fill.color = theme.preview
        canvas.drawRoundRect(scratch, radius, radius, fill)
        fill.color = theme.hint
        canvas.drawRect(panel.left + MENU_TEXT_PAD_DP * dp, menu.divider, panel.right - MENU_TEXT_PAD_DP * dp,
            menu.divider + max(1f, dp * 0.5f), fill)
        text.textSize = MENU_TEXT_DP * dp
        sizedAt = -1f
        text.textAlign = Paint.Align.LEFT
        for ((index, item) in menu.items.withIndex()) {
            val box = item.box
            val lit = index == menu.choice || isHeld(item)
            if (lit) {
                scratch.set(box.left, box.top, box.right, box.bottom)
                fill.color = theme.accent
                canvas.drawRoundRect(scratch, radius * 0.7f, radius * 0.7f, fill)
            }
            val ink = if (lit) theme.onAccent else theme.label
            text.color = ink
            val cy = (box.top + box.bottom) / 2
            canvas.drawText(item.key.label, box.left + MENU_TEXT_PAD_DP * dp - MENU_INSET_DP * dp, cy - (text.descent() + text.ascent()) / 2, text)
            if (item.key.kind == KeyKind.LANGUAGE && item.key.output == language.name) {
                // A tick, drawn rather than typed, so it looks the same whatever font the phone has.
                val size = MENU_CHECK_DP * dp * 0.45f
                val cx = box.right - MENU_CHECK_DP * dp / 2
                stroke.color = ink
                stroke.strokeWidth = max(1.5f * dp, size * 0.14f)
                canvas.drawLine(cx - size / 2, cy, cx - size / 8, cy + size * 0.38f, stroke)
                canvas.drawLine(cx - size / 8, cy + size * 0.38f, cx + size / 2, cy - size * 0.4f, stroke)
            }
        }
        text.textAlign = Paint.Align.CENTER
    }

    // ---- holding a word in the strip ----------------------------------------------------------------------------

    /**
     * Don't suggest, in a small menu just under the word that was held.
     *
     * Held open, [opener] is the finger that held the word: sliding it down onto the menu and letting go takes it, as
     * the globe's list works. Let go anywhere else and the menu stays open for a tap; any other touch closes it.
     * [lit] is whether the opener is over it now.
     */
    private class ForgetMenu(val item: Placement) {
        var opener: Press? = null
        var lit = false
    }

    private var forgetMenu: ForgetMenu? = null

    /** The menu's one item while it is open, and nothing when it is closed. */
    internal val forgetMenuItems: List<Placement> get() = listOfNotNull(forgetMenu?.item)

    /**
     * Whether holding this key offers Don't suggest: any word in the strip, except what was typed. That one is
     * already in the text, so there is nothing to stop suggesting; the emoji and the mic are not words at all.
     */
    private fun forgettable(placement: Placement): Boolean =
        placement.key.kind == KeyKind.SUGGESTION && !(typedFirst && placement === tools.firstOrNull())

    private fun openForgetMenu(on: Placement, opener: Press?) {
        closePopup()
        closeStyleMenu()
        closeLanguageMenu()
        val word = on.key.output
        val label = context.getString(R.string.forget_suggestion, word)
        text.textSize = MENU_TEXT_DP * dp
        sizedAt = -1f
        // Wide enough for the trash can and the words, never wider than the keys; a long word is cut short in the
        // drawing, and read whole by a screen reader.
        val across = min(
            keysRight - keysLeft,
            text.measureText(label) + FORGET_ICON_DP * dp + 3 * MENU_TEXT_PAD_DP * dp,
        )
        val centre = (on.box.left + on.box.right) / 2
        val left = (centre - across / 2).coerceIn(keysLeft, max(keysLeft, keysRight - across))
        // Under the strip, over the top row of keys, a full fingertip tall.
        val top = on.box.bottom + POPUP_LIFT_DP * dp
        val item = Placement(Key(label, KeyKind.FORGET, output = word), Box(left, top, left + across, top + MENU_ROW_DP * dp))
        forgetMenu = ForgetMenu(item).also { it.opener = opener }
        keyNodes.invalidateRoot()
        invalidate()
    }

    private fun closeForgetMenu() {
        if (forgetMenu == null) return
        forgetMenu = null
        keyNodes.invalidateRoot()
        invalidate()
    }

    /**
     * The word goes from the strip at once, rather than when the service next answers. If what is left is only
     * what was typed, the strip has nothing to offer and the toolbar comes back, as it does for a word with no
     * suggestions at all.
     */
    private fun dropSuggestion(word: String) {
        val left = suggestions.filter { it != word }
        suggestions = if (typedFirst && left.size <= 1 && suggestedEmoji == null) emptyList() else left
    }

    private fun drawForgetMenu(canvas: Canvas) {
        val menu = forgetMenu ?: return
        val box = menu.item.box
        val radius = theme.keyRadiusDp * dp
        scratch.set(box.left, box.top, box.right, box.bottom)
        fill.color = if (menu.lit || isHeld(menu.item)) blend(theme.preview, theme.pressTint) else theme.preview
        // A soft shadow, since it sits over keys drawn in the same color in the light themes and would vanish into
        // them without one.
        fill.setShadowLayer(10 * dp, 0f, 3 * dp, FORGET_SHADOW)
        canvas.drawRoundRect(scratch, radius, radius, fill)
        fill.clearShadowLayer()
        val cy = (box.top + box.bottom) / 2
        val icon = FORGET_ICON_DP * dp
        val iconX = box.left + MENU_TEXT_PAD_DP * dp + icon / 2
        stroke.color = destructive()
        stroke.strokeWidth = max(1.5f * dp, icon * 0.09f)
        Icons.trash(canvas, iconX, cy, icon, stroke)
        text.textSize = MENU_TEXT_DP * dp
        sizedAt = -1f
        val start = iconX + icon / 2 + MENU_TEXT_PAD_DP * dp * 0.7f
        val room = box.right - MENU_TEXT_PAD_DP * dp - start
        var label = menu.item.key.label
        if (text.measureText(label) > room) {
            val fits = text.breakText(label, true, max(0f, room - text.measureText("…")), null)
            label = label.take(fits).trimEnd() + "…"
        }
        text.color = theme.label
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(label, start, cy - (text.descent() + text.ascent()) / 2, text)
        text.textAlign = Paint.Align.CENTER
    }

    /**
     * The red of a delete, as iOS draws it: lighter on a dark menu, where the usual red is too dim to read, and the
     * usual one on a light menu. High contrast keeps the label's own color, the one it has already checked.
     */
    private fun destructive(): Int = when {
        settings.highContrast -> theme.label
        Color.luminance(theme.preview) < 0.5f -> DESTRUCTIVE_ON_DARK
        else -> DESTRUCTIVE_ON_LIGHT
    }

    // ---- the pill a two-finger swipe leaves ----------------------------------------------------------------------

    /** "Undo Typing" or "Redo Typing", over the toolbar until [pillUntil], fading for the last part of that. */
    private var pill: String? = null
    private var pillUntil = 0L
    private val pillFade = Runnable { invalidate() }

    /** What the pill says while it is on screen. */
    internal val pillLabel: String?
        get() = pill?.takeIf { android.os.SystemClock.uptimeMillis() < pillUntil }

    private fun showPill(label: String) {
        pill = label
        pillUntil = android.os.SystemClock.uptimeMillis() + PILL_MS
        repeat.removeCallbacks(pillFade)
        repeat.postDelayed(pillFade, PILL_MS - PILL_FADE_MS)
        announce(label)
        invalidate()
    }

    /**
     * Said aloud by a screen reader, since the pill is only drawn. The same event View's own announcement sends,
     * built here because that call is on its way out of Android.
     */
    private fun announce(words: String) {
        val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        if (manager == null || !manager.isEnabled) return
        val event = AccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
        onInitializeAccessibilityEvent(event)
        event.text.add(words)
        event.contentDescription = null
        parent?.requestSendAccessibilityEvent(this, event)
    }

    private fun drawPill(canvas: Canvas) {
        val label = pill ?: return
        val left = pillUntil - android.os.SystemClock.uptimeMillis()
        if (left <= 0) {
            pill = null
            return
        }
        val alpha = min(1f, left.toFloat() / PILL_FADE_MS)
        if (left < PILL_FADE_MS) postInvalidateOnAnimation()
        text.textSize = min(15 * dp, OFFER_PILL_DP * dp * 0.45f)
        sizedAt = -1f
        val tall = OFFER_PILL_DP * dp
        val icon = tall * 0.42f
        val across = text.measureText(label) + icon + 3 * PILL_PAD_DP * dp
        // Over the toolbar, or where it would be; the board's middle, not the window's, when it is one-handed.
        val cx = (boardLeft + boardRight) / 2
        val cy = panelPad + max(toolbarHeight, tall + 4 * dp) / 2
        scratch.set(cx - across / 2, cy - tall / 2, cx + across / 2, cy + tall / 2)
        fill.color = theme.key
        fill.alpha = (255 * alpha).roundToInt()
        canvas.drawRoundRect(scratch, tall / 2, tall / 2, fill)
        fill.alpha = 255
        stroke.color = theme.label
        stroke.alpha = (255 * alpha).roundToInt()
        stroke.strokeWidth = max(1.5f * dp, icon * 0.1f)
        val iconX = scratch.left + PILL_PAD_DP * dp + icon / 2
        if (label == context.getString(R.string.pill_redo)) Icons.redo(canvas, iconX, cy, icon, stroke)
        else Icons.undo(canvas, iconX, cy, icon, stroke)
        stroke.alpha = 255
        text.color = theme.label
        text.alpha = (255 * alpha).roundToInt()
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(label, iconX + icon / 2 + PILL_PAD_DP * dp, cy - (text.descent() + text.ascent()) / 2, text)
        text.textAlign = Paint.Align.CENTER
        text.alpha = 255
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
        if (placement.key.kind == KeyKind.GLOBE) return openLanguageMenu(placement, null)
        val items = if (placement.key.holdsSymbols) periodItems
            else Alternates.forKey(placement.key.label, placement.key.hint, language)
        // The same rule a finger gets: one alternate is taken, not offered.
        if (items.size < 2) return
        popup = openPopup(placement, items)
    }

    /**
     * The globe's list, the rail, then the Style menu: all sit close enough to keys that a key's slop would take
     * them, and the list sits over the keys.
     */
    private fun keyAt(x: Float, y: Float): Placement? =
        forgetMenu?.item?.takeIf { it.box.contains(x, y) }
            ?: languageMenu?.items?.firstOrNull { it.box.contains(x, y) }
            ?: rail.firstOrNull { it.box.contains(x, y) } ?: styleMenu?.choices?.firstOrNull { it.box.contains(x, y) }
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
                languageMenu?.opener = null
                forgetMenu?.opener = null
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
        forgetMenu?.let { menu ->
            // The same as the globe's list: the menu is pressed like a key, and a touch anywhere else only closes it,
            // since a finger reaching for Don't suggest and missing it did not mean the key underneath.
            if (menu.opener != null) return
            if (!menu.item.box.contains(x, y)) {
                closeForgetMenu()
                return
            }
        }
        languageMenu?.let { menu ->
            // The list is open for a tap: one of its rows is pressed like a key, and anywhere else only closes it.
            // Nothing else is typed, since a finger reaching for a row and missing it meant the list, not the key.
            if (menu.opener != null) return
            if (menu.items.none { it.box.contains(x, y) }) {
                closeLanguageMenu()
                return
            }
        }
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
            // A shift held down while backspace is pressed was held for this, and letting go of it changes nothing.
            var shiftHeld = false
            for (other in presses.values) {
                if (other !== press && other.origin.key.kind == KeyKind.SHIFT && !other.swiping) {
                    other.handled = true
                    shiftHeld = true
                }
            }
            listener?.onBackspaceStart(shiftHeld)
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
        if (forgettable(press.origin)) {
            // Tapped, a word goes in; held, the menu to stop it being suggested opens under it.
            val task = Runnable {
                Haptics.feel(this, settings.vibration, Haptics.Touch.HOLD)
                press.handled = true
                openForgetMenu(press.origin, press)
            }
            press.hold = task
            repeat.postDelayed(task, settings.holdDelay.millis)
            return
        }
        if (press.origin.key.kind == KeyKind.GLOBE) {
            // Tapped, the globe still goes to the next keyboard; held, it lists the languages.
            val task = Runnable {
                Haptics.feel(this, settings.vibration, Haptics.Touch.HOLD)
                press.handled = true
                listener?.onGesture(Tip.GLOBE_LANGUAGES)
                openLanguageMenu(press.origin, press)
            }
            press.hold = task
            repeat.postDelayed(task, settings.holdDelay.millis)
            return
        }
        val items = holdItems(press.origin.key)
        if (items.isEmpty()) return
        val task = Runnable {
            Haptics.feel(this, settings.vibration, Haptics.Touch.HOLD)
            if (items.size == 1) {
                // Nothing to choose between, so holding simply gives it.
                press.handled = true
                type(items.first())
            } else {
                if (press.origin.key.holdsSymbols) listener?.onGesture(Tip.PERIOD_SYMBOLS)
                popup = openPopup(press.origin, items)
            }
            invalidate()
        }
        press.hold = task
        repeat.postDelayed(task, settings.holdDelay.millis)
    }

    /**
     * What holding a key offers: the period's symbols on the period, the accents and the corner digit on a letter,
     * or only the digit with accents switched off. Nothing for any other key.
     */
    private fun holdItems(key: Key): List<String> = when {
        key.kind != KeyKind.CHAR -> emptyList()
        key.holdsSymbols -> periodItems
        settings.accents -> Alternates.forKey(key.label, key.hint, language)
        else -> listOfNotNull(key.hint)   // the corner digit still works; only the accents are switched off
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
        press.x = x
        press.y = y
        val dy = y - press.downY
        forgetMenu?.let { menu ->
            if (menu.opener !== press) return@let
            val over = menu.item.box.contains(x, y)
            if (over != menu.lit) {
                menu.lit = over
                invalidate()
            }
            return
        }
        languageMenu?.let { menu ->
            if (menu.opener !== press) return@let
            val over = menu.items.indexOfFirst { it.box.contains(x, y) }
            if (over != menu.choice) {
                menu.choice = over
                invalidate()
            }
            return
        }
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
        if (press.twoFinger) return
        val dx = x - press.downX
        // Two fingers on the way across the keys together. Until they have gone far enough to count, the gestures
        // one finger would start - the cursor, a selection, deleting a word - wait, so neither goes off underneath.
        val partner = partnerOf(press)
        val paired = partner != null && pairedDirection(press, partner) != 0
        if (partner != null && paired) {
            cancelHold(press)
            cancelHold(partner)
            if (twoFingerSwipe(press, partner)) return
        }
        when (press.origin.key.kind) {
            // The space bar is wide and a fast thumb wanders across it. The swipe only begins after a deliberate
            // journey - a whole key's worth - and counts its characters from there, so a drifted space is a space.
            // Down off the space bar puts the keyboard away, the way swiping a sheet down closes it. Checked
            // before the cursor, because a downward journey is not a sideways one however far it goes.
            KeyKind.SPACE -> if (paired) {
                Unit
            } else if (
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
            KeyKind.SHIFT -> if (!paired && settings.shiftSelect && (press.swiping || abs(dx) > CURSOR_START_DP * dp)) {
                if (!press.swiping) listener?.onGesture(Tip.SHIFT_SELECT)
                val steps = cursorSteps(press, x, dx)
                if (steps != 0) listener?.onSelectMove(steps)
            }
            KeyKind.BACKSPACE -> if (!paired && settings.deleteWordSwipe && dx < -DELETE_WORD_DP * dp && !press.swiping) {
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

    /**
     * The other finger, when exactly two are down on the keys and neither has started anything of its own yet: a
     * swipe, a held row, an edit. Only then can the two of them be a two-finger swipe.
     */
    private fun partnerOf(press: Press): Press? {
        if (!settings.twoFingerUndo || searchKeys || presses.size != 2 || popup != null || languageMenu != null) return null
        var other: Press? = null
        for (candidate in presses.values) if (candidate !== press) other = candidate
        val partner = other ?: return null
        for (one in arrayOf(press, partner)) {
            if (one.swiping || one.handled || one.editing || !onKeys(one.origin)) return null
        }
        return partner
    }

    private fun onKeys(placement: Placement): Boolean {
        for (key in placedKeys) if (key === placement) return true
        return false
    }

    /**
     * Which way two fingers are going together: -1 left, 1 right, 0 when they aren't. Both have to have moved a
     * little, the same way, and more across than up or down; fingers typing two letters at once do none of that.
     */
    private fun pairedDirection(a: Press, b: Press): Int {
        val ax = a.x - a.downX
        val bx = b.x - b.downX
        val start = TWO_FINGER_START_DP * dp
        if (abs(ax) < start || abs(bx) < start || sign(ax) != sign(bx)) return 0
        if (abs(ax) < abs(a.y - a.downY) || abs(bx) < abs(b.y - b.downY)) return 0
        return sign(ax).toInt()
    }

    /**
     * Undo or redo, once both fingers are past [TWO_FINGER_DP] and clearly sideways. The presses they began are
     * spent: nothing they were on is typed, and the gesture is over for them until they lift.
     */
    private fun twoFingerSwipe(a: Press, b: Press): Boolean {
        val direction = pairedDirection(a, b)
        val far = TWO_FINGER_DP * dp
        for (one in arrayOf(a, b)) {
            val across = abs(one.x - one.downX)
            if (across < far || across < abs(one.y - one.downY) * TWO_FINGER_BIAS) return false
        }
        for (one in arrayOf(a, b)) {
            one.twoFinger = true
            one.handled = true
            one.swiping = true
            cancelHold(one)
            if (repeatingFor === one) stopRepeat()
        }
        Haptics.feel(this, settings.vibration)
        if (direction < 0) listener?.onUndo() else listener?.onRedo()
        listener?.onGesture(Tip.TWO_FINGER_UNDO)
        showPill(context.getString(if (direction < 0) R.string.pill_undo else R.string.pill_redo))
        return true
    }

    /** True when the lift was a press on a key, which is what [performClick] is for. */
    private fun up(pointer: Int, x: Float, y: Float): Boolean {
        val press = presses.remove(pointer) ?: return false
        cancelHold(press)
        forgetMenu?.let { menu ->
            if (menu.opener !== press) return@let
            // Let go on it, it is taken; anywhere else, it stays for a tap.
            menu.opener = null
            val taken = menu.lit
            menu.lit = false
            invalidate()
            if (!taken) return false
            dispatch(menu.item.key)
            return true
        }
        languageMenu?.let { menu ->
            if (menu.opener !== press) return@let
            // Let go on a row, it is taken; anywhere else, the list stays for a tap.
            menu.opener = null
            val chosen = menu.items.getOrNull(menu.choice)
            menu.choice = -1
            invalidate()
            if (chosen == null) return false
            dispatch(chosen.key)
            return true
        }
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
        closeLanguageMenu()
        closeForgetMenu()
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
            KeyKind.OFFER, KeyKind.SELECTION, KeyKind.STYLE_NOTE, KeyKind.TIP, KeyKind.PRIVATE -> Unit
            KeyKind.TIP_DONE -> tip?.let { shown ->
                tip = null
                l.onTipDone(shown)
            }
            KeyKind.OFFER_YES, KeyKind.OFFER_NO -> offer?.let { asked ->
                offer = null
                l.onOffer(asked, accepted = key.kind == KeyKind.OFFER_YES)
            }
            KeyKind.LANGUAGE -> l.onLanguage(Language.valueOf(key.output))
            KeyKind.OTHER_KEYBOARDS -> l.onOtherKeyboards()
            KeyKind.LANGUAGE_SETTINGS -> l.onLanguageSettings()
            KeyKind.FORGET -> {
                dropSuggestion(key.output)
                l.onForgetSuggestion(key.output)
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

    /**
     * Every key a screen reader can find: the keys, the toolbar, the Style menu and the globe's list while they are
     * open, a held key's row, then the rail.
     */
    private fun nodes(): List<Placement> =
        placedKeys + tools + styleMenuItems + languageMenuItems + popup?.placements.orEmpty() + rail + forgetMenuItems

    private fun nodeAt(id: Int): Placement? = nodes().getOrNull(id)

    /** What sits over the keys while it is open, and hides the keys under it from a screen reader. */
    private fun overlays(): List<Box> = listOfNotNull(styleMenu?.panel, languageMenu?.panel, forgetMenu?.item?.box)

    /**
     * Holding a key, for a screen reader: the same row or list a finger gets, left open to be explored and tapped,
     * or the one thing a key with a single alternate gives. False for a key that holding does nothing on.
     */
    private fun holdFor(placement: Placement): Boolean {
        if (forgettable(placement)) {
            openForgetMenu(placement, null)
            return true
        }
        if (placement.key.kind == KeyKind.GLOBE) {
            openLanguageMenu(placement, null)
            return true
        }
        val items = holdItems(placement.key)
        when {
            items.isEmpty() -> return false
            items.size == 1 -> type(items.first())
            else -> popup = openPopup(placement, items)
        }
        invalidate()
        return true
    }

    private fun holds(placement: Placement): Boolean =
        placement.key.kind == KeyKind.GLOBE || forgettable(placement) || (onKeys(placement) && holdItems(placement.key).isNotEmpty())

    /**
     * The keys are drawn, not laid out, so a screen reader would find one blank rectangle. Each key is published as a
     * virtual view with the name it should be read by: "Backspace", not a glyph.
     */
    private inner class KeyNodes : ExploreByTouchHelper(this@KeyboardView) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val all = nodes()
            // A held key's row and the menus sit over the keys, so under them only they are there to be found.
            val over = forgetMenuItems + popup?.placements.orEmpty() + languageMenuItems + styleMenuItems
            over.firstOrNull { it.box.contains(x, y) }?.let { found -> return all.indexOfFirst { it === found } }
            if (overlays().any { it.contains(x, y) }) return HOST_ID
            val index = all.indexOfFirst { it.box.contains(x, y) }
            return if (index < 0) HOST_ID else index
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            val panels = overlays()
            for ((index, placement) in nodes().withIndex()) {
                val box = placement.box
                val hidden = index < placedKeys.size &&
                    panels.any { it.contains((box.left + box.right) / 2, (box.top + box.bottom) / 2) }
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
                // Whatever a held finger gets - accents, the period's symbols, the language list - a double tap
                // and hold gets too.
                if (forgettable(placement)) {
                    // Said as what it does, since holding a word is not something a strip usually offers.
                    node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        AccessibilityNodeInfo.ACTION_LONG_CLICK, context.getString(R.string.forget_suggestion_action),
                    ))
                } else if (holds(placement)) {
                    node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
                }
            }
            if (placement.key.kind == KeyKind.LANGUAGE) {
                node.isCheckable = true
                node.isChecked = placement.key.output == language.name
            }
            val box = placement.box
            node.setBoundsInParent(
                Rect(box.left.toInt(), box.top.toInt(), box.right.roundToInt(), box.bottom.roundToInt()),
            )
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            val placement = nodeAt(id) ?: return false
            if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) {
                if (!holds(placement) || !holdFor(placement)) return false
                sendEventForVirtualView(id, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
                return true
            }
            if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
            // A held key's row goes with whatever is pressed next, its own choices included, as it does for a finger.
            closePopup()
            // The same start a finger gives it, so shift and backspace deletes forward here too.
            if (placement.key.kind == KeyKind.BACKSPACE) listener?.onBackspaceStart(shiftHeld = false)
            dispatch(placement.key)
            sendEventForVirtualView(id, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    private companion object {
        const val FIRST_REPEAT_MS = 400L
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
        const val WORD_SLOT_DP = 100f     // the strip width each word it offers wants
        const val MIN_WORDS = 3
        const val MAX_WORDS = 5
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
        val READ_ONLY_KINDS = setOf(KeyKind.OFFER, KeyKind.SELECTION, KeyKind.STYLE_NOTE, KeyKind.TIP, KeyKind.PRIVATE)
        const val EDIT_HINT = "↑"            // the corner of the five keys a swipe up edits with
        const val MIN_TOOL_DP = 44f        // no toolbar button narrower than this; the list is cut from the end first
        const val SELECTION_SLOTS = 7f     // Hide, about two for the count, Style, Cut, Copy and Paste
        const val MIN_COUNT_DP = 11f       // the count shrinks to fit, but no smaller than this
        const val MENU_PAD_DP = 8f
        const val MENU_ROW_DP = 48f        // each style is a full fingertip tall
        const val MENU_ITEM_DP = 44f       // each row of the globe's list, where there is room for it
        const val MENU_TEXT_DP = 16f
        const val MENU_TEXT_PAD_DP = 14f
        const val MENU_INSET_DP = 4f
        const val MENU_DIVIDER_DP = 9f
        const val MENU_CHECK_DP = 32f      // room at the end of a row for the tick
        const val MENU_MIN_WIDTH_DP = 190f
        const val MENU_MIN_ITEM_DP = 30f   // the list's rows at their shortest, still taller than the 16 dp text
        const val TWO_FINGER_START_DP = 10f  // two fingers going the same way this far may be a swipe
        const val TWO_FINGER_DP = 40f        // and this far, both of them, is one
        const val TWO_FINGER_BIAS = 1.5f     // clearly more across than up or down
        const val PILL_MS = 1500L
        const val PILL_FADE_MS = 250L
        const val PILL_PAD_DP = 12f
        const val FORGET_ICON_DP = 18f
        const val PRIVATE_SLOT_DP = 36f   // the eye alone at the start of the strip
        const val PRIVATE_TOOL_DP = 48f   // the buttons beside the badge close up, but never below a fingertip
        const val PRIVATE_SQUEEZE = 0.85f // the badge's words may be drawn this much smaller before a button goes
        const val PRIVATE_PAD_DP = 12f
        const val PRIVATE_GAP_DP = 8f
        const val DESTRUCTIVE_ON_DARK = 0xFFFF6961.toInt()
        const val DESTRUCTIVE_ON_LIGHT = 0xFFD70015.toInt()
        const val FORGET_SHADOW = 0x59000000
    }
}
