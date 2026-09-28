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
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.max
import kotlin.math.min

/**
 * Emoji search: a row for the word, a row of what it found, and letters to type it with.
 *
 * Opened from the search key on [EmojiPanel]'s tab row, and in its place, at the same height, so the app above does
 * not move. The letters here type into the search and never into the app: nothing reaches the field until an emoji
 * is tapped, and then only that emoji.
 *
 * The letters are a real [KeyboardView] with [KeyboardView.searchKeys] on, under a small [Bar] of its own, rather
 * than a search mode drawn into either the keyboard or the emoji grid. The keyboard's touch handling is the most
 * worked-over code in Keyd (sliding between keys, two thumbs at once, the backspace repeat) and a second copy of it
 * for one panel would be a second set of bugs. Folding the search row into [KeyboardView] instead would have meant
 * every method there asking which of two keyboards it was, which is the reason the emoji grid is a sibling view too.
 * So this is a frame: it draws one board behind both, measures to the emoji panel's height, and turns the keys into
 * a query.
 */
internal class EmojiSearchPanel(context: Context) : ViewGroup(context) {

    interface Listener {
        /** Put this emoji in the field. The search stays open, so several can be added in a row. */
        fun onEmoji(emoji: String)

        /** Back to the emoji grid. */
        fun onBack()

        /** Swiped down off the space bar, which hides the keyboard everywhere else too. */
        fun onHide() {}
    }

    var listener: Listener? = null

    /** The names and keywords, once they have been read. Null until then, which shows no results rather than none. */
    var search: EmojiSearch? = null
        set(value) {
            field = value
            refresh()
        }

    /**
     * What has been used lately, which comes first among equal matches.
     *
     * Only read when the query changes. Picking an emoji makes it the most recent, and moving it to the front of the
     * results the moment it is tapped would put a different emoji under the finger for the second tap.
     */
    var recents: List<String> = emptyList()

    /** What has been typed so far. Only ever changed by the keys here, and cleared each time the search opens. */
    var query: String = ""
        private set

    /** Found for [query], best first, and never more than [Bar.columns] fit. */
    internal var results: List<String> = emptyList()
        private set

    private val dp = context.resources.displayMetrics.density
    private var bottomInset = BottomRoom.GESTURE_BAND_DP * dp
    private var sideInset = 0f
    private val panelPad get() = PANEL_PAD_DP * dp

    private var appearance = Appearance.SYSTEM
    private var highContrast = false
    private var keyStyle = KeyStyle.FOLIO
    private var vibrate = true
    private var theme = Theme.of(context)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    internal val bar = Bar(context)

    internal val keys = KeyboardView(context).also {
        it.searchKeys = true
        it.listener = Typing()
    }

    init {
        setWillNotDraw(false)
        addView(bar)
        addView(keys)
    }

    /**
     * Shown: an empty query, and the person's own look and feel on the letters.
     *
     * Accents are off on these letters: the search takes them off anyway, and a row of accents popping up over the
     * search row would cover the results it is meant to be narrowing.
     */
    fun opened(settings: Settings, language: Language) {
        appearance = settings.appearance
        highContrast = settings.highContrast
        keyStyle = settings.keyStyle
        vibrate = settings.vibrate
        keys.settings = settings.copy(accents = false)
        keys.language = language
        keys.rows = Layouts.searchRows(language)
        keys.forgetTouches()
        query = ""
        refresh()
        invalidate()
    }

    fun forgetTouches() = keys.forgetTouches()

    private fun type(text: String) {
        if (query.length + text.length > MAX_QUERY) return
        query += text
        refresh()
    }

    private fun erase() {
        if (query.isEmpty()) return
        query = query.dropLast(1)
        refresh()
    }

    private fun clear() {
        query = ""
        refresh()
    }

    /** Looks again. Cheap enough to do on every keystroke: it is a scan of a few hundred short lists of words. */
    private fun refresh() {
        results = search?.search(query, bar.columns, recents).orEmpty()
        bar.changed()
    }

