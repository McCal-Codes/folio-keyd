package com.mccal.folio.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keys, at every window a phone can give us.
 *
 * A keyboard fails in ways you only notice once it's on the screen: keys overlapping, a row running off the edge, a
 * keyboard eating the app above it, targets too small to hit. Those are all arithmetic, so they're checked here
 * against the whole matrix instead of on one phone.
 */
class GeometryTest {

    /** Width and height in dp, and the density the screen draws at: covers small phones through an unfolded Fold. */
    private val windows = listOf(
        Triple(320f, 640f, 2f),      // a small phone
        Triple(360f, 640f, 3f),      // the common phone
        Triple(411f, 891f, 2.625f),  // a tall phone
        Triple(475f, 751f, 2.75f),   // Fold8 cover
        Triple(932f, 704f, 2.5f),    // Fold8 inner, unfolded
        Triple(751f, 475f, 2.75f),   // the cover, on its side
        Triple(600f, 360f, 2f),      // a short landscape window
        Triple(320f, 300f, 2f),      // absurdly short: split screen on a small phone
    )

    private fun layout(widthDp: Float, heightDp: Float, density: Float, rows: List<Row>, bottomInset: Float = 0f) =
        Geometry.place(
            rows,
            (widthDp * density).toInt(),
            Geometry.height(rows.size, heightDp, density, bottomInset),
            density,
            bottomInset = bottomInset,
        )

    private val layouts: List<List<Row>> = listOf(
        Layouts.rows(Layer.LETTERS, false, FieldRules()),
        Layouts.rows(Layer.NUMBERS, false, FieldRules()),
        Layouts.rows(Layer.SYMBOLS, false, FieldRules()),
        Layouts.rows(Layer.LETTERS, false, FieldRules(kind = FieldKind.EMAIL)),
        Layouts.rows(Layer.LETTERS, false, FieldRules(kind = FieldKind.NUMBER)),
    )

    @Test
    fun `no two keys ever overlap`() {
        for ((w, h, d) in windows) for (rows in layouts) {
            val placed = layout(w, h, d, rows)
            for (i in placed.indices) for (j in i + 1 until placed.size) {
                assertTrue(
                    "${placed[i].key.label} overlaps ${placed[j].key.label} at ${w}x$h",
                    !placed[i].box.overlaps(placed[j].box),
                )
            }
        }
    }

    @Test
    fun `every key stays inside the keyboard`() {
        for ((w, h, d) in windows) for (rows in layouts) {
            val widthPx = (w * d).toInt()
            val heightPx = Geometry.height(rows.size, h, d, 0f)
            for (placement in layout(w, h, d, rows)) {
                val box = placement.box
                assertTrue("${placement.key.label} runs off the left at ${w}x$h", box.left >= -0.5f)
                assertTrue("${placement.key.label} runs off the right at ${w}x$h", box.right <= widthPx + 0.5f)
                assertTrue("${placement.key.label} runs off the bottom at ${w}x$h", box.bottom <= heightPx + 0.5f)
            }
        }
    }

    @Test
    fun `a row fills the width it was given`() {
        for ((w, h, d) in windows) for (rows in layouts) {
            val placed = layout(w, h, d, rows)
            val byRow = placed.groupBy { it.box.top }
            for ((_, row) in byRow) {
                val left = row.minOf { it.box.left }
                val right = row.maxOf { it.box.right }
                val expected = (w * d) - 2 * (Geometry.SIDE_PAD_DP * d)
                assertEquals("row is not flush at ${w}x$h", expected.toDouble(), (right - left).toDouble(), 1.0)
            }
        }
    }

    @Test
    fun `no key is too small to hit`() {
        // WCAG 2.5.8 puts the floor at 24 dp. Keys are allowed to be smaller than Android's 48 dp guidance - every
        // phone keyboard is - but never smaller than a finger can find.
        for ((w, h, d) in windows) for (rows in layouts) {
            for (placement in layout(w, h, d, rows)) {
                val box = placement.box
                assertTrue(
                    "${placement.key.label} is ${box.width / d}x${box.height / d} dp at ${w}x$h",
                    box.width / d >= 24f && box.height / d >= 24f,
                )
            }
        }
    }

