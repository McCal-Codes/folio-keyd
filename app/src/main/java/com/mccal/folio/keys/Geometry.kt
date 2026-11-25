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

    /**
     * How a window shares its width between keys and a gap, in dp.
     *
     * [board] is how wide the keys' board is, [gutter] is the gap between a split board's halves (the gap between two
     * ordinary keys when not split), and [opened] says how far the board has pulled apart, 0 to 1. All three change
     * smoothly with the window, so dragging a window wider or taller never makes the keys jump.
     */
    data class Spread(val board: Float, val gutter: Float, val opened: Float) {
        /** Whether the halves are laid out apart at all; at the very start of opening they are one gap wide. */
        val split: Boolean get() = opened > 0f
    }

    /** Up to this wide, the board is the whole window. Phones, and the Fold's cover screen. */
    const val FILL_DP = 440f

    /** Past [FILL_DP] an unsplit board eases towards [FILL_DP] plus this, and never further: keys stay the size hands expect. */
    const val EASE_DP = 40f

    /** A split starts to pull apart at this window width, and is fully apart [OPEN_OVER_DP] later. */
    const val OPEN_AT_DP = 600f
    const val OPEN_OVER_DP = 160f

    /** Window heights between these open the split gradually: a window that is wide but short is a phone on its side. */
    const val TALL_FROM_DP = 480f
    const val TALL_OVER_DP = 160f

    /** A fully split board is never wider than this, or its gap wider than [GUTTER_MAX_DP], so its keys stop growing too. */
    const val SPLIT_BOARD_MAX_DP = 772f
    const val GUTTER_MAX_DP = 220f
    const val GUTTER_SHARE = 0.19f

    /** Eases from 0 to 1 over [0, 1] with no corner at either end. */
    fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /**
     * An unsplit board in a window with [usableDp] to spend: all of it up to [FILL_DP], then easing towards a ceiling
     * rather than stopping dead, so there is no width at which the keys quit growing all at once.
     */
    fun eased(usableDp: Float): Float =
        if (usableDp <= FILL_DP) usableDp else FILL_DP + EASE_DP * (1 - kotlin.math.exp(-(usableDp - FILL_DP) / EASE_DP))

    /**
     * What the window gives the board.
     *
     * [usableDp] is the width between the keyboard's edges; [windowWidthDp] and [windowHeightDp] are the window's
     * own size, which are what decide how far a split opens. [allowSplit] and [ignoreHeight] carry the person's
     * choice: never splitting, and splitting even in a short window.
     *
     * Everything here is continuous: the key size a split starts with is the size the unsplit board had just before
     * it, and the gap grows from one ordinary gap. The only thing that is not continuous is which keys sit where
     * once a board splits, which no number can smooth.
     */
    fun spread(
        usableDp: Float,
        windowWidthDp: Float,
        windowHeightDp: Float,
        allowSplit: Boolean = true,
        ignoreHeight: Boolean = false,
    ): Spread {
        val tall = if (ignoreHeight) 1f else smooth((windowHeightDp - TALL_FROM_DP) / TALL_OVER_DP)
        // A short window fills its width, however wide. Only a tall one is held to what a hand can reach.
        val unsplit = usableDp + (eased(usableDp) - usableDp) * (if (allowSplit) tall else 1f)
        val opened = if (allowSplit) smooth((windowWidthDp - OPEN_AT_DP) / OPEN_OVER_DP) * tall else 0f
        if (opened <= 0f) return Spread(unsplit, GAP_X_DP, 0f)
        val board = unsplit + (min(usableDp, SPLIT_BOARD_MAX_DP) - unsplit) * opened
        val gutter = GAP_X_DP + (min(GUTTER_MAX_DP, GUTTER_SHARE * board) - GAP_X_DP) * opened
        return Spread(board, gutter, opened)
    }

    const val ONE_HANDED_MAX_DP = 360f
    const val ONE_HANDED_MIN_DP = 280f
    const val RAIL_DP = 56f
    const val RAIL_MIN_DP = 48f

    /**
     * How wide a one-handed keyboard is in a window [availableDp] across, or null when it does not fit.
     *
     * About a thumb's reach, and never so narrow the letters stop being letters. The rail beside it needs a
     * fingertip of its own; a window too narrow for both gets the ordinary keyboard, which is already one-handed
     * at that size.
     */
    fun oneHandedDp(availableDp: Float): Float? {
        val keys = max(ONE_HANDED_MIN_DP, min(ONE_HANDED_MAX_DP, availableDp - RAIL_DP))
        return if (availableDp - keys >= RAIL_MIN_DP) keys else null
    }

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
        /**
         * For a split half: every row uses the top row's key size instead of stretching to fill, so the two halves'
         * letters are the same size. A row with a space bar gives its spare room to the space bar; any other short
         * row leaves it on the crease side ([alignEnd] for the right half), so the outer edges stay flush.
         */
        evenKeys: Boolean = false,
        alignEnd: Boolean = false,
        /** With [evenKeys]: a short row sits in the middle, the way the second row of a phone keyboard does. */
        centre: Boolean = false,
    ): List<Placement> {
        if (rows.isEmpty() || width <= 0 || height <= 0) return emptyList()
        val gapX = GAP_X_DP * density
        val gapY = GAP_Y_DP * density
        val left = if (fillWidth > 0f) startX else SIDE_PAD_DP * density + sideInset
        val usable = if (fillWidth > 0f) fillWidth else width - 2 * left
        if (usable <= 0) return emptyList()
        val rowH = (height - top - bottomInset - 2 * gapY - (rows.size - 1) * gapY) / rows.size
        val out = ArrayList<Placement>()
        // The top row sets the key size for the whole half: it is always the fullest row of letters.
        val first = rows.first()
        val shared = (usable - gapX * (first.size - 1)) / first.sumOf { it.weight.toDouble() }.toFloat()
        var y = top + gapY
        for (row in rows) {
            val weights = row.sumOf { it.weight.toDouble() }.toFloat()
            val natural = shared * weights + gapX * (row.size - 1)
            // Stretching is right for a full keyboard, and for any half-row that would not fit at the shared size.
            val stretch = !evenKeys
            val tight = evenKeys && natural > usable + 0.5f
            val unit = if (stretch) (usable - gapX * (row.size - 1)) / weights else shared
            // A half-row too wide for the shared size (shift, 123 and the globe add up) keeps its letters the same
            // size as every other letter and squeezes only the keys that aren't letters.
            val letters = row.filter { it.kind == KeyKind.CHAR }.sumOf { it.weight.toDouble() }.toFloat()
            val others = weights - letters
            val otherUnit = if (tight && others > 0f) (usable - gapX * (row.size - 1) - shared * letters) / others else unit
            val spare = if (stretch || tight) 0f else usable - natural
            val spaces = row.count { it.kind == KeyKind.SPACE }
            var x = left + when {
                spaces > 0 -> 0f
                alignEnd -> spare
                centre -> spare / 2
                else -> 0f
            }
            for (key in row) {
                var w = if (tight && key.kind != KeyKind.CHAR) otherUnit * key.weight else unit * key.weight
                if (spaces > 0 && key.kind == KeyKind.SPACE) w += spare / spaces
                out += Placement(key, Box(x, y, x + w, y + rowH))
                x += w + gapX
            }
            y += rowH + gapY
        }
        return out
    }
}
