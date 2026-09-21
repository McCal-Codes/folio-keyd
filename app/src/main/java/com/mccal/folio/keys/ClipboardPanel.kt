package com.mccal.folio.keys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What has been copied lately, to put back without leaving the keyboard.
 *
 * A sibling of [EmojiPanel] and built the same way, for the same reason: it takes the keyboard's height so the app
 * above never jumps, it scrolls, and it has a tab row along the bottom. Where the emoji are a grid of glyphs, these
 * are rows of text — a clip is worth reading before you paste it, which is the whole point of a history.
 *
 * Each row does three things, all of them visible: tap the text to put it back, tap the pin to keep it past the hour
 * [Clipboard] gives an unpinned clip, tap the cross to forget it now.
 */
internal class ClipboardPanel(context: Context) : View(context) {

    interface Listener {
        /** Put this clip into the field. */
        fun onClip(text: String)
        fun onPinClip(text: String, pinned: Boolean)
        fun onForgetClip(text: String)
        fun onClearClips()
        fun onBackspace()

        /** Back to the letters. */
        fun onLetters()
    }

    /** "Clear unpinned" when something is pinned, because that is all it clears; "Clear all" otherwise. */
    internal val clearLabel: Int
        get() = if (clips.any { it.pinned }) R.string.clipboard_clear_unpinned else R.string.clipboard_clear

    var listener: Listener? = null

    /** Owned by the service, which is what reads and writes the stored list. Pinned first, then newest. */
    var clips: List<Clipboard.Clip> = emptyList()
        set(value) {
            field = value.sortedWith(compareByDescending<Clipboard.Clip> { it.pinned }.thenByDescending { it.at })
            measureScroll()
            requestLayout()
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

    private var bottomInset = BottomRoom.GESTURE_BAND_DP * dp
    private var sideInset = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    private var scroll = 0f
    private var maxScroll = 0f
    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downY = 0f
    private var downScroll = 0f
    private var dragging = false
    private var inList = false
    private var pressed = -1
    private var velocity: VelocityTracker? = null

    private var listTop = 0f
    private var listBottom = 0f

    private val cells = CellNodes()

    init {
        isHapticFeedbackEnabled = true
        setWillNotDraw(false)
        ViewCompat.setAccessibilityDelegate(this, cells)
    }

    private val panelPad get() = PANEL_PAD_DP * dp
    private val tabHeight get() = TAB_DP * dp
    private val rowHeight get() = ROW_DP * dp
    private val buttonWidth get() = BUTTON_DP * dp

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

    /** The height the letters would have taken, so switching panels never moves the app above. */
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
        listTop = panelPad + LIST_PAD_DP * dp
        listBottom = height - bottomInset - panelPad - tabHeight
        measureScroll()
    }

    /** How far the list can go, and keeping where it is inside that. Changing the clips changes both. */
    private fun measureScroll() {
        maxScroll = max(0f, clips.size * rowHeight - visibleHeight())
        scroll = scroll.coerceIn(0f, maxScroll)
    }

    private fun visibleHeight() = max(0f, listBottom - listTop)

