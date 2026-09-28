package com.mccal.folio.keys

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * The keyboard's window: the letters, and the panels that open in their place, stacked with one showing at a time.
 *
 * A frame of its own only so the panels follow the letters when they are one-handed. Every panel lays itself out
 * across whatever width it is given, so rather than teach each of them about one-handed, each is given the width of
 * the letters' board and put where that board is. The rail stays with the letters: beside a panel its strip is left
 * empty, and the way back to it is the panel's own letters key.
 */
internal class KeyboardFrame(context: Context, private val keys: KeyboardView) : FrameLayout(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val (left, right) = keys.boardSpan(measuredWidth)
        if (right - left == measuredWidth) return
        for (panel in panels()) panel.measure(exactly(right - left), exactly(panel.measuredHeight))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val (start, end) = keys.boardSpan(right - left)
        if (end - start == right - left) return
        for (panel in panels()) panel.layout(start, panel.top, start + panel.measuredWidth, panel.top + panel.measuredHeight)
    }

    private fun panels(): List<View> =
        (0 until childCount).map(::getChildAt).filter { it !== keys && it.visibility != View.GONE }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
}