    // ---- measuring ------------------------------------------------------------------------------------------------

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
        return insets   // not consumed: the letters below need the same insets
    }

    /**
     * The emoji panel's height, worked out the same way, because this opens in its place and the app above must
     * not move. The letters get whatever the search rows leave, which makes them shorter than the keyboard's.
     */
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val height = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightSpec)
        } else {
            Geometry.height(
                ROWS_LIKE_LETTERS, resources.configuration.screenHeightDp.toFloat(), dp, bottomInset,
                extra = TAB_LIKE_EMOJI_DP * dp + panelPad,
            )
        }
        val top = min(bar.wanted(), height)
        bar.measure(exactly(width), exactly(top))
        keys.measure(exactly(width), exactly(height - top))
        setMeasuredDimension(width, height)
    }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        bar.layout(0, 0, r - l, bar.measuredHeight)
        keys.layout(0, bar.measuredHeight, r - l, bar.measuredHeight + keys.measuredHeight)
        // How many results fit depends on the width, which is only known now.
        refresh()
    }

    /** One board behind the search rows and the letters, so they read as one keyboard rather than two. */
    override fun onDraw(canvas: Canvas) {
        theme = Theme.of(context, appearance, highContrast, keyStyle)
        rect.set(panelPad, panelPad, width - panelPad, height - panelPad)
        fill.color = theme.board
        canvas.drawRoundRect(rect, PANEL_RADIUS_DP * dp, PANEL_RADIUS_DP * dp, fill)
    }

    // ---- the letters ----------------------------------------------------------------------------------------------

    /** The keys, turned into a query. Anything that would act on the app's text does nothing here. */
    private inner class Typing : KeyboardView.Listener {
        override fun onText(text: String) = type(text)
        override fun onBackspace() = erase()
        override fun onDeleteWord() {
            query = query.trimEnd().dropLastWhile { !it.isWhitespace() }
            refresh()
        }
        override fun onEmojiPanel() { listener?.onBack() }
        override fun onHide() { listener?.onHide() }
        override fun onShift() = Unit
        override fun onLayer(layer: Layer) = Unit
        override fun onAction() = Unit
        override fun onSwitchKeyboard() = Unit
        override fun onCursor(steps: Int) = Unit
        override fun onSelectAll() = Unit
        override fun onCopy() = Unit
        override fun onPaste() = Unit
        override fun onClipboardPanel() = Unit
        override fun onSuggestion(word: String) = Unit
    }

    // ---- the search row and the results ---------------------------------------------------------------------------

    /**
     * The back arrow, the field and the results, drawn like the rest of Keyd rather than built from widgets.
     *
     * The field is not an EditText. Text typed here is Keyd talking to itself, and an EditText inside an input method
     * is a field that Android would try to give a keyboard to.
     */
    internal inner class Bar(context: Context) : View(context) {

        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG)
        private val centred = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val nodes = Nodes()
        private var pressed = NONE

        init {
            isHapticFeedbackEnabled = true
            ViewCompat.setAccessibilityDelegate(this, nodes)
        }

        fun wanted(): Int = (panelPad + ROW_GAP_DP * dp + FIELD_ROW_DP * dp + RESULT_DP * dp).toInt()

        private val edge get() = panelPad + sideInset + Geometry.SIDE_PAD_DP * dp
        private val fieldTop get() = panelPad + ROW_GAP_DP * dp
        private val fieldBottom get() = fieldTop + FIELD_ROW_DP * dp
        private val resultsTop get() = fieldBottom
        private val resultsBottom get() = resultsTop + RESULT_DP * dp
        private val backRight get() = edge + BACK_DP * dp
        private val fieldLeft get() = backRight + 4 * dp
        private val fieldRight get() = width - edge
        private val clearLeft get() = fieldRight - CLEAR_DP * dp

        /**
         * How many results fit in the row: only whole cells of at least [RESULT_DP], never a half emoji at the edge.
         * The spare width is shared between them, so the row still runs edge to edge.
         */
        val columns: Int
            get() = if (width == 0) 0 else max(0, ((width - 2 * edge) / (RESULT_DP * dp)).toInt())

        private val cell get() = if (columns == 0) 0f else (width - 2 * edge) / columns

        fun changed() {
            nodes.invalidateRoot()
            invalidate()
        }

        /** Where result [index] sits, in this view's pixels. */
        fun resultBox(index: Int): Box {
            val left = edge + index * cell
            return Box(left, resultsTop, left + cell, resultsBottom)
        }

        private fun boxOf(id: Int): Box? = when (id) {
            BACK -> Box(edge, fieldTop, backRight, fieldBottom)
            FIELD -> Box(fieldLeft, fieldTop, if (query.isEmpty()) fieldRight else clearLeft, fieldBottom)
            CLEAR -> if (query.isEmpty()) null else Box(clearLeft, fieldTop, fieldRight, fieldBottom)
            else -> if (id - FIRST_RESULT in results.indices) resultBox(id - FIRST_RESULT) else null
        }

        private fun idAt(x: Float, y: Float): Int {
            for (id in listOf(BACK, CLEAR, FIELD)) if (boxOf(id)?.contains(x, y) == true) return id
            if (y < resultsTop || y >= resultsBottom || cell == 0f) return NONE
            val index = ((x - edge) / cell).toInt()
            return if (x >= edge && index in results.indices) FIRST_RESULT + index else NONE
        }

        private fun nameOf(id: Int): String = when (id) {
            BACK -> context.getString(R.string.emoji_search_back)
            FIELD -> if (query.isEmpty()) context.getString(R.string.emoji_search)
                else context.getString(R.string.emoji_search_field, query)
            CLEAR -> context.getString(R.string.emoji_search_clear)
            else -> results.getOrNull(id - FIRST_RESULT)?.let { search?.nameOf(it) ?: it }.orEmpty()
        }

        /** Does what tapping [id] does. False for the field, which is only there to be read. */
        private fun activate(id: Int): Boolean {
            when (id) {
                BACK -> listener?.onBack()
                CLEAR -> clear()
                FIELD, NONE -> return false
                else -> listener?.onEmoji(results.getOrNull(id - FIRST_RESULT) ?: return false)
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val theme = this@EmojiSearchPanel.theme
            val cy = (fieldTop + fieldBottom) / 2
            stroke.strokeWidth = 1.8f * dp

            stroke.color = theme.label
            Icons.back(canvas, (edge + backRight) / 2, cy, 20 * dp, stroke)

            // The field: a pill in the key colour, so it reads as something to type into.
            val inset = 3 * dp
            rect.set(fieldLeft, fieldTop + inset, fieldRight, fieldBottom - inset)
            fill.color = theme.key
            val round = (rect.bottom - rect.top) / 2
            canvas.drawRoundRect(rect, round, round, fill)
            stroke.color = theme.hint
            Icons.magnifier(canvas, fieldLeft + 18 * dp, cy, 16 * dp, stroke)

            text.textSize = 16 * dp
            val baseline = cy - (text.descent() + text.ascent()) / 2
            val start = fieldLeft + 34 * dp
            if (query.isEmpty()) {
                // Just clear of the caret, which waits at the start of the empty field.
                text.color = theme.hint
                canvas.drawText(context.getString(R.string.emoji_search), start + 5 * dp, baseline, text)
            } else {
                // Long queries keep their end in view, which is the part being typed.
                val room = clearLeft - start - 6 * dp
                var shown = query
                while (shown.isNotEmpty() && text.measureText(shown) > room) shown = shown.drop(1)
                text.color = theme.label
                canvas.drawText(shown, start, baseline, text)
                stroke.color = theme.hint
                Icons.close(canvas, (clearLeft + fieldRight) / 2, cy, 20 * dp, stroke)
            }
            // The caret, so it is clear where the letters are going.
            val caretX = start + if (query.isEmpty()) 0f else min(text.measureText(query), clearLeft - start - 6 * dp) + 1 * dp
            fill.color = theme.accent
            canvas.drawRect(caretX, cy - 9 * dp, caretX + 2 * dp, cy + 9 * dp, fill)

            drawResults(canvas, theme)
        }

        private fun drawResults(canvas: Canvas, theme: Theme) {
            val cy = (resultsTop + resultsBottom) / 2
            val message = when {
                query.isBlank() -> context.getString(R.string.emoji_search_hint)
                results.isEmpty() && search != null -> context.getString(R.string.emoji_search_none, query.trim())
                else -> null
            }
            if (message != null) {
                centred.textSize = 14 * dp
                centred.color = theme.hint
                val shown = TextUtils.ellipsize(
                    message, android.text.TextPaint(centred), width - 2 * edge, TextUtils.TruncateAt.END,
                )
                canvas.drawText(shown, 0, shown.length, width / 2f, cy - (centred.descent() + centred.ascent()) / 2, centred)
                return
            }
            centred.textSize = min(cell, RESULT_DP * dp) * 0.6f
            val baseline = -(centred.descent() + centred.ascent()) / 2
            for ((index, glyph) in results.withIndex()) {
                val box = resultBox(index)
                val cx = (box.left + box.right) / 2
                if (pressed == FIRST_RESULT + index) {
                    val half = min(box.width, box.height) * 0.46f
                    rect.set(cx - half, cy - half, cx + half, cy + half)
                    fill.color = theme.pressTint
                    canvas.drawRoundRect(rect, 10 * dp, 10 * dp, fill)
                }
                canvas.drawText(glyph, cx, cy + baseline, centred)
            }
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = idAt(event.x, event.y)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val id = idAt(event.x, event.y)
                    // Only a tap that lets go where it landed: sliding off is how you change your mind.
                    if (id == pressed && id != NONE && id != FIELD) {
                        if (vibrate) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        activate(id)
                        performClick()
                    }
                    pressed = NONE
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressed = NONE
                    invalidate()
                }
            }
            return true
        }

        override fun dispatchHoverEvent(event: MotionEvent): Boolean =
            nodes.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

        /** What TalkBack finds, in reading order. */
        internal fun spoken(): List<String> = visibleIds().map { nameOf(it) }

        private fun visibleIds(): List<Int> =
            (listOf(BACK, FIELD, CLEAR) + results.indices.map { FIRST_RESULT + it }).filter { boxOf(it) != null }

        /** Every part of the row is a node of its own: the arrow, the field, the cross, and each result by its name. */
        private inner class Nodes : ExploreByTouchHelper(this@Bar) {
            override fun getVirtualViewAt(x: Float, y: Float): Int = idAt(x, y).let { if (it == NONE) HOST_ID else it }

            override fun getVisibleVirtualViews(ids: MutableList<Int>) {
                ids.addAll(visibleIds())
            }

            override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
                val box = boxOf(id)
                if (box == null) {
                    node.contentDescription = ""
                    node.setBoundsInParent(Rect(0, 0, 1, 1))
                    return
                }
                node.contentDescription = nameOf(id)
                if (id == FIELD) {
                    node.className = "android.widget.TextView"
                } else {
                    node.className = "android.widget.Button"
                    node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
                }
                node.setBoundsInParent(Rect(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt()))
            }

            override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
                if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
                if (!activate(id)) return false
                sendEventForVirtualView(id, AccessibilityEvent.TYPE_VIEW_CLICKED)
                return true
            }
        }
    }

    /** Tapping a result by position, for a test that should not have to work out where the row is. */
    internal fun tapResultForTest(index: Int) {
        val box = bar.resultBox(index)
        val x = (box.left + box.right) / 2
        val y = (box.top + box.bottom) / 2
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0, 0, action, x, y, 0)
            bar.onTouchEvent(event)
            event.recycle()
        }
    }

    private companion object {
        const val PANEL_PAD_DP = 6f
        const val PANEL_RADIUS_DP = 22f
        const val ROW_GAP_DP = 4f
        const val FIELD_ROW_DP = 40f
        const val BACK_DP = 44f
        const val CLEAR_DP = 40f

        /** A result is at least this wide and this tall: a fingertip, and the size TalkBack asks of a target. */
        const val RESULT_DP = 48f

        /** More than anyone types to find an emoji, and short enough that the field never has to scroll far. */
        const val MAX_QUERY = 40

        /** The emoji panel's own numbers, so the two measure to the same height. */
        const val ROWS_LIKE_LETTERS = 4
        const val TAB_LIKE_EMOJI_DP = 42f

        const val NONE = -1
        const val BACK = 0
        const val FIELD = 1
        const val CLEAR = 2
        const val FIRST_RESULT = 3
    }
}
