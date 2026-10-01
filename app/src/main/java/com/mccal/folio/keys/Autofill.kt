package com.mccal.folio.keys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Password manager suggestions in the strip: a login, a one-time code, "Passwords…".
 *
 * Android 11 lets a keyboard show what the autofill service offers for a field in its own strip, which is where
 * Gboard and Samsung show them. The keyboard asks with a request that says how big each chip may be and how it should
 * look; the password manager draws each chip itself, in its own process, and the keyboard is handed views it can place
 * but not look inside. That is the whole privacy story, and it is why nothing here reads a chip: there is nothing to
 * read, only a window onto someone else's drawing.
 */
internal object Autofill {

    /** More than fit is fine: the strip shows what fits and keeps the pinned ones. More than this is a list, not a strip. */
    const val MAX_CHIPS = 6

    /** Shorter than the strip, so a chip reads as a thing to tap rather than a band across the keyboard. */
    const val CHIP_HEIGHT_DP = 34f

    /** Narrow enough for a pinned key icon on its own, wide enough that nothing smaller is a target. */
    const val CHIP_MIN_WIDTH_DP = 48f

    fun chipHeight(dp: Float): Int = (CHIP_HEIGHT_DP * dp).roundToInt()

    /**
     * The request, or null when the setting is off, which is Android's way of saying "no chips here". [widest] is the
     * widest a chip may be, the width of the strip or of the wider half of a split one.
     */
    fun request(context: Context, on: Boolean, widest: Int, theme: Theme): InlineSuggestionsRequest? {
        if (!on) return null
        val dp = context.resources.displayMetrics.density
        val height = chipHeight(dp)
        val spec = InlinePresentationSpec.Builder(
            Size((CHIP_MIN_WIDTH_DP * dp).roundToInt(), height),
            Size(max(widest, (CHIP_MIN_WIDTH_DP * dp).roundToInt()), height),
        ).setStyle(style(context, theme)).build()
        // One spec: Android uses the last for every chip after the ones listed, and every chip here is the same.
        return InlineSuggestionsRequest.Builder(listOf(spec)).setMaxSuggestionCount(MAX_CHIPS).build()
    }

    /**
     * How the chips should look, in the androidx format every password manager reads. A manager that does not
     * understand it draws its own default, which is why it is only a style and never the content.
     */
    // The ViewStyle.Builder setters are public API; lint flags them from a stale library annotation.
    @SuppressLint("RestrictedApi")
    private fun style(context: Context, theme: Theme): Bundle {
        val dp = context.resources.displayMetrics.density
        val pad = (12 * dp).roundToInt()
        val chip = ViewStyle.Builder()
            .setBackground(Icon.createWithResource(context, R.drawable.autofill_chip).setTint(theme.key))
            .setPadding(pad, 0, pad, 0)
            .setLayoutMargin((3 * dp).roundToInt(), 0, (3 * dp).roundToInt(), 0)
            .build()
        val title = TextViewStyle.Builder().setTextColor(theme.label).setTextSize(15f).build()
        val subtitle = TextViewStyle.Builder().setTextColor(theme.hint).setTextSize(12f).build()
        val ui = InlineSuggestionUi.newStyleBuilder()
            .setChipStyle(chip)
            .setTitleStyle(title)
            .setSubtitleStyle(subtitle)
            .build()
        return UiVersions.newStylesBuilder().addStyle(ui).build()
    }
}

/**
 * The views the password manager drew, laid over the strip.
 *
 * The strip itself is drawn by [KeyboardView], and a drawing cannot hold another app's view, so the chips live in
 * this row above it instead, placed in the same [lanes] the strip's own buttons use: one across the keys, or one per
 * half of a split keyboard, so no chip sits on the gap in the middle, which on an unfolded Fold is the crease.
 *
 * Chips go in the order they came, the pinned ones last and at the far end, as Gboard puts them. On a split
 * keyboard the chips are shared between the halves, as the strip's words are. A chip that does not fit is left out
 * rather than squeezed; a pinned one is kept before the others.
 *
 * Not clickable itself, so a touch between chips goes on to the keyboard underneath.
 */