    @Test
    fun `the keyboard leaves the app room to exist`() {
        for ((_, h, d) in windows) for (rows in layouts) {
            val heightDp = Geometry.height(rows.size, h, d, 0f) / d
            val share = heightDp / h
            // Android's guidance is about half the window; a very short window is allowed a little more, and a tiny
            // one is where the IME goes fullscreen instead, which is the service's decision, not the geometry's.
            if (h >= 400) {
                assertTrue("keyboard takes ${(share * 100).toInt()}% of a ${h}dp window", share <= 0.62f)
            }
        }
    }

    /**
     * The window a phone has on its side is the one with least to spare.
     *
     * It used to be allowed a bigger share of itself than a tall window, so in landscape the keyboard took two
     * thirds of the screen and the field being typed into ended up behind it.
     */
    @Test
    fun `a short window does not give the keyboard a bigger share than a tall one`() {
        val density = 2.75f
        val rows = Layouts.rows(Layer.LETTERS, false, FieldRules())
        fun share(heightDp: Float): Float {
            val extra = 48 * density   // the toolbar and the panel's own margin
            return Geometry.height(rows.size, heightDp, density, 0f, extra) / (heightDp * density)
        }
        // Comparing the two shares directly would be the wrong test: the toolbar, the gaps and the band under the
        // keys cost the same number of pixels whatever the window, so they are always a bigger slice of a small
        // one. What matters is that the app is left something worth looking at.
        // Windows shorter than this cannot be divided usefully at all, and the service goes fullscreen
        // instead, which is a different answer to the same problem.
        for (heightDp in listOf(411f, 454f, 475f, 520f)) {
            val taken = share(heightDp)
            assertTrue(
                "a ${heightDp.toInt()} dp window gives the keyboard ${(taken * 100).toInt()}%",
                taken <= 0.58f,
            )
            val leftForTheApp = heightDp * (1 - taken)
            assertTrue(
                "only ${leftForTheApp.toInt()} dp left of a ${heightDp.toInt()} dp window",
                leftForTheApp >= 150f,
            )
        }
    }

    @Test
    fun `a taller layout does not make taller keys`() {
        val (w, h, d) = windows[1]
        val four = layout(w, h, d, Layouts.rows(Layer.LETTERS, false, FieldRules()))
        val five = layout(w, h, d, Layouts.rows(Layer.LETTERS, false, FieldRules(kind = FieldKind.NUMBER)))
        assertTrue(five.first().box.height <= four.first().box.height + 0.5f)
    }

    @Test
    fun `the gesture bar is left alone`() {
        val (w, h, d) = windows[1]
        val inset = 24 * d
        val rows = Layouts.rows(Layer.LETTERS, false, FieldRules())
        val height = Geometry.height(rows.size, h, d, inset)
        val placed = Geometry.place(rows, (w * d).toInt(), height, d, bottomInset = inset)
        val lowest = placed.maxOf { it.box.bottom }
        assertTrue("keys reach into the gesture bar", lowest <= height - inset + 0.5f)
    }

    /** A split keyboard is two keyboards: neither half may wander into the other's side of the gap. */
    @Test
    fun `the halves of a split keyboard keep to their own side`() {
        val density = 2.5f
        val widthPx = (932 * density).toInt()
        val rows = Layouts.rows(Layer.LETTERS, false, FieldRules())
        val (left, right) = Layouts.split(rows)
        val gutter = 120 * density
        val half = (widthPx - 2 * Geometry.SIDE_PAD_DP * density - gutter) / 2
        val start = Geometry.SIDE_PAD_DP * density
        val height = Geometry.height(rows.size, 704f, density, 0f)
        val placedLeft = Geometry.place(left, widthPx, height, density, startX = start, fillWidth = half)
        val placedRight = Geometry.place(right, widthPx, height, density, startX = start + half + gutter, fillWidth = half)

        assertTrue(placedLeft.isNotEmpty() && placedRight.isNotEmpty())
        assertTrue("a left key crosses the gap", placedLeft.all { it.box.right <= start + half + 0.5f })
        assertTrue("a right key crosses the gap", placedRight.all { it.box.left >= start + half + gutter - 0.5f })
        for (placement in placedLeft + placedRight) {
            assertTrue(
                "${placement.key.label} is ${placement.box.width / density} dp wide",
                placement.box.width / density >= 24f,
            )
        }
    }

