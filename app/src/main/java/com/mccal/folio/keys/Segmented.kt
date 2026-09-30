package com.mccal.folio.keys

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

/**
 * Samsung's way with a setting of a few fixed choices: all of them in one rounded track, the chosen one raised,
 * changed in one tap with no page to open.
 *
 * It wraps rather than cutting a word short. On the Fold's cover screen with text at 200%, "Shorter", "Phone" and
 * "Longer" do not fit side by side, and a truncated "Sho…" says nothing; so the options flow onto a second line,
 * each line still filling the track. TalkBack hears one group, named by [label], of buttons that each say whether
 * they are the chosen one and where they sit in the set.
 */
// Only ever built in code, with its options, so the layout-inflation constructors would have nothing to build from.
@android.annotation.SuppressLint("ViewConstructor")
internal class Segmented(
    context: Context,
    private val label: String,
    options: List<String>,
    selected: Int,
    private val colors: Colors,
    private val picked: (Int) -> Unit,
) : ViewGroup(context) {

    /** The track, the raised chosen option, and the text on both. */
    data class Colors(val track: Int, val raised: Int, val text: Int)

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float) = (value * density).toInt()
    private val pad = dp(3f)
    private val gap = dp(4f)

    var selected: Int = selected
        private set

    private val buttons: List<TextView> = options.mapIndexed { index, text -> button(index, text, options.size) }

    init {
        // Read before the apply: inside GradientDrawable.apply, `colors` is the drawable's own gradient.
        val track = colors.track
        background = GradientDrawable().apply { setColor(track); cornerRadius = dp(10f).toFloat() }
        setPadding(pad, pad, pad, pad)
        buttons.forEach { addView(it) }
        // The group is what TalkBack names; the options inside it are what it lands on.
        contentDescription = label
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        show()
    }

    private fun button(index: Int, text: String, count: Int) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(colors.text)
        gravity = Gravity.CENTER
        minHeight = dp(40f)
        minimumHeight = dp(40f)
        setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
        isClickable = true
        isFocusable = true
        setOnClickListener {
            if (index == selected) return@setOnClickListener
            selected = index
            show()
            picked(index)
            // Nothing is rebuilt, so the raised option moving is announced here, as a tapped radio button's is.
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
        }
        accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                val on = index == selected
                info.className = android.widget.Button::class.java.name
                info.isCheckable = true
                info.isChecked = on
                info.isSelected = on
                // "Medium, selected, 3 of 4": the state and the place in the set, which the looks alone give.
                info.stateDescription = context.getString(
                    if (on) R.string.segment_selected else R.string.segment_not_selected, index + 1, count,
                )
                info.collectionItemInfo = AccessibilityNodeInfo.CollectionItemInfo(0, 1, index, 1, false, on)
            }
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.collectionInfo = AccessibilityNodeInfo.CollectionInfo(
            1, buttons.size, false, AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE,
        )
    }

    private fun show() = buttons.forEachIndexed { index, button ->
        val on = index == selected
        button.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        button.isSelected = on
        val raised = colors.raised
        button.background = if (on) GradientDrawable().apply {
            setColor(raised)
            cornerRadius = dp(8f).toFloat()
        } else null
        button.elevation = if (on) dp(1f).toFloat() else 0f
    }

    /** Which options go on each line: as many as fit, in order, and never none. */
    private var lines: List<List<Int>> = emptyList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val natural = buttons.map { button ->
            button.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            button.measuredWidth
        }
        val built = mutableListOf<MutableList<Int>>()
        var used = 0
        natural.forEachIndexed { index, w ->
            val line = built.lastOrNull()
            if (line != null && used + gap + w <= inner) {
                line += index
                used += gap + w
            } else {
                built += mutableListOf(index)
                used = w
            }
        }
        lines = built
        var height = paddingTop + paddingBottom
        lines.forEachIndexed { row, line ->
            // Each line fills the track, the spare room shared out evenly, as flex: 1 1 auto shares it.
            val spare = (inner - line.sumOf { natural[it] } - gap * (line.size - 1)).coerceAtLeast(0)
            var tallest = 0
            line.forEachIndexed { i, index ->
                val extra = spare / line.size + if (i < spare % line.size) 1 else 0
                // A single option wider than the track wraps its own text rather than running out of it.
                val w = minOf(natural[index] + extra, inner)
                buttons[index].measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
                tallest = maxOf(tallest, buttons[index].measuredHeight)
            }
            line.forEach { index ->
                buttons[index].measure(
                    MeasureSpec.makeMeasureSpec(buttons[index].measuredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(tallest, MeasureSpec.EXACTLY),
                )
            }
            height += tallest + if (row > 0) gap else 0
        }
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = paddingTop
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        lines.forEach { line ->
            var x = if (rtl) width - paddingRight else paddingLeft
            var tallest = 0
            line.forEach { index ->
                val button = buttons[index]
                val w = button.measuredWidth
                if (rtl) {
                    button.layout(x - w, y, x, y + button.measuredHeight)
                    x -= w + gap
                } else {
                    button.layout(x, y, x + w, y + button.measuredHeight)
                    x += w + gap
                }
                tallest = maxOf(tallest, button.measuredHeight)
            }
            y += tallest + gap
        }
    }

    /** How many lines the options take, for the test that they wrap rather than truncate. */
    internal val lineCount: Int get() = lines.size

    internal fun option(index: Int): TextView = buttons[index]
}