    // ---- drawing --------------------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        theme = Theme.of(context, appearance, highContrast)
        rect.set(panelPad, panelPad, width - panelPad, height - panelPad)
        fill.color = theme.board
        canvas.drawRoundRect(rect, PANEL_RADIUS_DP * dp, PANEL_RADIUS_DP * dp, fill)
        drawList(canvas)
        drawTabs(canvas)
    }

    private fun drawList(canvas: Canvas) {
        if (clips.isEmpty()) {
            label.textSize = 14 * dp
            label.color = theme.hint
            canvas.drawText(
                context.getString(R.string.clipboard_empty), width / 2f, (listTop + listBottom) / 2, label,
            )
            return
        }
        canvas.save()
        canvas.clipRect(0f, listTop, width.toFloat(), listBottom)
        val edge = panelPad + sideInset + Geometry.SIDE_PAD_DP * dp
        text.textSize = 15 * dp
        stroke.strokeWidth = 1.6f * dp
        // Only the rows on screen: twenty clips is not many, but the arithmetic is the same either way.
        val first = max(0, (scroll / rowHeight).toInt())
        val last = min(clips.size, first + (visibleHeight() / rowHeight).toInt() + 2)
        for (index in first until last) {
            val clip = clips[index]
            val top = listTop + index * rowHeight - scroll
            if (index == pressed) {
                rect.set(edge, top + 2 * dp, width - edge, top + rowHeight - 2 * dp)
                fill.color = theme.pressTint
                canvas.drawRoundRect(rect, 12 * dp, 12 * dp, fill)
            }
            // The clip itself, on one line, cut with an ellipsis rather than wrapped: a row that changes height
            // with its contents makes a list you cannot aim at.
            text.color = theme.label
            val room = width - edge * 2 - buttonWidth * 2 - 12 * dp
            val shown = TextUtils.ellipsize(clip.text.replace('\n', ' '), TextPaintOf(text), room, TextUtils.TruncateAt.END)
            canvas.drawText(shown, 0, shown.length, edge + 8 * dp, top + rowHeight / 2 + text.textSize * 0.36f, text)

            val pinCx = width - edge - buttonWidth * 1.5f
            val closeCx = width - edge - buttonWidth * 0.5f
            val cy = top + rowHeight / 2
            stroke.color = if (clip.pinned) theme.accent else theme.hint
            fill.color = theme.accent
            Icons.pin(canvas, pinCx, cy, 18 * dp, stroke, fill, clip.pinned)
            stroke.color = theme.hint
            Icons.close(canvas, closeCx, cy, 18 * dp, stroke)
        }
        canvas.restore()
    }

    private fun drawTabs(canvas: Canvas) {
        val top = listBottom
        val bottom = top + tabHeight
        val slots = tabSlots()
        label.textSize = tabHeight * 0.34f
        val baseline = -(label.descent() + label.ascent()) / 2
        label.color = theme.label
        for ((index, slot) in slots.withIndex()) {
            val cx = (slot.first + slot.second) / 2
            val cy = (top + bottom) / 2
            val caption = when (index) {
                0 -> context.getString(R.string.emoji_letters)
                1 -> context.getString(clearLabel)
                else -> "⌫"
            }
            canvas.drawText(caption, cx, cy + baseline, label)
        }
    }

    /** Left to right: the letters key, clear everything, then the backspace. */
    private fun tabSlots(): List<Pair<Float, Float>> {
        val edge = panelPad + sideInset + Geometry.SIDE_PAD_DP * dp
        val span = (width - 2 * edge) / TABS
        return (0 until TABS).map { edge + it * span to edge + (it + 1) * span }
    }

    // ---- touch ----------------------------------------------------------------------------------------------------

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                downY = event.y
                downScroll = scroll
                dragging = false
                inList = event.y in listTop..listBottom
                pressed = if (inList) rowAt(event.y) else -1
                if (pressed >= 0) invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (!dragging && inList && abs(event.y - downY) > touchSlop) {
                    dragging = true
                    pressed = -1
                }
                if (dragging) {
                    scroll = (downScroll - (event.y - downY)).coerceIn(0f, maxScroll)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(event)
                if (dragging) fling() else tapped(event.x, event.y)
                release()
                invalidate()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    private fun fling() {
        val tracker = velocity ?: return
        tracker.computeCurrentVelocity(1000)
        val speed = -tracker.yVelocity
        if (abs(speed) < MIN_FLING_DP * dp) return
        scroller.fling(0, scroll.roundToInt(), 0, speed.roundToInt(), 0, 0, 0, maxScroll.roundToInt())
        postInvalidateOnAnimation()
    }

    private fun release() {
        velocity?.recycle()
        velocity = null
        pressed = -1
        dragging = false
        inList = false
    }

    private fun tapped(x: Float, y: Float) {
        if (y >= listBottom) {
            val index = tabSlots().indexOfFirst { x >= it.first && x < it.second }
            if (index < 0) return
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            when (index) {
                0 -> listener?.onLetters()
                1 -> listener?.onClearClips()
                else -> listener?.onBackspace()
            }
            return
        }
        val row = rowAt(y)
        val clip = clips.getOrNull(row) ?: return
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (partAt(x)) {
            Part.PIN -> listener?.onPinClip(clip.text, !clip.pinned)
            Part.FORGET -> listener?.onForgetClip(clip.text)
            Part.TEXT -> listener?.onClip(clip.text)
        }
    }

    private enum class Part { TEXT, PIN, FORGET }

    private fun partAt(x: Float): Part {
        val edge = panelPad + sideInset + Geometry.SIDE_PAD_DP * dp
        val pinFrom = width - edge - buttonWidth * 2
        val closeFrom = width - edge - buttonWidth
        return when {
            x >= closeFrom -> Part.FORGET
            x >= pinFrom -> Part.PIN
            else -> Part.TEXT
        }
    }

    private fun rowAt(y: Float): Int {
        if (y < listTop || y >= listBottom) return -1
        val row = ((y - listTop + scroll) / rowHeight).toInt()
        return if (row in clips.indices) row else -1
    }

    // ---- screen readers ---------------------------------------------------------------------------------------

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        cells.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /**
     * Three virtual views per row — the clip, its pin and its cross — then the tabs.
     *
     * A screen reader has to be able to reach the pin: a row that only announces its text leaves pinning and
     * forgetting to people who can see where to aim.
     */
    private fun idOf(row: Int, part: Part) = row * 3 + part.ordinal

    private fun rowOf(id: Int) = id / 3

    private fun partOf(id: Int) = Part.entries[id % 3]

    private fun boundsOf(id: Int): Rect? {
        val edge = (panelPad + sideInset + Geometry.SIDE_PAD_DP * dp).toInt()
        if (id < clips.size * 3) {
            val top = listTop + rowOf(id) * rowHeight - scroll
            if (top + rowHeight < listTop || top > listBottom) return null   // scrolled out of sight
            val bottom = (top + rowHeight).toInt()
            return when (partOf(id)) {
                Part.TEXT -> Rect(edge, top.toInt(), (width - edge - buttonWidth * 2).toInt(), bottom)
                Part.PIN -> Rect(
                    (width - edge - buttonWidth * 2).toInt(), top.toInt(),
                    (width - edge - buttonWidth).toInt(), bottom,
                )
                Part.FORGET -> Rect((width - edge - buttonWidth).toInt(), top.toInt(), width - edge, bottom)
            }
        }
        val slot = tabSlots().getOrNull(id - clips.size * 3) ?: return null
        return Rect(slot.first.toInt(), listBottom.toInt(), slot.second.toInt(), (listBottom + tabHeight).toInt())
    }

    private fun nameOf(id: Int): String {
        if (id < clips.size * 3) {
            val clip = clips[rowOf(id)]
            val short = clip.text.replace('\n', ' ').take(SPOKEN_CHARS)
            return when (partOf(id)) {
                Part.TEXT -> short
                Part.PIN -> context.getString(
                    if (clip.pinned) R.string.clipboard_unpin_spoken else R.string.clipboard_pin_spoken, short,
                )
                Part.FORGET -> context.getString(R.string.clipboard_forget_spoken, short)
            }
        }
        return when (id - clips.size * 3) {
            0 -> context.getString(R.string.emoji_letters)
            1 -> context.getString(clearLabel)
            else -> Spoken.name(Key("", KeyKind.BACKSPACE), Shift.OFF)
        }
    }

    private fun activate(id: Int): Boolean {
        if (id < clips.size * 3) {
            val clip = clips[rowOf(id)]
            when (partOf(id)) {
                Part.TEXT -> listener?.onClip(clip.text)
                Part.PIN -> listener?.onPinClip(clip.text, !clip.pinned)
                Part.FORGET -> listener?.onForgetClip(clip.text)
            }
            return true
        }
        when (id - clips.size * 3) {
            0 -> listener?.onLetters()
            1 -> listener?.onClearClips()
            else -> listener?.onBackspace()
        }
        return true
    }

    private inner class CellNodes : ExploreByTouchHelper(this@ClipboardPanel) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (y >= listBottom) {
                val slot = tabSlots().indexOfFirst { x >= it.first && x < it.second }
                return if (slot < 0) HOST_ID else clips.size * 3 + slot
            }
            val row = rowAt(y)
            return if (row < 0) HOST_ID else idOf(row, partAt(x))
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            val total = clips.size * 3 + TABS
            for (id in 0 until total) if (boundsOf(id) != null) ids.add(id)
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val bounds = boundsOf(id)
            if (bounds == null) {
                node.contentDescription = ""
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            node.contentDescription = nameOf(id)
            node.className = "android.widget.Button"
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.setBoundsInParent(bounds)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
            if (!activate(id)) return false
            sendEventForVirtualView(id, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        }
    }

    /** What a test needs to ask: the order on screen, where a row is, and how far it can scroll. */
    internal fun showing(): List<String> = clips.map { it.text }
    internal fun rowAtForTest(y: Float): Int = rowAt(y)
    internal fun partAtForTest(x: Float): String = partAt(x).name
    internal val scrollRange: Float get() = maxScroll
    internal val tabCount: Int get() = TABS

    private companion object {
        const val PANEL_PAD_DP = 6f
        const val PANEL_RADIUS_DP = 22f
        const val TAB_DP = 42f
        const val ROW_DP = 52f
        const val BUTTON_DP = 48f
        const val LIST_PAD_DP = 6f
        const val MIN_FLING_DP = 50f
        const val SPOKEN_CHARS = 60

        /** The letters keyboard is four rows; matching it keeps the app above from jumping when you switch. */
        const val ROWS_LIKE_LETTERS = 4
        const val TABS = 3
    }
}

/** Paint is what measures text; the ellipsize call wants a TextPaint, which is the same thing with a wrapper. */
private fun TextPaintOf(paint: Paint) = android.text.TextPaint(paint)
