package com.mccal.folio.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the keyboard keeps about how someone writes.
 *
 * The interesting tests here are the ones about what it refuses to keep. A learning dictionary that learns
 * everything ends up full of typos, and those typos then compete with real words in the strip for as long as the
 * app is installed — so the rules that say no are worth more than the ones that say yes.
 */
class LearnedTest {

    @Test
    fun `a word typed once is remembered`() {
        val learned = Learned()
        learned.learn("mccal")
        assertEquals(1, learned.count("mccal"))
        assertTrue("mccal" in learned.all())
    }

    @Test
    fun `typing it again counts again`() {
        val learned = Learned()
        repeat(3) { learned.learn("folio") }
        assertEquals(3, learned.count("folio"))
    }

    /** A word used constantly should end up ranked with the ordinary words of the language, not below them. */
    @Test
    fun `the more it is used the more it counts`() {
        val learned = Learned()
        learned.learn("obsidian")
        val once = learned.score("obsidian")
        repeat(9) { learned.learn("obsidian") }
        val often = learned.score("obsidian")
        assertTrue("$once then $often", often < once)
        assertTrue("a word used ten times should rank well, got $often", often <= 10)
    }

    @Test
    fun `a word never seen scores worse than any learned one`() {
        val learned = Learned()
        learned.learn("anything")
        assertTrue(learned.score("something else") > learned.score("anything"))
    }

    @Test
    fun `most used come first`() {
        val learned = Learned()
        learned.learn("rare")
        repeat(5) { learned.learn("common") }
        assertEquals("common", learned.all().first())
    }

    // ---- what it refuses ----------------------------------------------------------------------------------

    /** The rule that matters: learn "teh" once and it competes with "the" for good. */
    @Test
    fun `a near miss of a common word is a slip, not a word`() {
        assertFalse(Learned.worthLearning("teh", known = false, nearMiss = true))
    }

    @Test
    fun `a word the dictionary already has is not worth learning`() {
        assertFalse(Learned.worthLearning("keyboard", known = true, nearMiss = false))
    }

    @Test
    fun `something too short is not a word`() {
        assertFalse(Learned.worthLearning("hi", known = false, nearMiss = false))
        assertFalse(Learned.worthLearning("x", known = false, nearMiss = false))
    }

    /** A code, a model number or a password fragment is not vocabulary, and must never come back as a suggestion. */
    @Test
    fun `anything with a digit in it is not vocabulary`() {
        assertFalse(Learned.worthLearning("hunter2", known = false, nearMiss = false))
        assertFalse(Learned.worthLearning("sha256", known = false, nearMiss = false))
    }

    @Test
    fun `a genuine new word is kept`() {
        assertTrue(Learned.worthLearning("mccal", known = false, nearMiss = false))
        assertTrue(Learned.worthLearning("o'brien", known = false, nearMiss = false))
    }

    // ---- keeping it small and forgettable ------------------------------------------------------------------

    @Test
    fun `it does not grow forever, and drops the least used first`() {
        val learned = Learned()
        repeat(4) { learned.learn("kept") }
        for (i in 0 until Learned.LIMIT + 50) learned.learn("word$i")
        assertTrue("grew to ${learned.size}", learned.size <= Learned.LIMIT)
        assertTrue("the word used four times should have survived", learned.count("kept") > 0)
    }

    @Test
    fun `it can be emptied`() {
        val learned = Learned()
        learned.learn("something")
        learned.clear()
        assertEquals(0, learned.size)
    }

    @Test
    fun `what is stored comes back the same`() {
        val learned = Learned()
        repeat(2) { learned.learn("folio") }
        learned.learn("mccal")
        val back = Learned.decode(learned.encode())
        assertEquals(2, back.count("folio"))
        assertEquals(1, back.count("mccal"))
    }

    @Test
    fun `nothing stored is not a crash`() {
        assertEquals(0, Learned.decode(null).size)
        assertEquals(0, Learned.decode("").size)
        assertEquals(0, Learned.decode("rubbish with no counts").size)
    }
}
