package com.mccal.folio.keys

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the keys and turns touches into presses. Everything it knows about the text being edited arrives as
 * [FieldRules]; everything it does leaves through [listener].
 *
 * Sizes follow what Android keyboards actually use rather than the system font scale: keys are about 46 dp tall, 5 dp
 * apart across and 9 dp down, and the whole keyboard stays under about half the window. Key labels are sized from the
 * key, the way AOSP does it, so a 200% font setting doesn't tear the keyboard apart - the settings screens scale, the
 * keys don't.
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onBackspace()
        fun onDeleteWord()
        fun onShift()
        fun onLayer(layer: Layer)
        fun onAction()
        fun onSwitchKeyboard()
        fun onCursor(steps: Int)
    }

    var listener: Listener? = null

    var rules: FieldRules = FieldRules()
        set(value) { field = value; requestLayout(); invalidate() }

    var rows: List<Row> = emptyList()
        set(value) {
            field = value
            placed = false
            requestLayout()
            invalidate()
            keyNodes.invalidateRoot()   // the keys changed, so the names TalkBack holds are stale
        }

    /** Shift's three states: off, on for one letter, or locked until it's tapped again. */
    var shift: Shift = Shift.OFF
        set(value) { field = value; invalidate() }

    enum class Shift { OFF, ONCE, LOCKED }

    private val dp = resources.displayMetrics.density
    private val gapX = 5 * dp
    private val gapY = 9 * dp
    private val padSide = 3 * dp

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }

    private var theme = Theme.of(context)
    private val placedKeys = ArrayList<Placed>()
    private var placed = false
    private var bottomInset = 0f
    private var sideInset = 0f

    private class Placed(val key: Key, val bounds: RectF)

    /** One press per finger. A keyboard that tracks a single pointer drops letters the moment someone types fast. */
    private val presses = HashMap<Int, Press>()

    private class Press(val key: Placed, val downX: Float) {
        var swiping = false
        var cursorAnchor = downX
        var repeated = false
    }

    private val repeat = Handler(Looper.getMainLooper())
    private var repeatingFor: Press? = null
    private val repeatBackspace = object : Runnable {
        override fun run() {
            val press = repeatingFor ?: return
            press.repeated = true
            listener?.onBackspace()
            repeat.postDelayed(this, REPEAT_MS)
        }
    }

    private fun stopRepeat() {
        repeat.removeCallbacks(repeatBackspace)
        repeatingFor = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRepeat()   // a keyboard hidden mid-hold must not keep deleting
        presses.clear()
    }

    private val keyNodes = KeyNodes()

    init {
        isHapticFeedbackEnabled = true
        setWillNotDraw(false)
        ViewCompat.setAccessibilityDelegate(this, keyNodes)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        keyNodes.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /**
     * The keys are drawn, not laid out, so TalkBack would find one blank rectangle. Each key is published as a virtual
     * view with the name it should be read by: "Backspace", not "⌫".
     */
    private inner class KeyNodes : ExploreByTouchHelper(this@KeyboardView) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val index = placedKeys.indexOfFirst { it.bounds.contains(x, y) }
            return if (index < 0) HOST_ID else index
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            for (index in placedKeys.indices) ids.add(index)
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val placed = placedKeys.getOrNull(id) ?: run {
                // A stale id can arrive while the rows are being rebuilt. The helper rejects empty bounds, so this
                // node is given somewhere to be rather than crashing the screen reader.
                node.contentDescription = ""
                node.setBoundsInParent(android.graphics.Rect(0, 0, 1, 1))
                return
            }
            node.contentDescription = spokenName(placed.key)
            node.className = "android.widget.Button"
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            val bounds = android.graphics.Rect()
            placed.bounds.roundOut(bounds)
            node.setBoundsInParent(bounds)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: android.os.Bundle?): Boolean {
            if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
            val placed = placedKeys.getOrNull(id) ?: return false
            dispatch(placed.key)
            sendEventForVirtualView(id, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    /** What a key should be read as. Two keys never read the same, which is a rule the mockup checks too. */
    private fun spokenName(key: Key): String = when (key.kind) {
        KeyKind.BACKSPACE -> "Backspace, swipe left to delete a word"
        KeyKind.SHIFT -> when (shift) {
            Shift.OFF -> "Shift"
            Shift.ONCE -> "Shift, on for one letter"
            Shift.LOCKED -> "Caps lock"
        }
        KeyKind.SPACE -> "Space, swipe to move the cursor"
        KeyKind.GLOBE -> "Switch keyboard"
        KeyKind.LAYER -> when (key.output) {
            Layer.LETTERS.name -> "Letters"
            Layer.SYMBOLS.name -> "More symbols"
            else -> "Numbers and symbols"
        }
        KeyKind.ACTION -> key.label
        KeyKind.CHAR -> key.label
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        // Sit above the gesture bar, and clear of a cutout when the phone is on its side.
        bottomInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom.toFloat()
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

    /** Android's guidance: a keyboard that isn't fullscreen shouldn't take much more than half the window. */
    private fun desiredHeight(): Int {
        // The configuration's height is the window's, not the display's, so split screen gets a keyboard that fits.
        val windowHeightDp = resources.configuration.screenHeightDp.toFloat()
        val windowHeight = windowHeightDp * dp
        val tall = windowHeightDp >= 560
        val cap = min(windowHeight * (if (tall) 0.46f else 0.55f), 360 * dp)
        val rowCount = max(rows.size, 1)
        val rowHeight = rowHeight(cap, rowCount)
        return (rowCount * rowHeight + (rowCount - 1) * gapY + 2 * gapY + bottomInset).roundToInt()
    }

    private fun rowHeight(cap: Float, rowCount: Int): Float {
        val room = cap - 2 * gapY - (rowCount - 1) * gapY - bottomInset
        return max(25 * dp, min(52 * dp, room / rowCount))
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // The window usually lets the keyboard say how tall it is; when it insists, the keyboard does as it's told.
        val height = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightSpec)
        } else {
            desiredHeight()
        }
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), height)
        placed = false
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        place()
    }

    private fun place() {
        placedKeys.clear()
        if (rows.isEmpty() || width == 0) return
        val left = padSide + sideInset
        val usable = width - 2 * left
        val rowCount = rows.size
        val rowH = (height - bottomInset - 2 * gapY - (rowCount - 1) * gapY) / rowCount
        var y = gapY
        for (row in rows) {
            val weights = row.sumOf { it.weight.toDouble() }.toFloat()
            val unit = (usable - gapX * (row.size - 1)) / weights
            var x = left
            for (key in row) {
                val w = unit * key.weight
                placedKeys += Placed(key, RectF(x, y, x + w, y + rowH))
                x += w + gapX
            }
            y += rowH + gapY
        }
        placed = true
        keyNodes.invalidateRoot()   // the keys moved, so what TalkBack holds about them is stale
    }

    override fun onDraw(canvas: Canvas) {
        if (!placed) place()
        canvas.drawColor(theme.board)
        val radius = 8 * dp
        for (p in placedKeys) {
            val special = p.key.kind != KeyKind.CHAR && p.key.kind != KeyKind.SPACE
            fill.color = when {
                p.key.kind == KeyKind.ACTION -> theme.accent
                special -> theme.altKey
                else -> theme.key
            }
            if (presses.values.any { it.key === p }) fill.color = blend(fill.color, theme.pressTint)
            canvas.drawRoundRect(p.bounds, radius, radius, fill)

            val label = label(p.key)
            val size = if (p.key.kind == KeyKind.CHAR) p.bounds.height() * 0.46f else
                max(11 * dp, min(p.bounds.height() * 0.30f, 15 * dp))
            text.textSize = size
            text.color = if (p.key.kind == KeyKind.ACTION) theme.onAccent else theme.label
            val baseline = p.bounds.centerY() - (text.descent() + text.ascent()) / 2
            canvas.drawText(label, p.bounds.centerX(), baseline, text)

            // The corner hint is the long-press alternate; a password field keeps its own counsel.
            val cornerHint = p.key.hint
            if (cornerHint != null && !rules.password) {
                hint.textSize = 11 * dp
                hint.color = theme.hint
                canvas.drawText(cornerHint, p.bounds.right - 5 * dp, p.bounds.top + 13 * dp, hint)
            }
        }
    }

    private fun label(key: Key): String = when (key.kind) {
        KeyKind.SHIFT -> if (shift == Shift.LOCKED) "⇪" else "⇧"
        else -> key.label
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

    private fun keyAt(x: Float, y: Float): Placed? = placedKeys.firstOrNull { it.bounds.contains(x, y) }

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
                    move(event.getPointerId(index), event.getX(index))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                if (up(event.getPointerId(index), event.getX(index), event.getY(index))) performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                presses.clear()
                stopRepeat()
                invalidate()
            }
        }
        return true
    }

    private fun down(pointer: Int, x: Float, y: Float) {
        val key = keyAt(x, y) ?: return
        val press = Press(key, x)
        presses[pointer] = press
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (key.key.kind == KeyKind.BACKSPACE) {
            repeatingFor = press
            repeat.postDelayed(repeatBackspace, FIRST_REPEAT_MS)
        }
        invalidate()
    }

    /** Swipe the space bar to move the cursor, and the backspace to take a word at a time. */
    private fun move(pointer: Int, x: Float) {
        val press = presses[pointer] ?: return
        val dx = x - press.downX
        when {
            press.key.key.kind == KeyKind.SPACE && abs(dx) > CURSOR_STEP * dp -> {
                press.swiping = true
                val steps = ((x - press.cursorAnchor) / (CURSOR_STEP * dp)).toInt()
                if (steps != 0) {
                    listener?.onCursor(steps)
                    press.cursorAnchor += steps * CURSOR_STEP * dp
                }
            }
            press.key.key.kind == KeyKind.BACKSPACE && dx < -24 * dp && !press.swiping -> {
                press.swiping = true
                if (repeatingFor === press) stopRepeat()
                listener?.onDeleteWord()
            }
        }
    }

    /** True when the lift was a press on a key, which is what [performClick] is for. */
    private fun up(pointer: Int, x: Float, y: Float): Boolean {
        val press = presses.remove(pointer) ?: return false
        if (repeatingFor === press) stopRepeat()
        invalidate()
        if (press.swiping || press.repeated) return false    // the swipe or the held repeat already did the work
        if (keyAt(x, y) !== press.key) return false          // slid off the key: no press
        dispatch(press.key.key)
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
        }
    }

    private companion object {
        const val FIRST_REPEAT_MS = 400L
        const val REPEAT_MS = 55L
        const val CURSOR_STEP = 12f   // dp of travel per character the cursor moves
    }
}
