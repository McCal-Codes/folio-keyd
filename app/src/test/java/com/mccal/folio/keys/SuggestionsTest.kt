package com.mccal.folio.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the strip decides to offer.
 *
 * Run against a handful of words rather than the real dictionary, so each test says exactly which words were
 * available and exactly what should have come back. The real list is exercised in [DictionaryTest].
 */
class SuggestionsTest {

    /** A dictionary small enough to reason about, ranked the way the real one is. */
    private class Small(pairs: List<Pair<String, Int>>) : Suggestions.Words {
        private val sorted = pairs.sortedBy { it.first.lowercase() }
        override val size get() = sorted.size
        override fun word(index: Int) = sorted[index].first
        override fun rank(index: Int) = sorted[index].second
        override fun startingWith(prefix: String): IntRange {
            val matching = sorted.withIndex()
                .filter { it.value.first.lowercase().startsWith(prefix.lowercase()) }
            return if (matching.isEmpty()) IntRange.EMPTY else matching.first().index..matching.last().index
        }

        override fun contains(word: String) = sorted.any { it.first.equals(word, ignoreCase = true) }

        override fun byShape(first: Char, length: Int) = sorted.withIndex()
            .filter { it.value.first.length == length && it.value.first.first().lowercaseChar() == first }
            .map { it.index }
            .toIntArray()
    }

    /**
     * Scores are on the real scale: 0 is the commonest word in the language, 99 is one no frequency list has seen.
     * The spread matters — the engine refuses to correct when two candidates are about as likely as each other, so
     * a fixture with everything bunched together would test the refusal and nothing else.
     *
     * Deliberately no "teh": it is not a word, and what happens to things that are not words is the point.
     */
    private val words = Small(
        listOf(
            "the" to 4, "they" to 12, "then" to 13, "there" to 10, "them" to 11, "these" to 18,
            "keyboard" to 30, "keys" to 28, "key" to 20, "keyhole" to 60,
            "hello" to 15, "help" to 16, "held" to 25, "hell" to 26,
            "duck" to 33, "luck" to 30, "lick" to 45, "tea" to 29, "ten" to 27,
            // Two words equally likely and one edit apart, for the case where there is no confident answer.
            "wind" to 22, "wine" to 23,
        ),
    )

    /** A QWERTY, with real boxes, so proximity is the thing the engine actually uses. */
    private val proximity: Suggestions.Proximity = run {
        val rows = Layouts.rows(Layer.LETTERS, false, FieldRules())
        Suggestions.Proximity(Geometry.place(rows, 1080, 700, 3f))
    }

    private fun suggest(typed: String) = Suggestions.forWord(typed, words, proximity)

    @Test
    fun `a part word is finished`() {
        assertTrue("keyboard" in suggest("keyb"))
    }

    @Test
    fun `the most common completion comes first`() {
        assertEquals("key", suggest("ke").first())
    }

    /** The point of the whole thing: a real slip, corrected. */
    @Test
    fun `a transposition is corrected`() {
        assertTrue("the' should be offered for 'teh', got ${suggest("teh")}", "the" in suggest("teh"))
    }

    @Test
    fun `a neighbouring key is corrected`() {
        // 'g' sits next to 'h' on a QWERTY, so "gello" is "hello".
        assertTrue("got ${suggest("gello")}", "hello" in suggest("gello"))
    }

    /** What was typed is the user's, and the strip never offers it back as though it were a suggestion. */
    @Test
    fun `the literal is never one of the suggestions`() {
        assertFalse("the" in suggest("the"))
        assertFalse("hello" in suggest("hello"))
    }

    @Test
    fun `nothing typed suggests nothing`() {
        assertEquals(emptyList<String>(), Suggestions.forWord("", words, proximity))
    }

    /** Two letters is not enough to be wrong about; only completions should appear. */
    @Test
    fun `a very short word is completed but not corrected`() {
        val suggestions = suggest("ke")
        assertTrue(suggestions.all { it.lowercase().startsWith("ke") })
    }

    @Test
    fun `at most three are offered`() {
        assertTrue(suggest("the").size <= Suggestions.LIMIT)
    }

    // ---- case ----------------------------------------------------------------------------------------------

    @Test
    fun `a capital typed is a capital offered`() {
        assertTrue(suggest("Teh").contains("The"))
    }

    @Test
    fun `shouting stays shouting`() {
        assertEquals("THE", Suggestions.matchCase("TEH", "the"))
    }

    @Test
    fun `lower case is left alone`() {
        assertEquals("the", Suggestions.matchCase("teh", "the"))
    }

    // ---- distance ------------------------------------------------------------------------------------------

    @Test
    fun `the same word is no distance at all`() {
        assertEquals(0, Suggestions.distance("hello", "hello", 2, null))
    }

    @Test
    fun `one letter out is one`() {
        assertEquals(1, Suggestions.distance("helld", "hello", 2, null))
    }

    @Test
    fun `far apart gives up rather than counting`() {
        assertTrue(Suggestions.distance("abcdefg", "hijklmn", 2, null) > 2)
    }

    /** The near miss is meant to be cheaper than the wild one; that is the whole of the proximity idea. */
    @Test
    fun `a neighbouring letter costs less than a distant one`() {
        val near = Suggestions.distance("gello", "hello", 2, proximity)
        val far = Suggestions.distance("pello", "hello", 2, proximity)
        assertTrue("near $near should be no worse than far $far", near <= far)
    }

    // ---- correcting on its own ------------------------------------------------------------------------------

    private fun correction(typed: String) = Suggestions.correction(typed, words, proximity)

    @Test
    fun `a clear typo is corrected without being asked`() {
        assertEquals("the", correction("teh"))
    }

    /** A word is a word. Whatever it is, if the dictionary has it, it is left exactly as typed. */
    @Test
    fun `a real word is never replaced`() {
        assertEquals(null, correction("the"))
        assertEquals(null, correction("duck"))
        assertEquals(null, correction("hell"))
    }

    @Test
    fun `something with no near word is left alone`() {
        assertEquals(null, correction("zxqwv"))
    }

    @Test
    fun `something too short is left alone`() {
        assertEquals(null, correction("teh".take(2)))
    }

    /** Two candidates equally close is not a confident answer, and belongs in the strip rather than in the text. */
    @Test
    fun `an ambiguous typo is left for the strip to offer`() {
        // "wind" and "wine" are both one letter from "winx", and about as common as each other.
        assertEquals(null, correction("winx"))
    }

    @Test
    fun `a word already learned is left alone`() {
        val learned = Learned()
        learned.learn("teh")
        assertEquals(null, Suggestions.correction("teh", words, proximity, learned))
    }

    @Test
    fun `a keyboard with no keys on it does not break proximity`() {
        val empty = Suggestions.Proximity(emptyList())
        assertTrue(empty.isEmpty())
        assertEquals(emptySet<Char>(), empty.neighbours('a'))
        assertTrue(Suggestions.forWord("teh", words, empty).isNotEmpty())
    }

    /** The letters really are neighbours: 'g' next to 'h', 'q' nowhere near 'p'. */
    @Test
    fun `proximity comes from where the keys actually are`() {
        assertTrue('h' in proximity.neighbours('g'))
        assertTrue('g' in proximity.neighbours('h'))
        assertFalse('p' in proximity.neighbours('q'))
    }
}
