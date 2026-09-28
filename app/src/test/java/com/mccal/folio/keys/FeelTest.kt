package com.mccal.folio.keys

import android.content.Context
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

/** How the keys feel and sound: the strength of the tap, the click in headphones, and the black board. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeelTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("feel", Context.MODE_PRIVATE)

    // ---- vibration ----------------------------------------------------------------------------------------------

    @Test
    fun `each strength is its own effect, and off is none`() {
        assertNull(Haptics.effect(Vibration.OFF, Haptics.Touch.TAP))
        assertNull(Haptics.effect(Vibration.OFF, Haptics.Touch.HOLD))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, Haptics.effect(Vibration.LIGHT, Haptics.Touch.TAP))
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, Haptics.effect(Vibration.MEDIUM, Haptics.Touch.TAP))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, Haptics.effect(Vibration.STRONG, Haptics.Touch.TAP))
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, Haptics.effect(Vibration.LIGHT, Haptics.Touch.HOLD))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, Haptics.effect(Vibration.MEDIUM, Haptics.Touch.HOLD))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, Haptics.effect(Vibration.STRONG, Haptics.Touch.HOLD))
    }

    @Test
    fun `the tap goes through the view, and off taps nothing`() {
        val view = View(context).apply { isHapticFeedbackEnabled = true }
        Haptics.feel(view, Vibration.LIGHT)
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, shadowOf(view).lastHapticFeedbackPerformed())
        val quiet = View(context).apply { isHapticFeedbackEnabled = true }
        Haptics.feel(quiet, Vibration.OFF)
        assertEquals(-1, shadowOf(quiet).lastHapticFeedbackPerformed())
    }

    @Test
    fun `the old vibrate switch becomes a strength`() {
        prefs.edit().clear().putBoolean(Settings.VIBRATE, false).commit()
        assertEquals(Vibration.OFF, Settings.load(prefs).vibration)
        prefs.edit().clear().putBoolean(Settings.VIBRATE, true).commit()
        assertEquals(Vibration.MEDIUM, Settings.load(prefs).vibration)
        // Once saved, the strength is what counts and the old switch is gone.
        Settings(vibration = Vibration.LIGHT).save(prefs)
        assertFalse(prefs.contains(Settings.VIBRATE))
        assertEquals(Vibration.LIGHT, Settings.load(prefs).vibration)
        prefs.edit().clear().commit()
        assertEquals(Vibration.MEDIUM, Settings.load(prefs).vibration)
    }

    // ---- sound --------------------------------------------------------------------------------------------------

    @Test
    fun `headphones and speakers count as Bluetooth audio, the phone's own speaker and hearing aids do not`() {
        assertTrue(Feedback.isBluetooth(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertTrue(Feedback.isBluetooth(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertTrue(Feedback.isBluetooth(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertFalse(Feedback.isBluetooth(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertFalse(Feedback.isBluetooth(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertFalse(Feedback.isBluetooth(AudioDeviceInfo.TYPE_HEARING_AID))
    }

    @Test
    fun `connecting and disconnecting Bluetooth audio is noticed without asking on every key`() {
        val audio = context.getSystemService(AudioManager::class.java)
        val shadow = shadowOf(audio)
        shadow.setOutputDevices(listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()))
        val feedback = Feedback(context)
        feedback.start()
        assertFalse(feedback.bluetoothConnected)
        val buds = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build()
        shadow.addOutputDevice(buds, true)
        assertTrue(feedback.bluetoothConnected)
        shadow.removeOutputDevice(buds, true)
        assertFalse(feedback.bluetoothConnected)
        feedback.stop()
    }

    // ---- pure black ---------------------------------------------------------------------------------------------

    private fun dark(): Context {
        val config = Configuration(context.resources.configuration)
        config.uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL
        return context.createConfigurationContext(config)
    }

    @Test
    fun `pure black makes a dark board black in every key style, and leaves light alone`() {
        for (style in KeyStyle.entries) {
            assertEquals(style.name, 0xFF000000.toInt(), Theme.of(dark(), Appearance.DARK, style = style, pureBlack = true).board)
            assertEquals(Theme.of(context, Appearance.LIGHT, style = style), Theme.of(context, Appearance.LIGHT, style = style, pureBlack = true))
        }
        // Following the phone: only when the phone is dark.
        assertEquals(0xFF000000.toInt(), Theme.of(dark(), Appearance.SYSTEM, pureBlack = true).board)
        assertEquals(Theme.of(dark(), Appearance.DARK, highContrast = true), Theme.of(dark(), Appearance.DARK, highContrast = true, pureBlack = true))
    }

    @Test
    fun `the pure black palettes are among those the contrast test checks`() {
        val names = Theme.bothForTests().map { it.first }
        assertTrue(names.containsAll(listOf("pure black", "material pure black", "samsung pure black")))
        for (style in KeyStyle.entries) {
            val theme = Theme.of(dark(), Appearance.DARK, style = style, pureBlack = true)
            assertTrue(style.name, Theme.bothForTests().any { it.second == theme })
        }
    }
}
