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

    @Test
    fun `nothing is placed when there is nowhere to place it`() {
        val rows = Layouts.rows(Layer.LETTERS, false, FieldRules())
        assertTrue(Geometry.place(rows, 0, 500, 2f).isEmpty())
        assertTrue(Geometry.place(emptyList(), 800, 500, 2f).isEmpty())
    }
}
