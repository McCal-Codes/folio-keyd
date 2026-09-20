package com.mccal.folio.keys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every language that ships, checked against the things that break quietly.
 *
 * A wrong layout is obvious the moment anyone looks. A word list sorted differently from the way the keyboard
 * searches it is not: the binary search simply fails to find perfectly ordinary words, at random, forever. So the
 * order is checked on the real assets rather than assumed from the script that wrote them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LanguageTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun dictionary(language: Language) = Dictionary.load(context, language)

    @Test
    fun `every language has a word list that loads`() {
        for (language in Language.entries) {
            val words = dictionary(language)
            assertTrue("${language.tag} has only ${words.size} words", words.size > 15_000)
        }
    }

    /**
     * The order the keyboard searches in has to be the order the file is written in.
     *
     * Every lookup is a binary search, so a single pair out of order silently hides a stretch of the dictionary.
     * Accented letters are where this goes wrong, which is why it is worth checking now and was not before.
     */
    @Test
    fun `every word list is in the order the search expects`() {
        for (language in Language.entries) {
            val words = dictionary(language)
            var previous = words.word(0)
            for (index in 1 until words.size) {
                val current = words.word(index)
                assertTrue(
                    "${language.tag}: \"$previous\" is listed before \"$current\"",
                    previous.compareTo(current, ignoreCase = true) <= 0,
                )
                previous = current
            }
        }
    }

    /** The words each language is actually made of, including the ones with accents in them. */
    @Test
    fun `each language knows its own words`() {
        val expected = mapOf(
            Language.ENGLISH to listOf("the", "keyboard", "because"),
            Language.SPANISH to listOf("que", "está", "años", "señor"),
            Language.FRENCH to listOf("être", "déjà", "français", "où"),
            Language.GERMAN to listOf("über", "schön", "größe", "für"),
            Language.ITALIAN to listOf("perché", "città", "più"),
            Language.PORTUGUESE to listOf("não", "então", "você"),
        )
        for ((language, words) in expected) {
            val list = dictionary(language)
            val missing = words.filterNot { list.contains(it) }
            assertEquals("${language.tag} is missing $missing", emptyList<String>(), missing)
        }
    }

    /** An accented word has more bytes than letters, and the shape index counts letters: "años" is four, not five. */
    @Test
    fun `a word with accents is found by its length in letters`() {
        val spanish = dictionary(Language.SPANISH)
        val four = spanish.byShape('a', 4).map { spanish.word(it) }
        assertTrue("nothing of four letters starting with a", four.isNotEmpty())
        for (word in four) assertEquals("$word is not four letters", 4, word.length)
        assertTrue("años should be among them, got ${four.take(12)}", four.any { it == "años" })
    }

    /**
     * The letters d, w and the underscore, which a broken pattern once excluded from every word list but English.
     *
     * The builder's regex had a doubled backslash in it, so its "any letter" class read as "not a backslash, not a
     * W, not a d" - and every word containing a d silently vanished. Spanish lost eight thousand words, including
     * most of its commonest ones, and nothing said so.
     */
    @Test
    fun `words containing d and w are in every list`() {
        val expected = mapOf(
            Language.SPANISH to listOf("donde", "desde", "todo"),
            Language.FRENCH to listOf("dans", "déjà", "demain"),
            Language.GERMAN to listOf("und", "wir", "was", "werden"),
            Language.ITALIAN to listOf("dove", "dopo"),
            Language.PORTUGUESE to listOf("desde", "dizer"),
        )
        for ((language, words) in expected) {
            val list = dictionary(language)
            val missing = words.filterNot { list.contains(it) }
            assertEquals("${language.tag} is missing $missing", emptyList<String>(), missing)
        }
    }

    @Test
    fun `an accented word can be completed from an accented prefix`() {
        val french = dictionary(Language.FRENCH)
        val range = french.startingWith("êtr")
        assertTrue("nothing starts with être", range.any { french.word(it) == "être" })
    }

    // ---- the layouts ------------------------------------------------------------------------------------------

    @Test
    fun `the layouts are the ones each language actually uses`() {
        assertEquals(listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"), Language.ENGLISH.rows)
        assertEquals("azertyuiop", Language.FRENCH.rows.first())
        assertEquals("qwertzuiop", Language.GERMAN.rows.first())
        assertTrue("Spanish needs an ñ key", Language.SPANISH.rows[1].contains('ñ'))
    }

    /** The digit in the corner belongs to the position, not the letter: on AZERTY the first key is "a" and gives 1. */
    @Test
    fun `the corner digits follow the position, whatever letter is there`() {
        for (language in Language.entries) {
            val top = Layouts.rows(Layer.LETTERS, false, FieldRules(), language = language).first()
            assertEquals("${language.tag}: ${top.first().label} should hint 1", "1", top.first().hint)
            assertEquals("${language.tag}: the tenth key should hint 0", "0", top[9].hint)
        }
    }

    @Test
    fun `every language lays out without keys overlapping or shrinking`() {
        for (language in Language.entries) {
            val rows = Layouts.rows(Layer.LETTERS, false, FieldRules(), language = language)
            val placed = Geometry.place(rows, (411 * 3f).toInt(), Geometry.height(rows.size, 891f, 3f, 0f), 3f)
            for (placement in placed) {
                assertTrue(
                    "${language.tag}: ${placement.key.label} is ${placement.box.width / 3f} dp",
                    placement.box.width / 3f >= 24f,
                )
            }
            for (i in placed.indices) for (j in i + 1 until placed.size) {
                assertTrue(
                    "${language.tag}: ${placed[i].key.label} overlaps ${placed[j].key.label}",
                    !placed[i].box.overlaps(placed[j].box),
                )
            }
        }
    }

    // ---- the accents ------------------------------------------------------------------------------------------

    /** Each language's own ordering, not one pooled list shared out: Spanish leads with á, French with à. */
    @Test
    fun `each language offers its own accents in its own order`() {
        assertEquals("á", Alternates.forKey("a", null, Language.SPANISH).first())
        assertEquals("à", Alternates.forKey("a", null, Language.FRENCH).first())
        assertEquals("ä", Alternates.forKey("a", null, Language.GERMAN).first())
        assertTrue("German needs ß behind s", "ß" in Alternates.forKey("s", null, Language.GERMAN))
    }

    @Test
    fun `the corner digit still comes first when a key has one`() {
        val held = Alternates.forKey("e", "3", Language.FRENCH)
        assertEquals("3", held.first())
        assertTrue("é" in held)
    }

    // ---- picking one ------------------------------------------------------------------------------------------

    @Test
    fun `a subtype tag finds its language`() {
        assertEquals(Language.FRENCH, Language.of("fr-FR"))
        assertEquals(Language.GERMAN, Language.of("de-DE"))
        assertEquals(Language.SPANISH, Language.of("es-ES"))
    }

    /** Android may hand back a region we never named, and a French keyboard is still the answer for fr-CA. */
    @Test
    fun `a region we do not ship still finds the language`() {
        assertEquals(Language.FRENCH, Language.of("fr-CA"))
        assertEquals(Language.PORTUGUESE, Language.of("pt-BR"))
        assertEquals(Language.SPANISH, Language.of("es"))
    }

    @Test
    fun `an unknown language falls back to English rather than failing`() {
        assertEquals(Language.ENGLISH, Language.of("ja-JP"))
        assertEquals(Language.ENGLISH, Language.of(null))
        assertEquals(Language.ENGLISH, Language.of(""))
    }

    @Test
    fun `every language names itself in its own language`() {
        for (language in Language.entries) assertNotNull(language.ownName.ifEmpty { null })
        assertEquals("Français", Language.FRENCH.ownName)
    }
}
