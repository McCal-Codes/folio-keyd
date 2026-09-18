package com.mccal.folio.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deleting a word, and what a screen reader hears. Both are text work, so both are checked without a phone. */
class EditingTest {

    private fun afterWordDelete(before: String): String =
        before.dropLast(Words.charsToRemoveForWord(before))

    @Test
    fun `a word swipe takes the word and the space after it`() {
        assertEquals("hello ", afterWordDelete("hello world"))
        assertEquals("hello ", afterWordDelete("hello world "))
        assertEquals("", afterWordDelete("hello"))
        assertEquals("", afterWordDelete("hello "))
    }

    @Test
    fun `a word swipe on nothing does nothing`() {
        assertEquals(0, Words.charsToRemoveForWord(""))
        assertEquals(0, Words.charsToRemoveForWord("   ".dropLast(3)))
    }

    @Test
    fun `runs of spaces go with the word`() {
        assertEquals("one   ", afterWordDelete("one   two   "))   // the spaces before the word it took stay
        assertEquals("", afterWordDelete("    "))
    }

    @Test
    fun `punctuation is part of the word, as it is everywhere else`() {
        assertEquals("", afterWordDelete("don't"))
        assertEquals("wait, ", afterWordDelete("wait, what?"))
    }

    @Test
    fun `no two keys on a screen read the same`() {
        for (layer in Layer.entries) {
            for (kind in listOf(FieldKind.TEXT, FieldKind.EMAIL, FieldKind.URL, FieldKind.NUMBER)) {
                val rows = Layouts.rows(layer, false, FieldRules(kind = kind))
                val spoken = rows.flatten().map { Spoken.name(it, Shift.OFF) }
                val duplicates = spoken.groupBy { it }.filterValues { it.size > 1 }.keys
                assertTrue("$layer/$kind reads these twice: $duplicates", duplicates.isEmpty())
            }
        }
    }

    @Test
    fun `the shift key says which state it is in`() {
        val shiftKey = Layouts.rows(Layer.LETTERS, false, FieldRules()).flatten()
            .first { it.kind == KeyKind.SHIFT }
        val names = Shift.entries.map { Spoken.name(shiftKey, it) }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.contains("Caps lock"))
    }

    @Test
    fun `no key is read as a symbol nobody can say`() {
        val symbols = listOf("⌫", "⇧", "⇪", "🌐")
        for (layer in Layer.entries) {
            for (key in Layouts.rows(layer, false, FieldRules()).flatten()) {
                val name = Spoken.name(key, Shift.OFF)
                assertTrue("$name is read as a glyph", symbols.none { name == it })
                assertTrue("a key has no name", name.isNotBlank())
            }
        }
    }
}
