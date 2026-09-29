package com.mccal.folio.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two fingers swiped across the keys: left undoes, right redoes. The fear with any two-finger gesture on a keyboard
 * is two thumbs typing at once, so most of these check that ordinary typing still types.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TwoFingerUndoTest {

    private val t = Touches()
    private fun key(label: String) = t.key(label).box

    @Test
    fun `two fingers left undo, and type nothing`() {
        t.twoDown(key("g"), key("k"))
        t.twoMove(-60f)
        t.twoUp()
        assertEquals(listOf("undo"), t.heard)
        assertEquals("", t.typed.toString())
        assertEquals("Undo Typing", t.view.pillLabel)
    }

    @Test
    fun `two fingers right redo`() {
        t.twoDown(key("d"), key("h"))
        t.twoMove(60f)
        t.twoUp()
        assertEquals(listOf("redo"), t.heard)
        assertEquals("Redo Typing", t.view.pillLabel)
    }

    @Test
    fun `one swipe is one undo, however far it goes`() {
        t.twoDown(key("g"), key("k"))
        t.twoMove(-60f)
        t.twoMove(-60f)
        t.twoUp()
        assertEquals(listOf("undo"), t.heard)
    }

    @Test
    fun `the pill goes after a moment and a half`() {
        t.twoDown(key("g"), key("k"))
        t.twoMove(-60f)
        t.twoUp()
        t.idle(1000)
        assertEquals("Undo Typing", t.view.pillLabel)
        t.idle(700)
        assertNull(t.view.pillLabel)
    }

    @Test
    fun `two thumbs typing at once still type both letters`() {
        t.twoDown(key("a"), key("l"))
        t.twoMove(-6f, 3f)
        t.twoUp()
        assertEquals("la", t.typed.toString())
        assertTrue(t.heard.isEmpty())
    }

    @Test
    fun `fingers going opposite ways are not a swipe`() {
        t.twoDown(key("f"), key("j"))
        t.twoMove(-50f, second = 50f to 0f)
        t.twoUp()
        assertTrue("undo" !in t.heard && "redo" !in t.heard)
    }

    @Test
    fun `fingers going up are not a swipe`() {
        t.twoDown(key("f"), key("j"))
        t.twoMove(-45f, -60f)
        t.twoUp()
        assertTrue("undo" !in t.heard)
    }

    @Test
    fun `not far enough is not a swipe`() {
        t.twoDown(key("f"), key("j"))
        t.twoMove(-30f)
        t.twoUp()
        assertTrue("undo" !in t.heard)
        assertNull(t.view.pillLabel)
    }

    @Test
    fun `a finger on backspace does not take a word on the way`() {
        t.twoDown(key("m"), t.kind(KeyKind.BACKSPACE).box)
        t.twoMove(-60f)
        t.twoUp()
        assertEquals(listOf("start:false", "undo"), t.heard)
    }

    @Test
    fun `a finger on the space bar does not move the cursor on the way`() {
        t.twoDown(key("k"), t.kind(KeyKind.SPACE).box)
        t.twoMove(60f)
        t.twoUp()
        assertEquals(listOf("redo"), t.heard)
        assertEquals("", t.typed.toString())
    }

    @Test
    fun `switched off, two fingers are two presses again`() {
        t.show(settings = Settings(twoFingerUndo = false))
        t.twoDown(key("g"), key("k"))
        t.twoMove(-60f)
        t.twoUp()
        assertTrue("undo" !in t.heard)
        assertNull(t.view.pillLabel)
    }

    @Test
    fun `the setting is on unless turned off, and kept`() {
        assertTrue(Settings().twoFingerUndo)
        val prefs = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("twoFinger", android.content.Context.MODE_PRIVATE)
        Settings(twoFingerUndo = false).save(prefs)
        assertEquals(false, Settings.load(prefs).twoFingerUndo)
    }
}
