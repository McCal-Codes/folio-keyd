package com.mccal.folio.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Short things standing for long things.
 *
 * The rules that matter are the ones about when a shortcut must *not* fire, because a shortcut that goes off inside
 * an ordinary word is worse than no shortcuts at all.
 */
class ShortcutsTest {

    private fun shortcuts(vararg pairs: Pair<String, String>) = Shortcuts().apply {
        for ((key, value) in pairs) add(key, value)
    }

    @Test
    fun `a shortcut expands to its phrase`() {
        assertEquals("on my way", shortcuts("omw" to "on my way").expand("omw"))
    }

    @Test
    fun `case does not matter when typing it`() {
        val set = shortcuts("omw" to "on my way")
        assertEquals("on my way", set.expand("OMW"))
        assertEquals("on my way", set.expand("Omw"))
    }

    /** Whole word only: a trigger inside a longer word is not a trigger. */
    @Test
    fun `it does not fire part way through a word`() {
        val set = shortcuts("omw" to "on my way")
        assertNull(set.expand("omwards"))
        assertNull(set.expand("tomw"))
    }

    @Test
    fun `an unknown word expands to nothing`() {
        assertNull(shortcuts("omw" to "on my way").expand("hello"))
    }

    @Test
    fun `the newest wins when the same trigger is added twice`() {
        val set = shortcuts("omw" to "on my way", "omw" to "on my way now")
        assertEquals(1, set.size)
        assertEquals("on my way now", set.expand("omw"))
    }

    // ---- what it refuses ----------------------------------------------------------------------------------

    @Test
    fun `a trigger with a space in it can never be typed, so it is refused`() {
        assertFalse(Shortcuts().add("on my", "on my way"))
    }

    @Test
    fun `nothing standing for nothing is refused`() {
        assertFalse(Shortcuts().add("", "something"))
        assertFalse(Shortcuts().add("omw", ""))
        assertFalse(Shortcuts().add("   ", "   "))
    }

    /** A word standing for itself would expand into itself forever, which is nobody's intention. */
    @Test
    fun `a phrase the same as its trigger is refused`() {
        assertFalse(Shortcuts().add("omw", "omw"))
        assertFalse(Shortcuts().add("omw", "OMW"))
    }

    @Test
    fun `the list does not grow forever`() {
        val set = Shortcuts()
        for (i in 0 until Shortcuts.LIMIT + 20) set.add("key$i", "phrase $i")
        assertEquals(Shortcuts.LIMIT, set.size)
    }

    @Test
    fun `an existing one can still be changed when the list is full`() {
        val set = Shortcuts()
        for (i in 0 until Shortcuts.LIMIT) set.add("key$i", "phrase $i")
        assertTrue(set.add("key0", "something else"))
        assertEquals("something else", set.expand("key0"))
    }

    // ---- keeping them -------------------------------------------------------------------------------------

    @Test
    fun `what is stored comes back the same`() {
        val set = shortcuts("omw" to "on my way", "addr" to "12 Example Street, Exampleton")
        val back = Shortcuts.decode(set.encode())
        assertEquals("on my way", back.expand("omw"))
        assertEquals("12 Example Street, Exampleton", back.expand("addr"))
        assertEquals(2, back.size)
    }

    /** A phrase with punctuation, commas and apostrophes in it is the normal case, not the odd one. */
    @Test
    fun `a phrase can be a whole sentence`() {
        val set = shortcuts("ty" to "Thanks — I'll get back to you, probably tomorrow.")
        assertEquals("Thanks — I'll get back to you, probably tomorrow.", Shortcuts.decode(set.encode()).expand("ty"))
    }

    @Test
    fun `nothing stored is not a crash`() {
        assertEquals(0, Shortcuts.decode(null).size)
        assertEquals(0, Shortcuts.decode("").size)
        assertEquals(0, Shortcuts.decode("no tab on this line").size)
    }

    @Test
    fun `one can be removed`() {
        val set = shortcuts("omw" to "on my way")
        set.remove("OMW")
        assertEquals(0, set.size)
    }
}
