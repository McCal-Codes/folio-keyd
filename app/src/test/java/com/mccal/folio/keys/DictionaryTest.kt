package com.mccal.folio.keys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real word list, as it will be on the phone.
 *
 * Small fakes prove the engine; this proves the thing it will actually run against - that the asset parses, that the
 * words people type are in it, and that looking something up is fast enough to do while they are still typing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictionaryTest {

    private val dictionary: Dictionary by lazy {
        Dictionary.load(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun `the list is the size it should be`() {
        assertTrue("only ${dictionary.size} words", dictionary.size in 50_000..70_000)
    }

    @Test
    fun `ordinary words are in it`() {
        for (word in listOf("the", "keyboard", "hello", "because", "tomorrow", "phone", "thanks", "sorry")) {
            assertTrue("$word is missing", dictionary.contains(word))
        }
    }

    /** Capitals are stored as capitals, and found either way round. */
    @Test
    fun `case does not decide whether a word exists`() {
        assertTrue(dictionary.contains("monday"))
        assertTrue(dictionary.contains("Monday"))
    }

    @Test
    fun `nonsense is not in it`() {
        for (word in listOf("qwertyuiop", "asdfgh", "zzzzz")) {
            assertFalse("$word should not be a word", dictionary.contains(word))
        }
    }

    @Test
    fun `a prefix finds the words under it`() {
        val range = dictionary.startingWith("keyboa")
        assertTrue(range.any { dictionary.word(it) == "keyboard" })
        assertTrue(range.all { dictionary.word(it).lowercase().startsWith("keyboa") })
    }

    @Test
    fun `a prefix nothing starts with finds nothing`() {
        assertTrue(dictionary.startingWith("zzqx").none())
    }

    /** By shape means by shape: every index handed back has to be that letter and that length. */
    @Test
    fun `by shape returns only that shape`() {
        for (length in 3..8) {
            for (index in dictionary.byShape('t', length)) {
                val word = dictionary.word(index)
                assertEquals("$word is not $length long", length, word.length)
                assertEquals('t', word.first().lowercaseChar())
            }
        }
    }

    @Test
    fun `the words come back in order, and the rank is a real band`() {
        var previous = ""
        for (index in 0 until dictionary.size step 97) {
            val word = dictionary.word(index)
            assertTrue("$previous then $word is out of order", word.lowercase() >= previous)
            assertTrue("rank ${dictionary.rank(index)} on $word", dictionary.rank(index) in 0..3)
            previous = word.lowercase()
        }
    }

    /** "the" is in the commonest band and "rescind" is not, which is what the ranks are for. */
    @Test
    fun `common words rank ahead of rare ones`() {
        fun rankOf(word: String): Int {
            val range = dictionary.startingWith(word)
            val index = range.first { dictionary.word(it).equals(word, ignoreCase = true) }
            return dictionary.rank(index)
        }
        assertTrue(rankOf("the") < rankOf("rescind"))
    }

    /**
     * Suggesting has to keep up with typing.
     *
     * Not a benchmark, a ceiling: this runs on a desktop under Robolectric, so the number means little on its own,
     * but a change that makes lookups scan the whole dictionary will blow through it by orders of magnitude.
     */
    @Test
    fun `a suggestion is worked out quickly enough to type against`() {
        val rows = Layouts.rows(Layer.LETTERS, false, FieldRules())
        val proximity = Suggestions.Proximity(Geometry.place(rows, 1080, 700, 3f))
        val words = listOf("teh", "keyb", "recieve", "definately", "hte", "thge", "wrold", "seperate")
        for (word in words) Suggestions.forWord(word, dictionary, proximity)   // warm the per-letter caches
        val started = System.nanoTime()
        repeat(10) { for (word in words) Suggestions.forWord(word, dictionary, proximity) }
        val each = (System.nanoTime() - started) / (10.0 * words.size) / 1_000_000
        println("a suggestion takes about %.1f ms".format(each))
        assertTrue("a suggestion took %.1f ms".format(each), each < 50)
    }

    /** The real thing, on the real list: the misspellings everyone makes. */
    @Test
    fun `the usual misspellings are corrected`() {
        val rows = Layouts.rows(Layer.LETTERS, false, FieldRules())
        val proximity = Suggestions.Proximity(Geometry.place(rows, 1080, 700, 3f))
        val expected = mapOf(
            "teh" to "the",
            "recieve" to "receive",
            "seperate" to "separate",
            "wrold" to "world",
            "freind" to "friend",
        )
        val missed = expected.filter { (typed, wanted) ->
            wanted !in Suggestions.forWord(typed, dictionary, proximity)
        }
        assertEquals("not corrected: $missed", emptyMap<String, String>(), missed)
    }
}
