package com.mccal.folio.keys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Arrow keys, for putting the cursor exactly where it has to go.
 *
 * A sibling of [EmojiPanel] and [ClipboardPanel], built the same way: it takes the keyboard's height so the app above
 * never jumps, and it floats on the same rounded board. Swiping the space bar already moves the cursor, but only
 * sideways and only a character at a time; this is for the edits that need more than that - a line up, a word back,
 * or a selection made without a long press that the app may or may not honor.
 *
 * Select is a latch rather than a key you hold: tap it and every move after that extends the selection, which is the
 * only way to select with one thumb. The service sends each move as a real key event, so the app does the moving and
 * knows where lines and words end better than a keyboard could.
 */
internal class CursorPad(context: Context) : View(context) {

    enum class Move { LEFT, RIGHT, UP, DOWN, WORD_LEFT, WORD_RIGHT, LINE_START, LINE_END }

    interface Listener {
        /** Move the cursor; [selecting] means extend the selection rather than drop it. */
        fun onMove(move: Move, selecting: Boolean)
        fun onSelectAll()
        fun onCut()
        fun onBackspace()

        /** Back to the letters. */
        fun onLetters()
    }

    var listener: Listener? = null

    /**
     * The Select latch. The service sets it back to false when a field opens, so a selection half made in one app
     * never carries into the next.
     */
    var selecting: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            cells.invalidateVirtualView(SELECT_INDEX)
            invalidate()
        }

    private val dp = context.resources.displayMetrics.density
    private var theme = Theme.of(context)

    /** Shared with the keys, so the panels never disagree about whether it is night. */
    var appearance: Appearance = Appearance.SYSTEM
        set(value) {
            field = value
            invalidate()
        }

    var highContrast: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Keyd's own Vibration choice. Off means off here too, not only on the letters. */
    var vibration: Vibration = Vibration.MEDIUM

    /** A black board when dark, as on the letters. */
    var pureBlack: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** The key style the letters use, so opening the pad doesn't change the keyboard's look. */
    var keyStyle: KeyStyle = KeyStyle.FOLIO
        set(value) {
            field = value
            invalidate()
        }

    private var bottomInset = BottomRoom.GESTURE_BAND_DP * dp
    private var sideInset = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    /** Where each key sits, in the same order as [KEYS]. Worked out once per layout, not per frame. */
    private val boxes = List(KEYS.size) { RectF() }
    private var showLabels = true

    private var pressed = -1

    /** The held key has already repeated, so lifting the finger must not add one more. */
    private var repeated = false
    private val repeat = Handler(Looper.getMainLooper())
    private val repeatHeld = object : Runnable {
        override fun run() {
            val key = KEYS.getOrNull(pressed) ?: return
            repeated = true
            fire(key)
            repeat.postDelayed(this, REPEAT_MS)
        }
    }

    private val cells = CellNodes()

    init {
        isHapticFeedbackEnabled = true
        setWillNotDraw(false)
        ViewCompat.setAccessibilityDelegate(this, cells)
    }

    private val panelPad get() = PANEL_PAD_DP * dp
    private val tabHeight get() = TAB_DP * dp

    /** Shown: always start unlatched, since whatever was being selected last time is long gone. */
    fun opened() {
        stopRepeat()
        pressed = -1
        selecting = false
        invalidate()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
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

    /**
     * The height the letters would have taken, so switching panels never moves the app above.
     *
     * The other panels spend part of it on a tab row; this one has no tabs, so its four rows share all of it and come
     * out a little taller than the letters, which suits keys that are pressed and held rather than typed.
     */
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val height = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightSpec)
        } else {
            Geometry.height(
                ROWS_LIKE_LETTERS, resources.configuration.screenHeightDp.toFloat(), dp, bottomInset,
                extra = tabHeight + panelPad,
            )
        }
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        placeKeys()
    }

    private fun placeKeys() {
        val gapX = Geometry.GAP_X_DP * dp
        val gapY = Geometry.GAP_Y_DP * dp
        val edge = panelPad + sideInset + Geometry.SIDE_PAD_DP * dp + gapX
        // Twelve keys stretched across an unfolded Fold are a reach from one to the next and look like a toy, so
        // past a large phone's width they stop growing and sit in the middle, the way the letters do.
        val room = width - 2 * edge
        val span = min(room, CAP_WIDTH_DP * dp)
        val left = edge + (room - span) / 2
        val top = panelPad + gapY
        val bottom = height - bottomInset - panelPad
        val rowHeight = max(0f, (bottom - top - (ROWS.size - 1) * gapY) / ROWS.size)
        var index = 0
        for ((row, count) in ROWS.withIndex()) {
            val keyWidth = (span - (count - 1) * gapX) / count
            val y = top + row * (rowHeight + gapY)
            for (column in 0 until count) {
                val x = left + column * (keyWidth + gapX)
                boxes[index++].set(x, y, x + keyWidth, y + rowHeight)
            }
        }
        // Labels go when any one of them would not fit, not key by key: a pad where some keys are captioned and
        // others are not reads as if the uncaptioned ones were something different.
        label.textSize = labelSize(rowHeight)
        showLabels = rowHeight >= LABELED_ROW_DP * dp && KEYS.indices.all { i ->
            val caption = KEYS[i].caption ?: return@all true
            label.measureText(context.getString(caption)) <= boxes[i].width() - 2 * LABEL_PAD_DP * dp
        }
    }

    private fun labelSize(rowHeight: Float) = (rowHeight * 0.19f).coerceIn(10 * dp, 12 * dp)

    // ---- drawing --------------------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        theme = Theme.of(context, appearance, highContrast, keyStyle, pureBlack)
        // The panel floats: the app shows through around it, the way the letters do.
        rect.set(panelPad, panelPad, width - panelPad, height - panelPad)
        fill.color = theme.board
        canvas.drawRoundRect(rect, PANEL_RADIUS_DP * dp, PANEL_RADIUS_DP * dp, fill)

        val radius = KEY_RADIUS_DP * dp
        for ((index, key) in KEYS.withIndex()) {
            val box = boxes[index]
            val on = key.kind == Kind.SELECT && selecting
            fill.color = when {
                on -> theme.accent
                key.alt -> theme.altKey
                else -> theme.key
            }
            if (index == pressed) fill.color = blend(fill.color, theme.pressTint)
            canvas.drawRoundRect(box, radius, radius, fill)
            drawFace(canvas, key, box, if (on) theme.onAccent else theme.label)
        }
    }

    private fun drawFace(canvas: Canvas, key: PadKey, box: RectF, ink: Int) {
        val cx = box.centerX()
        val caption = key.caption?.takeIf { showLabels }
        label.textSize = labelSize(box.height())
        val labelGap = 4 * dp
        val icon = min(box.height() * if (caption != null) 0.34f else 0.46f, box.width() * 0.4f)
        // The icon and its caption are centered as one block, so a captioned key and a bare one line up.
        val block = if (caption != null) icon + labelGap + label.textSize else icon
        val cy = box.centerY() - block / 2 + icon / 2

        stroke.color = ink
        fill.color = ink
        stroke.strokeWidth = max(1.6f * dp, icon * 0.085f)
        when (key.kind) {
            Kind.MOVE -> drawMove(canvas, key.move!!, cx, cy, icon)
            Kind.SELECT -> cursor(canvas, cx, cy, icon)
            Kind.SELECT_ALL -> Icons.selectAll(canvas, cx, cy, icon, stroke, fill)
            Kind.CUT -> scissors(canvas, cx, cy, icon)
            Kind.BACKSPACE -> Icons.backspace(canvas, cx, cy, icon, stroke, stroke)
            Kind.LETTERS -> {
                // The same word the other panels use to go back, set as text because it is one.
                val size = label.textSize
                label.textSize = icon * 0.62f
                label.color = ink
                canvas.drawText(
                    context.getString(R.string.emoji_letters), cx, cy - (label.descent() + label.ascent()) / 2, label,
                )
                label.textSize = size
            }
        }
        if (caption != null) {
            label.color = ink
            canvas.drawText(context.getString(caption), cx, cy + icon / 2 + labelGap - label.ascent(), label)
        }
    }

    private fun drawMove(canvas: Canvas, move: Move, cx: Float, cy: Float, size: Float) {
        // Each shape is drawn pointing right and turned, so up, down and the two lefts are the same strokes.
        val turn = when (move) {
            Move.RIGHT, Move.WORD_RIGHT, Move.LINE_END -> 0f
            Move.DOWN -> 90f
            Move.LEFT, Move.WORD_LEFT, Move.LINE_START -> 180f
            Move.UP -> 270f
        }
        canvas.save()
        canvas.rotate(turn, cx, cy)
        when (move) {
            Move.WORD_LEFT, Move.WORD_RIGHT -> chevrons(canvas, cx, cy, size)
            Move.LINE_START, Move.LINE_END -> toBar(canvas, cx, cy, size)
            else -> arrow(canvas, cx, cy, size)
        }
        canvas.restore()
    }

    /** A plain arrow pointing right: a character, or a line up or down once turned. */
    private fun arrow(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val half = size * 0.42f
        val head = size * 0.26f
        canvas.drawLine(cx - half, cy, cx + half, cy, stroke)
        canvas.drawLine(cx + half, cy, cx + half - head, cy - head, stroke)
        canvas.drawLine(cx + half, cy, cx + half - head, cy + head, stroke)
    }

    /** Two chevrons: further than one arrow goes, which is what a word is. */
    private fun chevrons(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val h = size * 0.3f
        val w = size * 0.22f
        for (x in floatArrayOf(cx - size * 0.16f, cx + size * 0.16f)) {
            canvas.drawLine(x - w / 2, cy - h, x + w / 2, cy, stroke)
            canvas.drawLine(x - w / 2, cy + h, x + w / 2, cy, stroke)
        }
    }

    /** An arrow running into a bar: as far as it goes, the end of the line. */
    private fun toBar(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val half = size * 0.42f
        val head = size * 0.22f
        val tip = cx + half * 0.55f
        canvas.drawLine(cx - half, cy, tip, cy, stroke)
        canvas.drawLine(tip, cy, tip - head, cy - head, stroke)
        canvas.drawLine(tip, cy, tip - head, cy + head, stroke)
        canvas.drawLine(cx + half, cy - size * 0.36f, cx + half, cy + size * 0.36f, stroke)
    }

    /** The text cursor, an I-beam: the thing Select drags along behind every move. */
    private fun cursor(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val half = size * 0.42f
        val serif = size * 0.16f
        canvas.drawLine(cx, cy - half, cx, cy + half, stroke)
        canvas.drawLine(cx - serif, cy - half, cx + serif, cy - half, stroke)
        canvas.drawLine(cx - serif, cy + half, cx + serif, cy + half, stroke)
    }

    /** Open scissors: two rings for the fingers and two blades that cross above them. */
    private fun scissors(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val ring = size * 0.14f
        val ringY = cy + size * 0.28f
        val apart = size * 0.2f
        canvas.drawCircle(cx - apart, ringY, ring, stroke)
        canvas.drawCircle(cx + apart, ringY, ring, stroke)
        val tipY = cy - size * 0.44f
        val tipX = size * 0.2f
        canvas.drawLine(cx - apart + ring * 0.6f, ringY - ring * 0.8f, cx + tipX, tipY, stroke)
        canvas.drawLine(cx + apart - ring * 0.6f, ringY - ring * 0.8f, cx - tipX, tipY, stroke)
    }

    /** The pressed look the letters have: the key's own color with the press tint over it. */
    private fun blend(color: Int, tint: Int): Int {
        val a = Color.alpha(tint) / 255f
        fun mix(c: Int, t: Int) = (c * (1 - a) + t * a).roundToInt().coerceIn(0, 255)
        return Color.rgb(
            mix(Color.red(color), Color.red(tint)),
            mix(Color.green(color), Color.green(tint)),
            mix(Color.blue(color), Color.blue(tint)),
        )
    }

    // ---- touch ----------------------------------------------------------------------------------------------------

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopRepeat()
                pressed = keyAt(event.x, event.y)
                repeated = false
                val key = KEYS.getOrNull(pressed)
                if (key != null) {
                    Haptics.feel(this, vibration)
                    if (key.repeats) repeat.postDelayed(repeatHeld, FIRST_REPEAT_MS)
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // Sliding off a key lets it go without firing, the way a button does; a thumb that wandered is
                // not a thumb that meant it.
                if (pressed >= 0 && !boxes[pressed].contains(event.x, event.y)) {
                    stopRepeat()
                    pressed = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val key = KEYS.getOrNull(pressed)
                stopRepeat()
                if (key != null && !repeated) fire(key)
                pressed = -1
                invalidate()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                stopRepeat()
                pressed = -1
                invalidate()
            }
        }
        return true
    }

    private fun stopRepeat() {
        repeat.removeCallbacks(repeatHeld)
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        pressed = -1
        super.onDetachedFromWindow()
    }

    private fun fire(key: PadKey) {
        when (key.kind) {
            Kind.MOVE -> listener?.onMove(key.move!!, selecting)
            Kind.SELECT -> selecting = !selecting
            Kind.SELECT_ALL -> listener?.onSelectAll()
            Kind.CUT -> {
                listener?.onCut()
                // What was selected has gone to the clipboard, so there is nothing left to extend.
                selecting = false
            }
            Kind.BACKSPACE -> listener?.onBackspace()
            Kind.LETTERS -> listener?.onLetters()
        }
    }

    private fun keyAt(x: Float, y: Float): Int = boxes.indexOfFirst { it.contains(x, y) }

    // ---- screen readers ---------------------------------------------------------------------------------------

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        cells.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /** One virtual view per key, with the key's index as its id, so the ids never move. */
    private inner class CellNodes : ExploreByTouchHelper(this@CursorPad) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val index = keyAt(x, y)
            return if (index < 0) HOST_ID else index
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            for (id in KEYS.indices) ids.add(id)
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val key = KEYS.getOrNull(id)
            if (key == null) {
                node.contentDescription = ""
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            node.contentDescription = context.getString(key.spoken)
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            val box = boxes[id]
            node.setBoundsInParent(
                Rect(box.left.toInt(), box.top.toInt(), box.right.roundToInt(), box.bottom.roundToInt()),
            )
            if (key.kind == Kind.SELECT) {
                // A switch, so a screen reader says whether moves are selecting before someone makes one.
                node.className = "android.widget.ToggleButton"
                node.isCheckable = true
                node.isChecked = selecting
                // The compat node in this androidx version has no state description; the platform one has had it
                // since API 30, below the app's minimum.
                node.unwrap().stateDescription =
                    context.getString(if (selecting) R.string.cursor_pad_on else R.string.cursor_pad_off)
            } else {
                node.className = "android.widget.Button"
            }
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
            val key = KEYS.getOrNull(id) ?: return false
            fire(key)
            sendEventForVirtualView(id, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    /** What a test needs to ask: how many keys, where each one is, and whether the captions fit. */
    internal val keyCount: Int get() = KEYS.size
    internal fun keyBounds(index: Int): RectF = RectF(boxes[index])
    internal val labelsShown: Boolean get() = showLabels

    private enum class Kind { MOVE, SELECT, SELECT_ALL, CUT, BACKSPACE, LETTERS }

    /** One key: what it does, what is written under it (if anything) and what a screen reader calls it. */
    private class PadKey(
        val kind: Kind,
        val caption: Int?,
        val spoken: Int,
        val move: Move? = null,
        val alt: Boolean = false,
    ) {
        /** Moves and backspace are the keys you hold to go further; the rest mean it once. */
        val repeats: Boolean get() = kind == Kind.MOVE || kind == Kind.BACKSPACE
    }

    companion object {
        /**
         * Key events for a move: keycode plus meta state. Word jumps are Ctrl+arrow, line start/end are
         * MOVE_HOME/MOVE_END, selecting adds Shift.
         *
         * These are what a hardware keyboard sends, so every text field already knows them; a keyboard that moved
         * the cursor itself with setSelection would have to guess where a line wraps, and could not.
         */
        fun keyEvents(move: Move, selecting: Boolean): Pair<Int, Int> {
            val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            val (code, meta) = when (move) {
                Move.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT to 0
                Move.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT to 0
                Move.UP -> KeyEvent.KEYCODE_DPAD_UP to 0
                Move.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN to 0
                Move.WORD_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT to ctrl
                Move.WORD_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT to ctrl
                Move.LINE_START -> KeyEvent.KEYCODE_MOVE_HOME to 0
                Move.LINE_END -> KeyEvent.KEYCODE_MOVE_END to 0
            }
            val shift = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
            return code to (meta or shift)
        }

        private const val PANEL_PAD_DP = 6f
        private const val PANEL_RADIUS_DP = 22f
        private const val KEY_RADIUS_DP = 14f
        private const val TAB_DP = 42f
        private const val CAP_WIDTH_DP = 460f
        private const val LABELED_ROW_DP = 46f   // below this a caption squeezes the icon to nothing
        private const val LABEL_PAD_DP = 4f
        private const val FIRST_REPEAT_MS = 400L   // the same timing as backspace on the letters
        private const val REPEAT_MS = 55L

        /** The letters keyboard is four rows; matching it keeps the app above from jumping when you switch. */
        private const val ROWS_LIKE_LETTERS = 4

        private val ROWS = intArrayOf(3, 3, 3, 4)
        private const val SELECT_INDEX = 4

        private val KEYS = listOf(
            PadKey(Kind.MOVE, R.string.cursor_pad_line_start, R.string.cursor_pad_line_start_spoken, Move.LINE_START),
            PadKey(Kind.MOVE, R.string.cursor_pad_up, R.string.cursor_pad_up_spoken, Move.UP),
            PadKey(Kind.MOVE, R.string.cursor_pad_line_end, R.string.cursor_pad_line_end_spoken, Move.LINE_END),
            PadKey(Kind.MOVE, R.string.cursor_pad_left, R.string.cursor_pad_left_spoken, Move.LEFT),
            PadKey(Kind.SELECT, R.string.cursor_pad_select, R.string.cursor_pad_select_spoken, alt = true),
            PadKey(Kind.MOVE, R.string.cursor_pad_right, R.string.cursor_pad_right_spoken, Move.RIGHT),
            PadKey(Kind.MOVE, R.string.cursor_pad_word, R.string.cursor_pad_word_left_spoken, Move.WORD_LEFT),
            PadKey(Kind.MOVE, R.string.cursor_pad_down, R.string.cursor_pad_down_spoken, Move.DOWN),
            PadKey(Kind.MOVE, R.string.cursor_pad_word, R.string.cursor_pad_word_right_spoken, Move.WORD_RIGHT),
            // ABC is its own caption, and backspace is known by its shape; a word under either only repeats it.
            PadKey(Kind.LETTERS, null, R.string.cursor_pad_letters_spoken, alt = true),
            PadKey(Kind.SELECT_ALL, R.string.cursor_pad_select_all, R.string.cursor_pad_select_all_spoken, alt = true),
            PadKey(Kind.CUT, R.string.cursor_pad_cut, R.string.cursor_pad_cut_spoken, alt = true),
            PadKey(Kind.BACKSPACE, null, R.string.cursor_pad_backspace_spoken, alt = true),
        )
    }
}
