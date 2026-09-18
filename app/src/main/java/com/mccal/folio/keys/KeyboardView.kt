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
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws the keys and turns touches into presses. Everything it knows about the text being edited arrives as
 * [FieldRules]; everything it does leaves through [listener]; where the keys go is [Geometry]'s business, so that part
 * can be tested without a phone.
 *
 * Key labels are sized from the key, the way AOSP does it, rather than from the system font scale: at 200% text the
 * alternative is a keyboard that tears itself apart. The settings screens scale; the keys don't.
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
        set(value) { field = value; invalidate() }

    var rows: List<Row> = emptyList()
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var shift: Shift = Shift.OFF
        set(value) { field = value; invalidate() }

    private val dp = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val scratch = RectF()        // reused: a keyboard shouldn't allocate while it draws
    private val heldKeys = HashSet<Placement>()   // likewise: filled each frame, never rebuilt

    private var theme = Theme.of(context)
    private var placedKeys: List<Placement> = emptyList()
    private var bottomInset = 0f
    private var sideInset = 0f

    /** One press per finger. A keyboard that tracks a single pointer drops letters the moment someone types fast. */
    private val presses = HashMap<Int, Press>()

    private class Press(val placement: Placement, val downX: Float) {
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

    private val keyNodes = KeyNodes()

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
        presses.clear()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        // Above the gesture bar, and clear of a cutout when the phone is on its side. Not systemGestures(): its left
        // and right are the back-swipe strips, and padding by those would pull the keys in for no reason.
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

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // The window usually lets the keyboard say how tall it is; when it insists, the keyboard does as it's told.
        val height = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightSpec)
        } else {
            Geometry.height(rows.size, resources.configuration.screenHeightDp.toFloat(), dp, bottomInset)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        placedKeys = Geometry.place(rows, width, height, dp, sideInset, bottomInset)
        keyNodes.invalidateRoot()   // the keys moved, so what a screen reader holds about them is stale
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.board)
        val radius = 8 * dp
        heldKeys.clear()
        if (presses.isNotEmpty()) for (press in presses.values) heldKeys.add(press.placement)
        for (placement in placedKeys) {
            val box = placement.box
            val key = placement.key
            val special = key.kind != KeyKind.CHAR && key.kind != KeyKind.SPACE
            fill.color = when {
                key.kind == KeyKind.ACTION -> theme.accent
                special -> theme.altKey
                else -> theme.key
            }
            if (placement in heldKeys) fill.color = blend(fill.color, theme.pressTint)
            scratch.set(box.left, box.top, box.right, box.bottom)
            canvas.drawRoundRect(scratch, radius, radius, fill)

            text.textSize = if (key.kind == KeyKind.CHAR) box.height * 0.46f else
                max(11 * dp, minOf(box.height * 0.30f, 15 * dp))
            text.color = if (key.kind == KeyKind.ACTION) theme.onAccent else theme.label
            val baseline = (box.top + box.bottom) / 2 - (text.descent() + text.ascent()) / 2
            canvas.drawText(label(key), (box.left + box.right) / 2, baseline, text)

            // The corner hint is the long-press alternate; a password field keeps its own counsel.
            val cornerHint = key.hint
            if (cornerHint != null && !rules.password) {
                hint.textSize = 11 * dp
                hint.color = theme.hint
                canvas.drawText(cornerHint, box.right - 5 * dp, box.top + 13 * dp, hint)
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

    private fun keyAt(x: Float, y: Float): Placement? = placedKeys.firstOrNull { it.box.contains(x, y) }

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
                for (index in 0 until event.pointerCount) move(event.getPointerId(index), event.getX(index))
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
        val placement = keyAt(x, y) ?: return
        val press = Press(placement, x)
        presses[pointer] = press
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (placement.key.kind == KeyKind.BACKSPACE) {
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
            press.placement.key.kind == KeyKind.SPACE && abs(dx) > CURSOR_STEP_DP * dp -> {
                press.swiping = true
                val steps = ((x - press.cursorAnchor) / (CURSOR_STEP_DP * dp)).toInt()
                if (steps != 0) {
                    listener?.onCursor(steps)
                    press.cursorAnchor += steps * CURSOR_STEP_DP * dp
                }
            }
            press.placement.key.kind == KeyKind.BACKSPACE && dx < -24 * dp && !press.swiping -> {
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
        if (keyAt(x, y) !== press.placement) return false    // slid off the key: no press
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
        }
    }

    // ---- screen readers ---------------------------------------------------------------------------------------

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        keyNodes.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /**
     * The keys are drawn, not laid out, so a screen reader would find one blank rectangle. Each key is published as a
     * virtual view with the name it should be read by: "Backspace", not "⌫".
     */
    private inner class KeyNodes : ExploreByTouchHelper(this@KeyboardView) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val index = placedKeys.indexOfFirst { it.box.contains(x, y) }
            return if (index < 0) HOST_ID else index
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            for (index in placedKeys.indices) ids.add(index)
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val placement = placedKeys.getOrNull(id)
            if (placement == null) {
                // A stale id can arrive while the rows are being rebuilt. The helper rejects empty bounds, so this
                // node is given somewhere to be rather than taking the screen reader down with it.
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
            val placement = placedKeys.getOrNull(id) ?: return false
            dispatch(placement.key)
            sendEventForVirtualView(id, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    private companion object {
        const val FIRST_REPEAT_MS = 400L
        const val REPEAT_MS = 55L
        const val CURSOR_STEP_DP = 12f   // travel per character the cursor moves
    }
}
