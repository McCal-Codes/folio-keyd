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

        override fun byShape(first: Char, length: Int) = sorted.withIndex()
            .filter { it.value.first.length == length && it.value.first.first().lowercaseChar() == first }
            .map { it.index }
            .toIntArray()
    }

    private val words = Small(
        listOf(
            "the" to 0, "they" to 0, "then" to 0, "there" to 0, "them" to 0, "these" to 1,
            "keyboard" to 1, "keys" to 1, "key" to 0, "keyhole" to 3,
            "hello" to 0, "help" to 0, "held" to 1, "hell" to 2,
            "teh" to 3, "duck" to 1, "luck" to 1, "lick" to 2,
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
