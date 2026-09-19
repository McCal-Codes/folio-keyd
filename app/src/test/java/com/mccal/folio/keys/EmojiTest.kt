package com.mccal.folio.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The emoji lists, and the recents.
 *
 * The lists are data, but data that goes wrong quietly: a stray space makes two half-emoji out of one, a duplicate
 * means the same face twice in a grid, and a codepoint newer than the phone's font draws as an empty box that no
 * test on a desktop would ever notice. What can be checked here, is.
 */
class EmojiTest {

    private val all = Emoji.CATEGORIES.flatMap { it.items }

    @Test
    fun `every category has emoji in it`() {
        for (category in Emoji.CATEGORIES) {
            assertTrue("${category.name} is empty", category.items.size >= 20)
        }
    }

    /** A blank cell in the grid is a split that went wrong, which is what an empty entry would be. */
    @Test
    fun `no entry is blank`() {
        assertTrue(all.none { it.isBlank() })
    }

    /** Two of the same face in one category is the usual result of editing a list by hand. */
    @Test
    fun `no category repeats itself`() {
        for (category in Emoji.CATEGORIES) {
            val repeated = category.items.groupBy { it }.filterValues { it.size > 1 }.keys
            assertEquals("${category.name} repeats $repeated", emptySet<String>(), repeated)
        }
    }

    /**
     * Every entry has to be emoji and nothing else.
     *
     * An ordinary letter or a stray piece of punctuation in the grid looks like a bug to whoever finds it, and it is
     * the kind of thing a typo produces. Anything below the first emoji block is not an emoji.
     */
    @Test
    fun `nothing in the lists is ordinary text`() {
        for (entry in all) {
            val first = entry.codePointAt(0)
            assertTrue(
                "$entry starts with U+${first.toString(16)}, which is not emoji",
                first >= 0x203C,
            )
        }
    }

    @Test
    fun `the tab of every category is one of its own kind`() {
        for (category in Emoji.CATEGORIES) {
            assertTrue("${category.name} has no tab", category.tab.isNotEmpty())
        }
    }

    // ---- recents ----------------------------------------------------------------------------------------------

    @Test
    fun `the newest is first`() {
        val after = Emoji.remember(listOf("😀", "😁"), "😂")
        assertEquals(listOf("😂", "😀", "😁"), after)
    }

    @Test
    fun `using one again moves it up rather than adding it twice`() {
        val after = Emoji.remember(listOf("😀", "😁", "😂"), "😂")
        assertEquals(listOf("😂", "😀", "😁"), after)
    }

    @Test
    fun `the list does not grow forever`() {
        var recents = emptyList<String>()
        for (entry in all.take(Emoji.RECENT_LIMIT + 20)) recents = Emoji.remember(recents, entry)
        assertEquals(Emoji.RECENT_LIMIT, recents.size)
    }

    @Test
    fun `what is stored comes back the same`() {
        val recents = all.take(12)
        assertEquals(recents, Emoji.decode(Emoji.encode(recents)))
    }

    @Test
    fun `nothing stored is not a crash`() {
        assertEquals(emptyList<String>(), Emoji.decode(null))
        assertEquals(emptyList<String>(), Emoji.decode(""))
    }
}
