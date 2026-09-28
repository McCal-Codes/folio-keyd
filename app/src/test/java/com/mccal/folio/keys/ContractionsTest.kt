package com.mccal.folio.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContractionsTest {

    private val english = Contractions.of(Language.ENGLISH)

    @Test
    fun `a missing apostrophe is put back`() {
        assertEquals("don't", Contractions.fix("dont", english))
        assertEquals("I'm", Contractions.fix("im", english))
        assertEquals("a lot", Contractions.fix("alot", english))
    }

    @Test
    fun `the case typed is kept`() {
        assertEquals("Don't", Contractions.fix("Dont", english))
        assertEquals("DON'T", Contractions.fix("DONT", english))
        assertEquals("I'm", Contractions.fix("Im", english))
    }

    @Test
    fun `two capitals are an abbreviation, not a contraction`() {
        assertNull(Contractions.fix("IM", english))
        assertEquals("I'VE", Contractions.fix("IVE", english))
    }

    @Test
    fun `a real word is only offered, never fixed`() {
        for (word in listOf("its", "were", "well", "lets", "ill", "id", "hell", "shed")) {
            assertNull(word, Contractions.fix(word, english))
        }
        assertEquals("it's", Contractions.offer("its", english))
        assertEquals("we're", Contractions.offer("were", english))
    }

    @Test
    fun `wont is won't unless the word before makes it a word`() {
        assertEquals("won't", Contractions.fix("wont", english, previous = "i"))
        assertEquals("won't", Contractions.fix("wont", english))
        assertNull(Contractions.fix("wont", english, previous = "his"))
        assertNull(Contractions.fix("wont", english, previous = "was"))
    }

    @Test
    fun `french has only the elisions that are never words without the apostrophe`() {
        val french = Contractions.of(Language.FRENCH)
        assertEquals("c'est", Contractions.fix("cest", french))
        assertEquals("J'ai", Contractions.fix("Jai", french))
        assertNull(Contractions.fix("dont", french))
        assertNull(Contractions.fix("quelle", french))
    }

    @Test
    fun `the other languages have none`() {
        for (language in listOf(Language.SPANISH, Language.GERMAN, Language.ITALIAN, Language.PORTUGUESE)) {
            assertNull(Contractions.fix("dont", Contractions.of(language)))
        }
    }

    @Test
    fun `i on its own is I, and so is the i in i'm`() {
        assertEquals("I", Contractions.capitalI("i", Language.ENGLISH))
        assertEquals("I'm", Contractions.capitalI("i'm", Language.ENGLISH))
        assertEquals("I'll", Contractions.capitalI("i'll", Language.ENGLISH))
        assertNull(Contractions.capitalI("in", Language.ENGLISH))
        assertNull(Contractions.capitalI("I", Language.ENGLISH))
        assertNull(Contractions.capitalI("i", Language.ITALIAN))
    }
}
