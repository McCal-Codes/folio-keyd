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

/** What is remembered between sessions, and the one rule that ties two of the switches together. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsTest {

    private val prefs by lazy {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("test", Context.MODE_PRIVATE)
    }

    /** Someone who never opens the settings screen should get the ordinary answer to everything. */
    @Test
    fun `the defaults are what someone gets who never looks`() {
        val fresh = Settings.load(prefs)
        assertTrue(fresh.suggestions)
        assertTrue(fresh.autocorrect)
        assertTrue(fresh.learn)
        assertTrue(fresh.autoCapitalise)
        assertTrue(fresh.doubleSpaceFullStop)
        assertTrue(fresh.accents)
        assertTrue(fresh.keyPreview)
        assertTrue(fresh.sound)
        assertTrue(fresh.vibrate)
        assertFalse("a number row costs a row of height, so it is asked for", fresh.numberRow)
    }

    @Test
    fun `what is chosen comes back`() {
        val chosen = Settings(
            suggestions = false, autocorrect = false, learn = false, autoCapitalise = false,
            doubleSpaceFullStop = false, numberRow = true, accents = false, keyPreview = false,
            sound = false, vibrate = false,
        )
        chosen.save(prefs)
        assertEquals(chosen, Settings.load(prefs))
    }

    @Test
    fun `the new choices default to the ordinary answer`() {
        val fresh = Settings.load(prefs)
        assertEquals(Size.MEDIUM, fresh.size)
        assertEquals(Split.AUTO, fresh.split)
        assertEquals(Appearance.SYSTEM, fresh.appearance)
        assertFalse(fresh.highContrast)
        assertTrue(fresh.cursorSwipe)
        assertTrue(fresh.deleteWordSwipe)
        assertTrue(fresh.swipeDownToHide)
    }

    @Test
    fun `a chosen size, split and appearance come back`() {
        Settings(size = Size.LARGE, split = Split.NEVER, appearance = Appearance.DARK).save(prefs)
        val back = Settings.load(prefs)
        assertEquals(Size.LARGE, back.size)
        assertEquals(Split.NEVER, back.split)
        assertEquals(Appearance.DARK, back.appearance)
    }

    /**
     * Choices are stored by name, not by position.
     *
     * A setting kept as a number quietly means something different the day a choice is added in the middle, and
     * the person who set it is never told. A name that no longer exists falls back instead.
     */
    @Test
    fun `a choice that no longer exists falls back rather than crashing`() {
        prefs.edit().putString(Settings.SIZE, "ENORMOUS").apply()
        assertEquals(Size.MEDIUM, Settings.load(prefs).size)
    }

    @Test
    fun `everything can be put back`() {
        Settings(size = Size.LARGE, suggestions = false, numberRow = true).save(prefs)
        assertEquals(Settings(), Settings.reset(prefs))
        assertEquals(Settings(), Settings.load(prefs))
    }

    /** Short really is shorter and tall really is taller, at every window size. */
    @Test
    fun `the size setting changes the height`() {
        for (windowHeightDp in listOf(640f, 891f, 454f)) {
            val heights = listOf(Size.SMALL, Size.MEDIUM, Size.LARGE).map {
                Geometry.height(4, windowHeightDp, 3f, 0f, share = Geometry.SHARE * it.share)
            }
            assertTrue("$windowHeightDp: ${heights}", heights[0] < heights[1] && heights[1] < heights[2])
        }
    }

    @Test
    fun `no strip means no correcting, whatever the correcting switch says`() {
        assertFalse(Settings.correcting(Settings(suggestions = false, autocorrect = true)))
        assertFalse(Settings.correcting(Settings(suggestions = true, autocorrect = false)))
        assertTrue(Settings.correcting(Settings(suggestions = true, autocorrect = true)))
    }
}
