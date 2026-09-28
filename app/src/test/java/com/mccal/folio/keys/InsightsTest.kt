package com.mccal.folio.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The counts behind What it fixes, and when the strip may ask about them.
 *
 * The rules that matter are the ones about asking: not before the third time, not again until three more, never
 * more than twice, and never after an answer. A keyboard that nags is worse than one that never offers anything.
 */
class InsightsTest {

    private fun fixTimes(store: Insights, times: Int, offering: Boolean = true): List<Insights.Offer?> =
        (1..times).map { store.fixStood("teh", "the", offering) }

    @Test
    fun `counts survive being stored and read back`() {
        val store = Insights()
        fixTimes(store, 2)
        store.undone("Folio", offering = true)
        val back = Insights.decode(store.encode())
        assertEquals(2, back.fixed().single().count)
        assertEquals("teh", back.fixed().single().typed)
        assertEquals("the", back.fixed().single().replacement)
        assertEquals("Folio", back.putBack().single().typed)
        assertEquals(1, back.putBack().single().count)
        assertEquals(store.encode(), back.encode())
    }

    @Test
    fun `what it was asked and answered survives too`() {
        val store = Insights()
        val offer = fixTimes(store, 3).last()!!
        store.answered(offer)
        val back = Insights.decode(store.encode())
        assertTrue(back.settled(offer))
        assertEquals(1, back.fixed().single().offered)
    }

    @Test
    fun `nonsense in storage is skipped, not trusted`() {
        val back = Insights.decode("f\tteh\tthe\tlots\t0\t0\t0\ngarbage\np\t\t\t3\t0\t0\t0\nf\tteh\t\t2\t0\t0\t0\np\tFolio\t\t2\t1\t0\t0")
        assertEquals(0, back.fixed().size)
        assertEquals(listOf("Folio"), back.putBack().map { it.typed })
        assertEquals(0, Insights.decode(null).size)
    }

    @Test
    fun `the same pair in another case is the same pair`() {
        val store = Insights()
        store.fixStood("Teh", "The", offering = false)
        store.fixStood("teh", "the", offering = false)
        assertEquals(2, store.fixed().single().count)
        store.undone("folio", offering = false)
        store.undone("Folio", offering = false)
        assertEquals(2, store.putBack().single().count)
        // The strip asks about it the way it was typed last.
        assertEquals("Folio", store.putBack().single().typed)
    }

    @Test
    fun `most counted first`() {
        val store = Insights()
        store.fixStood("hte", "the", offering = false)
        fixTimes(store, 4, offering = false)
        assertEquals(listOf("teh", "hte"), store.fixed().map { it.typed })
    }

    @Test
    fun `it is capped, and the least counted go first`() {
        val store = Insights()
        fixTimes(store, 5, offering = false)
        for (i in 0 until Insights.LIMIT + 20) store.undone("word$i", offering = false)
        assertEquals(Insights.LIMIT, store.size)
        // The one counted five times outlives a crowd of ones.
        assertEquals("teh", store.fixed().single().typed)
    }

    @Test
    fun `one call forgets everything`() {
        val store = Insights()
        fixTimes(store, 2)
        store.undone("Folio", offering = true)
        store.clear()
        assertTrue(store.isEmpty())
        assertEquals("", store.encode())
    }

    // ---- asking ------------------------------------------------------------------------------------------------

    @Test
    fun `a fix is offered as a rule the third time, and not before`() {
        val offers = fixTimes(Insights(), 3)
        assertNull(offers[0])
        assertNull(offers[1])
        assertEquals(Insights.Offer("teh", "the"), offers[2])
        assertFalse(offers[2]!!.keep)
    }

    @Test
    fun `a word put back is offered as a keeper the third time`() {
        val store = Insights()
        assertNull(store.undone("Folio", offering = true))
        assertNull(store.undone("Folio", offering = true))
        val offer = store.undone("Folio", offering = true)!!
        assertTrue(offer.keep)
        assertEquals("Folio", offer.typed)
    }

    /** Typing on puts it away, and it may come back, but only after three more. */
    @Test
    fun `waved away, it comes back only after three more`() {
        val store = Insights()
        fixTimes(store, 3)
        val next = fixTimes(store, 3)
        assertEquals(listOf(null, null, Insights.Offer("teh", "the")), next)
    }

    @Test
    fun `it is offered at most twice ever`() {
        val store = Insights()
        val offers = fixTimes(store, 30).filterNotNull()
        assertEquals(2, offers.size)
        assertEquals(30, store.fixed().single().count)
    }

    @Test
    fun `no is remembered`() {
        val store = Insights()
        val offer = fixTimes(store, 3).last()!!
        store.answer(offer, accepted = false, Learned(), Shortcuts())
        assertTrue(fixTimes(store, 10).all { it == null })
        // Still counted, for the list in Settings.
        assertEquals(13, store.fixed().single().count)
    }

    @Test
    fun `with offers off it counts but never asks, and asking later is not used up`() {
        val store = Insights()
        assertTrue(fixTimes(store, 5, offering = false).all { it == null })
        assertEquals(0, store.fixed().single().offered)
        // Turned back on, the next one asks.
        assertEquals(Insights.Offer("teh", "the"), store.fixStood("teh", "the", offering = true))
    }

    @Test
    fun `a fix to the same word is not a fix`() {
        val store = Insights()
        assertNull(store.fixStood("The", "the", offering = true))
        assertTrue(store.isEmpty())
    }

    // ---- answering ---------------------------------------------------------------------------------------------

    @Test
    fun `always adds a text shortcut`() {
        val store = Insights()
        fixTimes(store, 3)
        val shortcuts = Shortcuts()
        store.answer(Insights.Offer("teh", "the"), accepted = true, Learned(), shortcuts)
        assertEquals("the", shortcuts.expand("teh"))
        assertTrue(store.settled(Insights.Offer("teh", "the")))
    }

    /** Three words, ranked on the real scale. "teh" is not one of them, which is the point. */
    private val words = object : Suggestions.Words {
        private val list = listOf("tea" to 29, "ten" to 27, "the" to 4)
        override val size get() = list.size
        override fun word(index: Int) = list[index].first
        override fun rank(index: Int) = list[index].second
        override fun startingWith(prefix: String): IntRange {
            val hits = list.indices.filter { list[it].first.startsWith(prefix.lowercase()) }
            return if (hits.isEmpty()) IntRange.EMPTY else hits.first()..hits.last()
        }
        override fun byShape(first: Char, length: Int) =
            list.indices.filter { list[it].first.length == length && list[it].first[0] == first }.toIntArray()
        override fun contains(word: String) = list.any { it.first == word.lowercase() }
    }

    @Test
    fun `keep learns the word, so it stops being corrected`() {
        val learned = Learned()
        assertEquals("the", Suggestions.correction("teh", words, null, learned))
        Insights().answer(Insights.Offer("Teh", null), accepted = true, learned, Shortcuts())
        assertTrue(learned.count("teh") >= Learned.MEANT_IT)
        assertNull(Suggestions.correction("teh", words, null, learned))
    }

    @Test
    fun `a kept word outlives the prune that clears out slips`() {
        val learned = Learned()
        learned.keep("follo")
        assertEquals(0, learned.prune { true })
        assertEquals(Learned.MEANT_IT, learned.count("follo"))
    }
}
