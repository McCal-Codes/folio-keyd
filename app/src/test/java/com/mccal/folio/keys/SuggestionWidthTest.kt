package com.mccal.folio.keys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The strip offers as many words as its width has room for: four on a phone or a cover screen, five unfolded.
 *
 * What goes wrong is a count picked per device instead of by width, words squeezed below a fingertip, or on a split
 * keyboard a word lying across the gap between the halves where no thumb reaches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w932dp-h701dp-land-xhdpi")
class SuggestionWidthTest {

    private val five = listOf("Hel", "Hello", "Help", "Held", "Helmet")

    private fun strip(widthDp: Int, split: Split = Split.NEVER): Touches {
        val touches = Touches(widthDp)
        touches.show(settings = Settings(split = split), widthDp = widthDp)
        touches.view.voiceAvailable = true
        touches.view.suggestions = five
        return touches
    }

    private fun Touches.words() = view.toolbarPlacements.filter { it.key.kind == KeyKind.SUGGESTION }

    @Test
    fun `a narrow phone's strip offers four words, as it always has`() {
        for (widthDp in listOf(330, 360)) {
            val touches = strip(widthDp)
            assertEquals("at $widthDp dp", listOf("Hel", "Hello", "Help", "Held"), touches.words().map { it.key.label })
            assertEquals(KeyKind.VOICE, touches.view.toolbarPlacements.last().key.kind)
        }
    }

    @Test
    fun `an unfolded strip offers five, each wider than a fingertip, and the mic stays last`() {
        for (widthDp in listOf(800, 932)) {
            val touches = strip(widthDp)
            val words = touches.words()
            assertEquals("at $widthDp dp", five, words.map { it.key.label })
            assertTrue(words.all { it.box.width / touches.density >= 100f })
            assertEquals(KeyKind.VOICE, touches.view.toolbarPlacements.last().key.kind)
        }
    }

    @Test
    fun `in between it grows one word at a time`() {
        assertEquals(4, strip(360).view.wordSlots())
        assertEquals("a Fold8 cover screen keeps the four it had", 4, strip(475).view.wordSlots())
        assertEquals(5, strip(560).view.wordSlots())
    }

    @Test
    fun `fewer suggestions than room fill the strip rather than leaving gaps`() {
        val touches = strip(932)
        touches.view.suggestions = listOf("Hel", "Hello")
        val words = touches.words()
        assertEquals(2, words.size)
        assertTrue(words.all { it.box.width / touches.density > 300f })
    }

    @Test
    fun `the fifth word is there to be tapped`() {
        val touches = strip(932)
        touches.tap(touches.words()[4].box)
        assertEquals(listOf("suggestion"), touches.heard)
    }

    @Test
    fun `on a split keyboard the words stay either side of the gap, the emoji and mic with the right half`() {
        val touches = strip(932, Split.ALWAYS)
        touches.view.suggestedEmoji = SuggestedEmoji("👋", "waving hand")
        val middle = touches.view.width / 2f
        val letters = touches.view.placements.filter { it.key.kind == KeyKind.CHAR }
        val gapLeft = letters.filter { it.box.right <= middle }.maxOf { it.box.right }
        val gapRight = letters.filter { it.box.left >= middle }.minOf { it.box.left }
        assertTrue("the keys are split", gapRight - gapLeft > 40 * touches.density)
        val words = touches.words()
        assertEquals(five, words.map { it.key.label })
        for (word in words) {
            assertTrue("${word.key.label} crosses the gap", word.box.right <= gapLeft + 0.5f || word.box.left >= gapRight - 0.5f)
        }
        assertEquals(3, words.count { it.box.right <= gapLeft + 0.5f })
        val placed = touches.view.toolbarPlacements
        assertEquals(KeyKind.SUGGESTED_EMOJI, placed[placed.size - 2].key.kind)
        assertEquals(KeyKind.VOICE, placed.last().key.kind)
        for ((a, b) in placed.zipWithNext()) assertTrue("${a.key.label} overlaps ${b.key.label}", a.box.right <= b.box.left + 0.5f)
    }

    @Test
    fun `on a split keyboard the not learning badge squeezes only the left half`() {
        val touches = strip(932, Split.ALWAYS)
        val lanes = touches.view.stripLanes
        assertEquals("the keys are split", 2, lanes.size)
        val gapLeft = lanes[0].second.toFloat()
        val gapRight = lanes[1].first.toFloat()
        val rightBefore = touches.words().filter { it.box.left >= gapRight - 0.5f }.map { it.box }
        touches.view.notLearning = true
        val placed = touches.view.toolbarPlacements
        for (item in placed) {
            val box = item.box
            assertTrue("${item.key.label} lies on the gap", box.right <= gapLeft + 1f || box.left >= gapRight - 1f)
        }
        val words = touches.words()
        assertEquals("the typed word is still first", "Hel", words.first().key.label)
        assertEquals("the right half is untouched", rightBefore, words.filter { it.box.left >= gapRight - 0.5f }.map { it.box })
        val eye = placed.single { it.key.kind == KeyKind.PRIVATE }
        assertTrue(words.filter { it.box.right <= gapLeft + 1f }.all { it.box.left >= eye.box.right - 0.5f })
    }

    @Test
    fun `the engine offers enough for the widest strip`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val words = Dictionary.load(context, Language.ENGLISH)
        assertEquals(4, Suggestions.forWord("hel", words, null).size)
    }
}