    @Test
    fun `splitting keeps every key, and gives each half a space bar`() {
        val rows = Layouts.rows(Layer.LETTERS, false, FieldRules())
        val (left, right) = Layouts.split(rows)
        val before = rows.flatten().filter { it.kind != KeyKind.SPACE }.map { it.label }
        val after = (left + right).flatten().filter { it.kind != KeyKind.SPACE }.map { it.label }
        assertEquals(before.sorted(), after.sorted())
        assertEquals("each thumb needs its own space bar", 1, left.flatten().count { it.kind == KeyKind.SPACE })
        assertEquals(1, right.flatten().count { it.kind == KeyKind.SPACE })
    }

    /** The band under the keys: buttons bring their own room, gestures don't, and Folio's Big Buttons sit on top. */
    @Test
    fun `the room under the keys suits what is down there`() {
        val d = 3f
        val gestureInset = 24 * d
        val buttonInset = 48 * d

        val gestures = BottomRoom.band(BottomRoom.NAV_GESTURE, gestureInset, 0f, d)
        assertTrue("gestures should keep a band of their own", gestures >= BottomRoom.GESTURE_BAND_DP * d)

        val buttons = BottomRoom.band(BottomRoom.NAV_THREE_BUTTON, buttonInset, 0f, d)
        assertEquals("buttons already leave room; don't add to it", buttonInset.toDouble(), buttons.toDouble(), 0.5)

        val withBig = BottomRoom.band(BottomRoom.NAV_GESTURE, gestureInset, 56f, d)
        assertEquals(
            "Folio's Big Buttons sit above the navigation, so their height adds",
            (gestures + 56 * d).toDouble(), withBig.toDouble(), 0.5,
        )
    }

    /** Whatever is under the keys, the keys stay above it and the rows stay out of each other's way. */
    @Test
    fun `nothing overlaps the band, whatever is down there`() {
        val rows = Layouts.rows(Layer.LETTERS, false, FieldRules())
        for ((w, h, d) in windows) {
            for (mode in listOf(BottomRoom.NAV_GESTURE, BottomRoom.NAV_THREE_BUTTON)) {
                for (big in listOf(0f, 56f)) {
                    val band = BottomRoom.band(mode, if (mode == BottomRoom.NAV_GESTURE) 24 * d else 48 * d, big, d)
                    val height = Geometry.height(rows.size, h, d, band)
                    val placed = Geometry.place(rows, (w * d).toInt(), height, d, bottomInset = band)
                    val lowest = placed.maxOf { it.box.bottom }
                    assertTrue(
                        "keys reach into the band at ${w}x$h, mode $mode, big $big",
                        lowest <= height - band + 0.5f,
                    )
                    for (i in placed.indices) for (j in i + 1 until placed.size) {
                        assertTrue(
                            "${placed[i].key.label} overlaps ${placed[j].key.label}",
                            !placed[i].box.overlaps(placed[j].box),
                        )
                    }
                }
            }
        }
    }

    /** The number row is an extra row of keys, and everything still has to fit and stay big enough to hit. */
    @Test
    fun `a number row still fits at every window size`() {
        val rows = Layouts.rows(Layer.LETTERS, false, FieldRules(), numberRow = true)
        assertEquals("a digits row should have been added", 5, rows.size)
        assertEquals("1234567890", rows.first().joinToString("") { it.label })
        for ((w, h, d) in windows) {
            val placed = layout(w, h, d, rows)
            for (placement in placed) {
                assertTrue(
                    "${placement.key.label} is ${placement.box.width / d} dp at ${w}x$h",
                    placement.box.width / d >= 24f && placement.box.height / d >= 24f,
                )
            }
            for (i in placed.indices) for (j in i + 1 until placed.size) {
                assertTrue(
                    "${placed[i].key.label} overlaps ${placed[j].key.label} at ${w}x$h",
                    !placed[i].box.overlaps(placed[j].box),
                )
            }
        }
    }

    @Test
    fun `nothing is placed when there is nowhere to place it`() {
        val rows = Layouts.rows(Layer.LETTERS, false, FieldRules())
        assertTrue(Geometry.place(rows, 0, 500, 2f).isEmpty())
        assertTrue(Geometry.place(emptyList(), 800, 500, 2f).isEmpty())
    }
}
