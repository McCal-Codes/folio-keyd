package com.mccal.folio.keys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.os.Bundle
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
 * The emoji keyboard.
 *
 * A sibling of [KeyboardView] rather than another mode inside it: the two share a look and the room they leave at the
 * bottom, but nothing else. One is a fixed grid of keys that never moves; this one scrolls, pages by category and has
 * no layers, and folding both into one view would leave every method asking which of the two it was in.
 *
 * The emoji themselves are drawn as text, because that is what they are - the phone's own font decides how they look,
 * so they match every other app on the phone rather than shipping a second set that doesn't.
 */
class EmojiPanel(context: Context) : View(context) {

    interface Listener {
        fun onEmoji(emoji: String)
        fun onBackspace()

        /** Back to the letters. */
        fun onLetters()
    }

    var listener: Listener? = null

    /** What has been used before, newest first. Owned by the service, which is what remembers it. */
    var recents: List<String> = emptyList()
        set(value) {
            field = value
            if (category == 0) requestLayout()
            invalidate()
        }

    private val dp = context.resources.displayMetrics.density
    private var theme = Theme.of(context)
    private var bottomInset = BottomRoom.GESTURE_BAND_DP * dp
    private var sideInset = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    /** 0 is the recents; the rest index [Emoji.CATEGORIES]. */
    private var category = 0
    private var scroll = 0f
    private var maxScroll = 0f
    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0f
    private var dragging = false
    private var inGrid = false
    private var pressed = -1
    private var velocity: VelocityTracker? = null

    private var columns = 8
    private var cell = 44 * dp
    private var gridTop = 0f
    private var gridBottom = 0f

    private val cells = CellNodes()

    init {
        isHapticFeedbackEnabled = true
        setWillNotDraw(false)
        ViewCompat.setAccessibilityDelegate(this, cells)
    }

    private val panelPad get() = PANEL_PAD_DP * dp
    private val tabHeight get() = TAB_DP * dp

