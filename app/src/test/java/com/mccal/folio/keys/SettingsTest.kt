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
    fun `no strip means no correcting, whatever the correcting switch says`() {
        assertFalse(Settings.correcting(Settings(suggestions = false, autocorrect = true)))
        assertFalse(Settings.correcting(Settings(suggestions = true, autocorrect = false)))
        assertTrue(Settings.correcting(Settings(suggestions = true, autocorrect = true)))
    }
}
