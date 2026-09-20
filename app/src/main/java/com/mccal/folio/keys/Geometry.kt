package com.mccal.folio.keys

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A key's place on the keyboard, in pixels. Plain numbers, so the arithmetic can be tested without a phone. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
    /**
     * How far a point lies outside this box, and zero inside it.
     *
     * Keys are drawn with a gap between them, and a finger landing in that gap belongs to the nearest key rather
     * than to nobody: a keyboard that only answers [contains] drops a letter every time someone hits a seam.
     */
    fun distanceTo(x: Float, y: Float): Float =
        hypot(max(max(left - x, 0f), x - right), max(max(top - y, 0f), y - bottom))

    fun overlaps(other: Box): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

data class Placement(val key: Key, val box: Box)

/**
 * Where the keys go, and how tall the keyboard is.
 *
 * This is the part that decides whether keys overlap, run off the edge or end up too small to hit, so it is kept clear
 * of the View: [GeometryTest] checks it at every window size the phone can be, which is cheaper and more thorough than
 * noticing on a phone.
 */
object Geometry {
    const val GAP_X_DP = 4f
    const val GAP_Y_DP = 8f
    const val SIDE_PAD_DP = 3f
    const val MIN_ROW_DP = 25f
    const val MAX_ROW_DP = 52f

    /** Android's guidance: a keyboard that isn't fullscreen shouldn't take much more than half the window. */
    fun height(
        rowCount: Int,
        windowHeightDp: Float,
        density: Float,
        bottomInset: Float,
        extra: Float = 0f,
        /** What the person chose: a hand that wants a taller or shorter keyboard than the default. */
        share: Float = SHARE,
    ): Int {
        val rows = max(rowCount, 1)
        val cap = capPx(windowHeightDp, density, share)
        // The ceiling on a row has to move with the choice too. On a tall phone the rows are already as tall as
        // they are allowed to get, so raising only the share changed nothing at all and "Tall" did nothing.
        val row = rowHeight(cap, rows, density, bottomInset, MAX_ROW_DP * (share / SHARE))
        val gapY = GAP_Y_DP * density
        return (rows * row + (rows - 1) * gapY + 2 * gapY + bottomInset + extra).toInt()
    }

    /**
     * The most of the window the keys may take.
     *
     * The same share whatever the window. A short window used to be allowed *more* of itself than a tall one, which
     * is backwards: a phone on its side has the least room to spare, so taking two thirds of it left the field
     * being typed into hidden behind the keyboard - the one thing a keyboard must never do.
     */
    fun capPx(windowHeightDp: Float, density: Float, share: Float = SHARE): Float =
        min(windowHeightDp * density * share, MAX_HEIGHT_DP * density * (share / SHARE))

    const val SHARE = 0.46f
    const val MAX_HEIGHT_DP = 360f

    /** [maxRow] moves with the size someone chose; the floor never does, because it is what a finger needs. */
    fun rowHeight(
        cap: Float,
        rowCount: Int,
        density: Float,
        bottomInset: Float,
        maxRow: Float = MAX_ROW_DP,
    ): Float {
        val gapY = GAP_Y_DP * density
        val room = cap - 2 * gapY - (rowCount - 1) * gapY - bottomInset
        return max(MIN_ROW_DP * density, min(maxRow * density, room / rowCount))
    }

    /**
     * Lays the rows into the space the view was given. Each row spends the width on its keys in proportion to their
     * weight, so a row of ten letters and a row with a space bar both end flush with the edges.
     */
    fun place(
        rows: List<Row>,
        width: Int,
        height: Int,
        density: Float,
        sideInset: Float = 0f,
        bottomInset: Float = 0f,
        /** Room taken above the keys, by a toolbar or a suggestion strip. */
        top: Float = 0f,
        /** The left edge of the area to fill, for one half of a split keyboard. */
        startX: Float = 0f,
        /** The width to fill, when it isn't the whole view: a split half, or a capped width on a wide window. */
        fillWidth: Float = 0f,
    ): List<Placement> {
        if (rows.isEmpty() || width <= 0 || height <= 0) return emptyList()
        val gapX = GAP_X_DP * density
        val gapY = GAP_Y_DP * density
        val left = if (fillWidth > 0f) startX else SIDE_PAD_DP * density + sideInset
        val usable = if (fillWidth > 0f) fillWidth else width - 2 * left
        if (usable <= 0) return emptyList()
        val rowH = (height - top - bottomInset - 2 * gapY - (rows.size - 1) * gapY) / rows.size
        val out = ArrayList<Placement>()
        var y = top + gapY
        for (row in rows) {
            val weights = row.sumOf { it.weight.toDouble() }.toFloat()
            val unit = (usable - gapX * (row.size - 1)) / weights
            var x = left
            for (key in row) {
                val w = unit * key.weight
                out += Placement(key, Box(x, y, x + w, y + rowH))
                x += w + gapX
            }
            y += rowH + gapY
        }
        return out
    }
}
