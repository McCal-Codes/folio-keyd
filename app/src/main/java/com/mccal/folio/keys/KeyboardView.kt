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
import android.view.HapticFeedbackConstants
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

        /** A word from the strip, tapped. */
        fun onSuggestion(word: String)
        fun onHide()
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

    var rules: FieldRules = FieldRules()
        set(value) { field = value; invalidate() }

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
    private var theme = Theme.of(context)
    private var placedKeys: List<Placement> = emptyList()
    private var tools: List<Placement> = emptyList()
    private var bottomInset = BottomRoom.GESTURE_BAND_DP * resources.displayMetrics.density
    private var sideInset = 0f

    private val panelPad get() = PANEL_PAD_DP * dp
    private val toolbarHeight get() = TOOLBAR_DP * dp

    /** One press per finger. A keyboard that tracks a single pointer drops letters the moment someone types fast. */
    private val presses = HashMap<Int, Press>()

    /**
     * [origin] is the key the finger landed on and owns the press's gestures; [placement] is the key it is over now.
     * They differ while someone slides, which at speed is most presses: a thumb travelling to the next letter lifts a
     * few pixels off the one it meant, and a keyboard that insists the lift land back inside the original rectangle
     * simply loses the letter.
     */
    private class Press(val origin: Placement, val downX: Float) {
        var placement: Placement = origin
        var swiping = false
        var cursorAnchor = downX

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
            listener?.onBackspaceRepeat()
            repeat.postDelayed(this, REPEAT_MS)
        }
    }

    private val keyNodes = KeyNodes()
    private val feedback = Feedback(context)

    init {
        isHapticFeedbackEnabled = true
        setWillNotDraw(false)
        ViewCompat.setAccessibilityDelegate(this, keyNodes)
    }

    private fun stopRepeat() {
        repeat.removeCallbacks(repeatBackspace)
        repeatingFor = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRepeat()   // a keyboard hidden mid-hold must not keep deleting
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
        theme = Theme.of(context)
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val height = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightSpec)
        } else {
            Geometry.height(
                rows.size, resources.configuration.screenHeightDp.toFloat(), dp, bottomInset,
                extra = toolbarHeight + panelPad,
            )
        }
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), height)
    }

    /**
     * How the keyboard sits in the window it was given. A phone keyboard stretched across an unfolded Fold gives keys
     * no thumb can reach, so a wide window splits (as Samsung's does), a middling one is capped and centred (as a
     * tablet keyboard is), and a phone fills the width.
     */
    private enum class Shape { FULL, CAPPED, SPLIT }

    private var shape = Shape.FULL

    private fun shapeFor(widthDp: Float) = when {
        widthDp >= SPLIT_AT_DP -> Shape.SPLIT
        widthDp >= CAP_AT_DP -> Shape.CAPPED
        else -> Shape.FULL
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        shape = shapeFor(width / dp)
        val edge = panelPad + sideInset + Geometry.SIDE_PAD_DP * dp
        val usable = width - 2 * edge
        val top = toolbarHeight + panelPad
        val bottom = bottomInset + panelPad

        placedKeys = when (shape) {
            Shape.FULL -> Geometry.place(
                rows, width, height, dp,
                sideInset = sideInset + panelPad, bottomInset = bottom, top = top,
            )
            Shape.CAPPED -> {
                // Centred and no wider than a large phone: the keys stay the size hands expect.
                val capped = min(usable, CAP_WIDTH_DP * dp)
                Geometry.place(
                    rows, width, height, dp, bottomInset = bottom, top = top,
                    startX = (width - capped) / 2, fillWidth = capped,
                )
            }
            Shape.SPLIT -> {
                val gutter = min(max(usable * 0.16f, 80 * dp), 220 * dp)
                val half = (usable - gutter) / 2
                val (leftRows, rightRows) = Layouts.split(rows)
                Geometry.place(
                    leftRows, width, height, dp, bottomInset = bottom, top = top,
                    startX = edge, fillWidth = half,
                ) + Geometry.place(
                    rightRows, width, height, dp, bottomInset = bottom, top = top,
                    startX = edge + half + gutter, fillWidth = half,
                )
            }
        }
        tools = placeToolbar()
        keyNodes.invalidateRoot()
    }

    /** The toolbar: hide the keyboard, and the three editing actions a field always supports. */
    private fun placeToolbar(): List<Placement> {
        if (width == 0) return emptyList()
        if (suggestions.isNotEmpty() && !rules.password) return placeSuggestions()
        val left = panelPad + sideInset + Geometry.SIDE_PAD_DP * dp
        val right = width - left
        val top = panelPad
        val bottom = top + toolbarHeight
        val items = listOf(
            Key("Hide", KeyKind.HIDE),
            Key("Emoji", KeyKind.EMOJI),
            Key("Select all", KeyKind.SELECT_ALL),
            Key("Copy", KeyKind.COPY),
            Key("Paste", KeyKind.PASTE),
        )
        // Even across the whole width: a row of icons bunched in one corner is the difference between a toolbar and
        // a few buttons someone left there.
        val slot = min((right - left) / items.size, TOOL_SLOT_DP * dp)
        val placed = ArrayList<Placement>(items.size)
        placed += Placement(items[0], Box(left, top, left + slot, bottom))
        placed += Placement(items[1], Box(left + slot, top, left + 2 * slot, bottom))
        val rest = items.drop(2)
        var x = right - rest.size * slot
        for (key in rest) {
            placed += Placement(key, Box(x, top, x + slot, bottom))
            x += slot
        }
        return placed
    }

    /**
     * The strip: what was typed, then the alternatives, in equal shares of the row.
     *
     * Equal shares rather than shares by word length, because a strip whose buttons move as you type is a strip
     * people mis-tap. The first is always the literal, so taking back a suggestion is always in the same place.
     */
    private fun placeSuggestions(): List<Placement> {
        val left = panelPad + sideInset + Geometry.SIDE_PAD_DP * dp
        val right = width - left
        val top = panelPad
        val bottom = top + toolbarHeight
        val slot = (right - left) / suggestions.size
        return suggestions.mapIndexed { index, word ->
            Placement(
                Key(word, KeyKind.SUGGESTION, output = word),
                Box(left + index * slot, top, left + (index + 1) * slot, bottom),
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        // The panel floats: the app shows through around it, the way a phone keyboard looks.
        scratch.set(panelPad, panelPad, width - panelPad, height - panelPad)
        fill.color = theme.board
        canvas.drawRoundRect(scratch, PANEL_RADIUS_DP * dp, PANEL_RADIUS_DP * dp, fill)
        drawHandle(canvas)

        drawToolbar(canvas)

        val radius = KEY_RADIUS_DP * dp
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
        drawPreview(canvas)
    }

    /** Whether a finger is on this key. A handful of identity checks, rather than hashing every key every frame. */
    private fun isHeld(placement: Placement): Boolean {
        if (presses.isEmpty()) return false
        for (press in presses.values) if (press.placement === placement) return true
        return false
    }

    /** The pill in the band below the keys: Samsung's grab handle, and where resizing will live. */
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
            KeyKind.ACTION -> if (key.label.length > 4) drawLabel(canvas, key.label, cx, cy, box, ink)
                else Icons.enter(canvas, cx, cy, icon * 1.1f, stroke)
            else -> drawLabel(canvas, key.label, cx, cy, box, ink)
        }

        // The corner hint is the long-press alternate; a password field keeps its own counsel.
        val cornerHint = key.hint
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
            if (placement.key.kind == KeyKind.SUGGESTION) {
                // The first is what was actually typed, and is drawn quieter than the alternatives so the eye goes
                // to what is being offered rather than to what it already knows it wrote.
                val literal = placement === tools.first()
                text.textSize = min(box.height * 0.40f, 17 * dp)
                sizedAt = -1f
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
            when (placement.key.kind) {
                KeyKind.HIDE -> Icons.chevronDown(canvas, cx, cy, size * 1.2f, stroke)
                KeyKind.EMOJI -> Icons.smiley(canvas, cx, cy, size, stroke, fill)
                KeyKind.SELECT_ALL -> Icons.selectAll(canvas, cx, cy, size, stroke, fill)
                KeyKind.COPY -> Icons.copy(canvas, cx, cy, size, stroke)
                KeyKind.PASTE -> Icons.paste(canvas, cx, cy, size, stroke, fill)
                else -> Unit
            }
        }
    }

    /** The character above the finger, so a thumb can see what it just hit. Never in a password field. */
    private fun drawPreview(canvas: Canvas) {
        if (rules.password) return
        for (press in presses.values) {
            val key = press.placement.key
            if (key.kind != KeyKind.CHAR) continue
            val box = press.placement.box
            val w = max(box.width * 1.25f, 34 * dp)
            val h = box.height * 1.05f
            val cx = (box.left + box.right) / 2
            val bottom = box.top - 6 * dp
            if (bottom - h < 0) continue      // the top row has nowhere to put it
            scratch.set(cx - w / 2, bottom - h, cx + w / 2, bottom)
            fill.color = theme.preview
            canvas.drawRoundRect(scratch, KEY_RADIUS_DP * dp, KEY_RADIUS_DP * dp, fill)
            text.textSize = h * 0.54f
            text.color = theme.label
            canvas.drawText(key.label, cx, (scratch.top + scratch.bottom) / 2 - (text.descent() + text.ascent()) / 2, text)
        }
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

    private fun keyAt(x: Float, y: Float): Placement? = nearest(placedKeys, x, y) ?: nearest(tools, x, y)

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
                for (press in presses.values) cancelHold(press)
                presses.clear()
                stopRepeat()
                invalidate()
            }
        }
        return true
    }

    private fun down(pointer: Int, x: Float, y: Float) {
        val placement = keyAt(x, y) ?: return
        val press = Press(placement, x)
        presses[pointer] = press
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        feedback.play(placement.key.kind)
        if (placement.key.kind == KeyKind.BACKSPACE) {
            repeatingFor = press
            repeat.postDelayed(repeatBackspace, FIRST_REPEAT_MS)
        }
        startHold(press)
        invalidate()
    }

    /**
     * Holding a key types the alternate printed in its corner.
     *
     * The corner of every top-row key says what holding it gives you, which until now it did not: a keyboard that
     * prints a promise on the keycap has to keep it. The delay is the system's own, so it follows whatever someone
     * has set for touch and hold in accessibility.
     */
    private fun startHold(press: Press) {
        val alternate = press.origin.key.hint ?: return
        if (press.origin.key.kind != KeyKind.CHAR) return
        val task = Runnable {
            press.handled = true
            listener?.onText(alternate)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
        press.hold = task
        repeat.postDelayed(task, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelHold(press: Press) {
        press.hold?.let { repeat.removeCallbacks(it) }
        press.hold = null
    }

    /** Swipe the space bar to move the cursor, and the backspace to take a word at a time. */
    private fun move(pointer: Int, x: Float, y: Float) {
        val press = presses[pointer] ?: return
        val dx = x - press.downX
        when (press.origin.key.kind) {
            // The space bar is wide and a fast thumb wanders across it. The swipe only begins after a deliberate
            // journey - a whole key's worth - and counts its characters from there, so a drifted space is a space.
            KeyKind.SPACE -> if (press.swiping || abs(dx) > CURSOR_START_DP * dp) {
                cancelHold(press)
                if (!press.swiping) {
                    press.swiping = true
                    // Anchored a step behind, so crossing the threshold moves one character straight away rather
                    // than asking for the journey all over again.
                    press.cursorAnchor = x - sign(dx) * CURSOR_STEP_DP * dp
                }
                val steps = ((x - press.cursorAnchor) / (CURSOR_STEP_DP * dp)).toInt()
                if (steps != 0) {
                    listener?.onCursor(steps)
                    press.cursorAnchor += steps * CURSOR_STEP_DP * dp
                }
            }
            KeyKind.BACKSPACE -> if (dx < -DELETE_WORD_DP * dp && !press.swiping) {
                cancelHold(press)
                press.swiping = true
                if (repeatingFor === press) stopRepeat()
                listener?.onDeleteWord()
            }
            // Sliding from one letter to the next is how a fast typist corrects mid-press, and how they leave a key
            // at all. Only letters follow the finger: sliding off shift and letting go is how you take it back.
            KeyKind.CHAR -> {
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
        if (repeatingFor === press) stopRepeat()
        invalidate()
        if (press.swiping || press.handled) return false
        // A letter commits wherever the finger lets go, because [move] has been keeping up with it. Everything else
        // has to be released on itself: sliding off shift, off a layer key or off the toolbar cancels it, which is
        // the one escape route someone has once they have pressed the wrong one.
        if (press.origin.key.kind != KeyKind.CHAR && keyAt(x, y) !== press.origin) return false
        dispatch(press.placement.key)
        return true
    }

    private fun dispatch(key: Key) {
        val l = listener ?: return
        when (key.kind) {
            KeyKind.CHAR, KeyKind.SPACE -> l.onText(key.output)
            KeyKind.BACKSPACE -> l.onBackspace()
            KeyKind.SHIFT -> l.onShift()
            KeyKind.LAYER -> l.onLayer(Layer.valueOf(key.output))
            KeyKind.ACTION -> l.onAction()
            KeyKind.GLOBE -> l.onSwitchKeyboard()
            KeyKind.HIDE -> l.onHide()
            KeyKind.SELECT_ALL -> l.onSelectAll()
            KeyKind.COPY -> l.onCopy()
            KeyKind.PASTE -> l.onPaste()
            KeyKind.EMOJI -> l.onEmojiPanel()
            KeyKind.SUGGESTION -> l.onSuggestion(key.output)
        }
    }

    // ---- screen readers ---------------------------------------------------------------------------------------

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        keyNodes.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private fun nodeAt(id: Int): Placement? = (placedKeys + tools).getOrNull(id)

    /**
     * The keys are drawn, not laid out, so a screen reader would find one blank rectangle. Each key is published as a
     * virtual view with the name it should be read by: "Backspace", not a glyph.
     */
    private inner class KeyNodes : ExploreByTouchHelper(this@KeyboardView) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val all = placedKeys + tools
            val index = all.indexOfFirst { it.box.contains(x, y) }
            return if (index < 0) HOST_ID else index
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            for (index in (placedKeys + tools).indices) ids.add(index)
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val placement = nodeAt(id)
            if (placement == null) {
                node.contentDescription = ""
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            node.contentDescription = Spoken.name(placement.key, shift)
            node.className = "android.widget.Button"
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
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
        const val CURSOR_START_DP = 30f   // travel before a drifting thumb counts as a swipe at all
        const val DELETE_WORD_DP = 24f
        const val HIT_SLOP_DP = 14f       // how far outside a key still belongs to it
        const val HYSTERESIS_DP = 12f     // how far clear of a key a finger must be to have left it
        const val HANDLE_W_DP = 44f
        const val HANDLE_H_DP = 4f
        const val PANEL_PAD_DP = 6f      // the gap around the panel, so it floats rather than fills
        const val PANEL_RADIUS_DP = 22f
        const val KEY_RADIUS_DP = 14f
        const val TOOLBAR_DP = 42f
        const val TOOL_SLOT_DP = 56f
        const val CAP_AT_DP = 480f      // wider than a large phone: stop stretching, start centring
        const val CAP_WIDTH_DP = 460f
        const val SPLIT_AT_DP = 600f    // an unfolded Fold or a tablet: split, the way Samsung does
    }
}
