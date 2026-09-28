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
 * Finding an emoji from a word, against the files that ship.
 *
 * Read from the real assets rather than a sample, because the ways this goes wrong are in the data: a language file
 * that lost an emoji, a keyword spelled with an accent nobody types, a name that ranks a house above a heart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmojiSearchTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun load(language: Language = Language.ENGLISH) = EmojiSearch.load(context, language)

    private val shipped = Emoji.CATEGORIES.flatMap { it.items }.distinct()

    @Test
    fun `every language names every emoji Keyd ships`() {
        for (language in Language.entries) {
            val search = load(language)
            assertEquals("$language", shipped.size, search.size)
            for (glyph in shipped) {
                assertTrue("$language has no name for $glyph", !search.nameOf(glyph).isNullOrBlank())
            }
        }
    }

    @Test
    fun `heart puts the red heart at the top`() {
        val found = load().search("heart", 8)
        assertEquals("❤️", found.first())
    }

    @Test
    fun `cat finds the cat face`() {
        assertTrue(load().search("cat", 8).contains("🐱"))
    }

    @Test
    fun `a word being typed finds what it starts`() {
        assertTrue(load().search("hea", 20).contains("❤️"))
    }

    @Test
    fun `French coeur finds the heart, with or without the ligature`() {
        val french = load(Language.FRENCH)
        assertTrue(french.search("coeur", 8).contains("❤️"))
        assertEquals(french.search("coeur", 8), french.search("cœur", 8))
    }

    @Test
    fun `spaces and capitals around the word make no difference`() {
        val search = load()
        assertEquals(search.search("heart", 8), search.search("  HEART ", 8))
    }

    @Test
    fun `accents are optional`() {
        val spanish = load(Language.SPANISH)
        assertTrue(spanish.search("corazon", 8).contains("❤️"))
        assertEquals(spanish.search("corazón", 8), spanish.search("corazon", 8))
    }

    @Test
    fun `nonsense finds nothing`() {
        assertEquals(emptyList<String>(), load().search("qxqx", 8))
    }

    /** Not nonsense after all: CLDR gives "zzz" to the sleeping face and the zzz symbol. */
    @Test
    fun `zzz finds sleep`() {
        assertEquals(listOf("💤", "😴").sorted(), load().search("zzz", 8).sorted())
    }

    @Test
    fun `nothing typed finds nothing`() {
        assertEquals(emptyList<String>(), load().search("   ", 8))
    }

    @Test
    fun `every word typed has to match`() {
        val found = load().search("red heart", 8)
        assertEquals("❤️", found.first())
        assertTrue("the yellow heart is not red", "💛" !in found)
    }

    @Test
    fun `the limit is kept`() {
        assertEquals(3, load().search("face", 3).size)
    }

    /** Among equally good matches, the one someone keeps using should not have to be hunted for. */
    @Test
    fun `recents come first among equal matches`() {
        val search = load()
        val plain = search.search("heart", 20)
        val purple = "💜"
        assertTrue(plain.indexOf(purple) > 0)
        assertEquals(purple, search.search("heart", 20, recents = listOf(purple)).first())
    }

    @Test
    fun `a recent does not jump a better match`() {
        // The house has "heart" as a keyword; the red heart is called one. Using the house a lot does not change that.
        val found = load().search("heart", 20, recents = listOf("🏠"))
        assertTrue(found.indexOf("❤️") < found.indexOf("🏠"))
    }

    @Test
    fun `names are what TalkBack reads`() {
        assertEquals("red heart", load().nameOf("❤️"))
        assertEquals("cœur rouge", load(Language.FRENCH).nameOf("❤️"))
    }

    @Test
    fun `a file without keywords still searches by name`() {
        val search = EmojiSearch.parse(sequenceOf("🐱\tcat face", "", "broken line", "❤️\tred heart\theart,love"))
        assertEquals(2, search.size)
        assertEquals(listOf("🐱"), search.search("cat", 5))
        assertEquals(listOf("❤️"), search.search("love", 5))
    }
}
