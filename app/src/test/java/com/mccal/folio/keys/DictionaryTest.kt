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
            assertTrue("score ${dictionary.rank(index)} on $word", dictionary.rank(index) in 0..99)
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

    /**
     * The bug the phone found.
     *
     * Typing "teh" on the real keyboard offered "get", "tea" and "ten" - alphabetical order - and never reached
     * "the". Every one of those words sits in SCOWL's top tier, so they all scored identically and the tie broke on
     * spelling. The fix was real word frequencies; this is the test that would have caught it, so it checks the
     * order and not merely that the word is somewhere in the list.
     */
    @Test
    fun `the commonest correction is offered first, not the first alphabetically`() {
        val rows = Layouts.rows(Layer.LETTERS, false, FieldRules())
        val proximity = Suggestions.Proximity(Geometry.place(rows, 1080, 700, 3f))
        val first = mapOf(
            "teh" to "the",
            "adn" to "and",
            "taht" to "that",
            "jsut" to "just",
            "yuo" to "you",
        )
        val wrong = first.filter { (typed, wanted) ->
            Suggestions.forWord(typed, dictionary, proximity).firstOrNull() != wanted
        }.mapValues { (typed, _) -> Suggestions.forWord(typed, dictionary, proximity) }
        assertEquals("offered the wrong word first: $wrong", emptyMap<String, List<String>>(), wrong)
    }

    /** The commonest word in the language must not score the same as a word for a hot drink. */
    @Test
    fun `commonness tells words in the same tier apart`() {
        fun scoreOf(word: String): Int {
            val range = dictionary.startingWith(word)
            return dictionary.rank(range.first { dictionary.word(it).equals(word, ignoreCase = true) })
        }
        assertTrue("the=${scoreOf("the")} tea=${scoreOf("tea")}", scoreOf("the") < scoreOf("tea"))
        assertTrue(scoreOf("the") < scoreOf("ten"))
        assertTrue(scoreOf("and") < scoreOf("ant"))
    }

    // ---- correcting on its own, against the real word list ----------------------------------------------

    private fun proximity(): Suggestions.Proximity {
        val rows = Layouts.rows(Layer.LETTERS, false, FieldRules())
        return Suggestions.Proximity(Geometry.place(rows, 1080, 700, 3f))
    }

    /**
     * The typos worth fixing without being asked, each one having failed at some point.
     *
     * "recieve" and "keybaord" needed the bar for "a word people use" to be set by measurement rather than taste.
     * "hte" used to be corrected to "he", because a swap of the first two letters changes the first letter to one
     * that need not be next to it. "wnat" used to give "what", until a swap was made to count as better evidence
     * than a substitution.
     */
    @Test
    fun `the typos everyone makes are fixed without being asked`() {
        val near = proximity()
        val expected = mapOf(
            "teh" to "the", "hte" to "the", "tje" to "the", "adn" to "and", "taht" to "that",
            "wnat" to "want", "jsut" to "just", "yuo" to "you", "wiht" to "with", "abotu" to "about",
            "recieve" to "receive", "seperate" to "separate", "keybaord" to "keyboard",
            "definately" to "definitely", "becuase" to "because", "freind" to "friend",
            "thier" to "their", "goign" to "going", "wrold" to "world", "pelase" to "please",
            // A dropped letter: every letter typed is right and one is simply missing, which is the most obvious
            // kind of slip there is. Pricing it the same as a key from the far side of the keyboard turned "smrt"
            // into "sort".
            "smrt" to "smart", "wierd" to "weird", "acheive" to "achieve", "arguement" to "argument",
        )
        val wrong = expected.filter { (typed, wanted) ->
            Suggestions.correction(typed, dictionary, near) != wanted
        }.mapValues { (typed, _) -> Suggestions.correction(typed, dictionary, near) }
        assertEquals("corrected wrongly: $wrong", emptyMap<String, String?>(), wrong)
    }

    /**
     * Halfway through a word is not a mistake.
     *
     * "keyb" is not a word and "key" is one letter from it, but someone typing "keyboard" who pauses must not have
     * the keyboard taken off them. Finishing a word is the strip's job.
     */
    @Test
    fun `a word still being typed is not corrected to a shorter one`() {
        val near = proximity()
        for (part in listOf("keyb", "somet", "anoth", "tomor", "bec")) {
            assertEquals(
                "$part was replaced",
                null,
                Suggestions.correction(part, dictionary, near),
            )
        }
        // ...and the strip still offers the finished word.
        assertTrue("keyboard" in Suggestions.forWord("keyb", dictionary, near))
    }

    /** What the strip puts first must be what correcting would have chosen; two answers would be one too many. */
    @Test
    fun `the strip agrees with what gets corrected`() {
        val near = proximity()
        for (typed in listOf("teh", "hte", "smrt", "wnat", "recieve", "keybaord")) {
            val auto = Suggestions.correction(typed, dictionary, near)
            val first = Suggestions.forWord(typed, dictionary, near).firstOrNull()
            assertEquals("$typed: strip says $first, correcting says $auto", auto, first)
        }
    }

    /** Words are never replaced, whatever they are. This is the promise the whole feature rests on. */
    @Test
    fun `real words are left exactly as typed`() {
        val near = proximity()
        val words = listOf(
            "the", "duck", "form", "thing", "here", "wont", "cant",
            // The ones worth naming: swearing must never be turned into something else.
            "arse", "bollocks", "wanker", "shit", "damn", "piss", "bastard",
            // Nor should a name, nor everyday speech the formal dictionary once lacked.
            "sam", "gonna", "yeah", "okay", "hiya",
        )
        val replaced = words.filter { Suggestions.correction(it, dictionary, near) != null }
            .associateWith { Suggestions.correction(it, dictionary, near) }
        assertEquals("these were replaced: $replaced", emptyMap<String, String?>(), replaced)
    }

    /** Contractions are among the commonest words in English and must be scored as such. */
    @Test
    fun `contractions are not treated as words nobody uses`() {
        fun scoreOf(word: String): Int {
            val range = dictionary.startingWith(word)
            return dictionary.rank(range.first { dictionary.word(it).equals(word, ignoreCase = true) })
        }
        // Anything from 60 up means the frequency list had never heard of it, which was true of every one of
        // these until the apostrophe was accounted for.
        for (word in listOf("don't", "can't", "it's", "I'm", "you're", "that's")) {
            assertTrue("$word scores ${scoreOf(word)}", scoreOf(word) < 50)
        }
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
