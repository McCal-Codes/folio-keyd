package com.mccal.folio.keys

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * The keyboard's window: the letters, and the panels that open in their place, stacked with one showing at a time.
 *
 * Every panel is given the letters' height, whether or not the letters are showing. Each panel can work a height out
 * for itself, but only for the default size and four rows; with Large, or the number row on, opening the emoji grid
 * used to shrink the keyboard and the app above jumped down and back up again.
 *
 * A frame of its own only so the panels follow the letters when they are one-handed. Every panel lays itself out
 * across whatever width it is given, so rather than teach each of them about one-handed, each is given the width of
 * the letters' board and put where that board is. The rail stays with the letters: beside a panel its strip is left
 * empty, and the way back to it is the panel's own letters key.
 */
internal class KeyboardFrame(context: Context, private val keys: KeyboardView) : FrameLayout(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Hidden, the letters are skipped by FrameLayout, so they are measured here: their height is the panels' too.
        if (keys.visibility == View.GONE) {
            val params = keys.layoutParams
            keys.measure(
                getChildMeasureSpec(widthMeasureSpec, paddingLeft + paddingRight, params.width),
                getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom, params.height),
            )
        }
        val height = keys.measuredHeight
        val (left, right) = keys.boardSpan(measuredWidth)
        for (panel in panels()) panel.measure(exactly(right - left), exactly(height))
        setMeasuredDimension(measuredWidth, resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec))
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