internal class AutofillStrip(context: Context) : ViewGroup(context) {

    /** A chip, and whether its service asked for it to be pinned at the end. */
    class Chip(val view: View, val pinned: Boolean)

    /** Left and right edges, in this view's pixels, of each part of the strip a chip may go in. */
    var lanes: List<Pair<Int, Int>> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            place()
        }

    /** Where the strip starts, in this view's pixels: the board's padding sits above it, and the chips do not. */
    var stripTop: Int = 0
        set(value) {
            if (field == value) return
            field = value
            place()
        }

    var chips: List<Chip> = emptyList()
        private set

    val showing: Boolean get() = chips.isNotEmpty()

    private val chipHeight = Autofill.chipHeight(resources.displayMetrics.density)
    private val gap = (GAP_DP * resources.displayMetrics.density).roundToInt()

    /** Replaces whatever was showing. Unpinned first in their order, then the pinned ones, whatever order they came in. */
    fun show(given: List<Chip>) {
        removeAllViews()
        chips = given.filter { !it.pinned } + given.filter { it.pinned }
        for (chip in chips) addView(chip.view)
        requestLayout()
        invalidate()
    }

    fun clear() {
        if (chips.isEmpty()) return
        chips = emptyList()
        removeAllViews()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widest = lanes.maxOfOrNull { it.second - it.first } ?: MeasureSpec.getSize(widthMeasureSpec)
        for (chip in chips) {
            val params = chip.view.layoutParams
            val width = if (params != null && params.width > 0) {
                MeasureSpec.makeMeasureSpec(min(params.width, widest), MeasureSpec.EXACTLY)
            } else {
                MeasureSpec.makeMeasureSpec(max(widest, 0), MeasureSpec.AT_MOST)
            }
            chip.view.measure(width, MeasureSpec.makeMeasureSpec(chipHeight, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = place()

    /**
     * Puts each chip in its lane. Also run when the lanes change, which is during the frame's layout, after this
     * view's own layout has already been and gone.
     */
    private fun place() {
        val top = stripTop + (height - stripTop - chipHeight) / 2
        val placed = arrange(chips.map { it.view.measuredWidth }, chips.map { it.pinned }, lanes, gap)
        for ((index, chip) in chips.withIndex()) {
            val left = placed[index]
            if (left == null) {
                // Laid out at nothing rather than made GONE: its visibility is the manager's surface's to manage.
                chip.view.layout(0, 0, 0, 0)
            } else {
                chip.view.layout(left, top, left + chip.view.measuredWidth, top + chipHeight)
            }
        }
    }

    companion object {
        /** The gap between chips, which is also what keeps the first off the rounded edge of the board. */
        const val GAP_DP = 4f

        /**
         * Where each chip's left edge goes, or null for one left out. [pinned] ones are right-aligned at the end of
         * their lane. Split between lanes as evenly as the strip's words are, the first lane taking the odd one.
         */
        fun arrange(widths: List<Int>, pinned: List<Boolean>, lanes: List<Pair<Int, Int>>, gap: Int = 0): List<Int?> {
            val out = MutableList<Int?>(widths.size) { null }
            if (lanes.isEmpty() || widths.isEmpty()) return out
            val per = (widths.size + lanes.size - 1) / lanes.size
            for ((laneIndex, lane) in lanes.withIndex()) {
                val members = (laneIndex * per until min(widths.size, (laneIndex + 1) * per)).toList()
                var right = lane.second
                // Pinned first, from the end, so they are the ones that stay when the lane is full.
                for (index in members.filter { pinned[it] }.reversed()) {
                    if (right - widths[index] < lane.first) continue
                    right -= widths[index]
                    out[index] = right
                    right -= gap
                }
                var left = lane.first
                for (index in members.filter { !pinned[it] }) {
                    if (left + widths[index] > right) break
                    out[index] = left
                    left += widths[index] + gap
                }
            }
            return out
        }
    }
}