    private fun items(): List<String> =
        if (category == 0) recents else Emoji.CATEGORIES[category - 1].items

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
     * The same height the letters would have taken.
     *
     * Swapping to the emoji and finding the app has jumped up or down the screen is the thing that makes an emoji
     * keyboard feel bolted on, so this measures to the keyboard's height rather than to its own content.
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
        val edge = panelPad + sideInset + Geometry.SIDE_PAD_DP * dp
        val usable = width - 2 * edge
        // Emoji want to be about a thumb across; how many fit is then arithmetic rather than a number picked per
        // device, so a Fold's inner screen gets more of them per row instead of bigger ones.
        columns = max(6, (usable / (CELL_DP * dp)).toInt())
        cell = usable / columns
        gridTop = panelPad + GRID_PAD_DP * dp
        gridBottom = height - bottomInset - panelPad - tabHeight
        scroll = scroll.coerceIn(0f, contentHeight() - visibleHeight())
        maxScroll = max(0f, contentHeight() - visibleHeight())
    }

    private fun visibleHeight() = max(0f, gridBottom - gridTop)

    private fun contentHeight(): Float {
        val count = items().size
        val rows = (count + columns - 1) / columns
        return max(visibleHeight(), rows * cell)
    }

    // ---- drawing --------------------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        theme = Theme.of(context)
        rect.set(panelPad, panelPad, width - panelPad, height - panelPad)
        fill.color = theme.board
        canvas.drawRoundRect(rect, PANEL_RADIUS_DP * dp, PANEL_RADIUS_DP * dp, fill)

        drawGrid(canvas)
        drawTabs(canvas)
        drawHandle(canvas)
    }

    /** The same pill the letters have in the band below them: the two panels should not end differently. */
    private fun drawHandle(canvas: Canvas) {
        val bandTop = height - bottomInset - panelPad
        val centre = (bandTop + height - panelPad) / 2
        rect.set(
            width / 2f - HANDLE_W_DP * dp / 2, centre - HANDLE_H_DP * dp / 2,
            width / 2f + HANDLE_W_DP * dp / 2, centre + HANDLE_H_DP * dp / 2,
        )
        fill.color = theme.hint
        canvas.drawRoundRect(rect, HANDLE_H_DP * dp / 2, HANDLE_H_DP * dp / 2, fill)
    }

    private fun drawGrid(canvas: Canvas) {
        val list = items()
        if (list.isEmpty()) {
            label.textSize = 14 * dp
            label.color = theme.hint
            canvas.drawText(
                context.getString(R.string.emoji_no_recents),
                width / 2f, (gridTop + gridBottom) / 2, label,
            )
            return
        }
        canvas.save()
        canvas.clipRect(0f, gridTop, width.toFloat(), gridBottom)
        val edge = panelPad + sideInset + Geometry.SIDE_PAD_DP * dp
        glyph.textSize = cell * 0.62f
        val baseline = -(glyph.descent() + glyph.ascent()) / 2
        // Only the rows on screen are drawn: a category is a few hundred emoji and the rest are somewhere above.
        val first = max(0, (scroll / cell).toInt() * columns)
        val last = min(list.size, first + (visibleHeight() / cell).toInt() * columns + columns * 2)
        for (index in first until last) {
            val row = index / columns
            val column = index % columns
            val cx = edge + column * cell + cell / 2
            val cy = gridTop + row * cell - scroll + cell / 2
            if (index == pressed) {
                rect.set(cx - cell * 0.44f, cy - cell * 0.44f, cx + cell * 0.44f, cy + cell * 0.44f)
                fill.color = theme.pressTint
                canvas.drawRoundRect(rect, 10 * dp, 10 * dp, fill)
            }
            canvas.drawText(list[index], cx, cy + baseline, glyph)
        }
        canvas.restore()
    }

    private fun drawTabs(canvas: Canvas) {
        val top = gridBottom
        val bottom = top + tabHeight
        val slots = tabSlots()
        glyph.textSize = tabHeight * 0.44f
        val baseline = -(glyph.descent() + glyph.ascent()) / 2
        label.textSize = tabHeight * 0.34f
        val labelBaseline = -(label.descent() + label.ascent()) / 2
        for ((index, slot) in slots.withIndex()) {
            val cx = (slot.first + slot.second) / 2
            val cy = (top + bottom) / 2
            when (index) {
                0 -> {
                    label.color = theme.label
                    canvas.drawText(context.getString(R.string.emoji_letters), cx, cy + labelBaseline, label)
                }
                slots.lastIndex -> {
                    label.color = theme.label
                    canvas.drawText("⌫", cx, cy + labelBaseline, label)
                }
                else -> {
                    val which = index - 1
                    if (which == category) {
                        rect.set(cx - tabHeight * 0.34f, top + 4 * dp, cx + tabHeight * 0.34f, bottom - 4 * dp)
                        fill.color = theme.pressTint
                        canvas.drawRoundRect(rect, 8 * dp, 8 * dp, fill)
                    }
                    val tab = if (which == 0) RECENT_TAB else Emoji.CATEGORIES[which - 1].tab
                    canvas.drawText(tab, cx, cy + baseline, glyph)
                }
            }
        }
    }

    /** Left to right: the letters key, one tab per category, then the backspace. */
    private fun tabSlots(): List<Pair<Float, Float>> {
        val edge = panelPad + sideInset + Geometry.SIDE_PAD_DP * dp
        val count = Emoji.CATEGORIES.size + 3   // letters + recents + categories + backspace
        val span = (width - 2 * edge) / count
        return (0 until count).map { edge + it * span to edge + (it + 1) * span }
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
                downX = event.x
                downY = event.y
                downScroll = scroll
                dragging = false
                // Anywhere in the grid scrolls, including the empty space past the end of a short category.
                inGrid = event.y in gridTop..gridBottom
                pressed = if (inGrid) indexAt(event.x, event.y) else -1
                if (pressed >= 0) invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (!dragging && inGrid && abs(event.y - downY) > touchSlop) {
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
                if (dragging) {
                    fling()
                } else {
                    tapped(event.x, event.y)
                }
                release()
                invalidate()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    /** Carry on where the finger left off, which is the difference between a list and a sheet of paper. */
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
        inGrid = false
    }

    private fun tapped(x: Float, y: Float) {
        if (y >= gridBottom) {
            val slots = tabSlots()
            val index = slots.indexOfFirst { x >= it.first && x < it.second }
            if (index < 0) return
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            when (index) {
                0 -> listener?.onLetters()
                slots.lastIndex -> listener?.onBackspace()
                else -> {
                    category = index - 1
                    scroll = 0f
                    requestLayout()
                    invalidate()
                }
            }
            return
        }
        val index = indexAt(x, y)
        val list = items()
        if (index in list.indices) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            listener?.onEmoji(list[index])
        }
    }

    private fun indexAt(x: Float, y: Float): Int {
        if (y < gridTop || y >= gridBottom) return -1
        val edge = panelPad + sideInset + Geometry.SIDE_PAD_DP * dp
        val column = ((x - edge) / cell).toInt()
        if (column < 0 || column >= columns) return -1
        val row = ((y - gridTop + scroll) / cell).toInt()
        val index = row * columns + column
        return if (index in items().indices) index else -1
    }

    // ---- screen readers ---------------------------------------------------------------------------------------

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        cells.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /** Where a virtual view sits: the grid first, then the tab bar, so the ids stay stable while scrolling. */
    private fun boundsOf(id: Int): Rect? {
        val list = items()
        val edge = panelPad + sideInset + Geometry.SIDE_PAD_DP * dp
        if (id < list.size) {
            val row = id / columns
            val column = id % columns
            val left = edge + column * cell
            val top = gridTop + row * cell - scroll
            if (top + cell < gridTop || top > gridBottom) return null   // scrolled out of sight
            return Rect(left.toInt(), top.toInt(), (left + cell).toInt(), (top + cell).toInt())
        }
        val slot = tabSlots().getOrNull(id - list.size) ?: return null
        return Rect(slot.first.toInt(), gridBottom.toInt(), slot.second.toInt(), (gridBottom + tabHeight).toInt())
    }

    private fun nameOf(id: Int): String {
        val list = items()
        if (id < list.size) return list[id]
        val tab = id - list.size
        val slots = tabSlots()
        return when (tab) {
            0 -> context.getString(R.string.emoji_letters)
            slots.lastIndex -> Spoken.name(Key("", KeyKind.BACKSPACE), Shift.OFF)
            1 -> Emoji.RECENT
            else -> Emoji.CATEGORIES.getOrNull(tab - 2)?.name.orEmpty()
        }
    }

    private fun activate(id: Int): Boolean {
        val list = items()
        if (id < list.size) {
            listener?.onEmoji(list[id])
            return true
        }
        val tab = id - list.size
        val slots = tabSlots()
        when (tab) {
            0 -> listener?.onLetters()
            slots.lastIndex -> listener?.onBackspace()
            else -> selectCategory(tab - 1)
        }
        return true
    }

    private inner class CellNodes : ExploreByTouchHelper(this@EmojiPanel) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (y >= gridBottom) {
                val slot = tabSlots().indexOfFirst { x >= it.first && x < it.second }
                return if (slot < 0) HOST_ID else items().size + slot
            }
            val index = indexAt(x, y)
            return if (index < 0) HOST_ID else index
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            val total = items().size + tabSlots().size
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

    /** What a test needs to ask: where the grid is, and how much of it there is. */
    internal val visibleColumns: Int get() = columns
    internal val cellSizeDp: Float get() = cell / dp
    internal val tabCount: Int get() = tabSlots().size
    internal fun showing(): List<String> = items()
    internal fun selectCategory(which: Int) {
        category = which
        scroll = 0f
        requestLayout()
        invalidate()
    }

    private companion object {
        const val PANEL_PAD_DP = 6f
        const val PANEL_RADIUS_DP = 22f
        const val TAB_DP = 42f
        const val CELL_DP = 44f
        const val GRID_PAD_DP = 6f
        const val MIN_FLING_DP = 50f
        const val HANDLE_W_DP = 38f
        const val HANDLE_H_DP = 4f
        const val RECENT_TAB = "🕒"   // a clock face, which reads as colour beside the other tabs

        /** The letters keyboard is four rows; matching it keeps the app above from jumping when you switch. */
        const val ROWS_LIKE_LETTERS = 4
    }
}
