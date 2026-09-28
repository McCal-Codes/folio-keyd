package com.mccal.folio.keys

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.provider.Settings

/**
 * The click a key makes.
 *
 * Android keeps four keypress sounds of its own, and every keyboard uses them rather than shipping its own: they
 * follow the phone's volume, its silent mode and whatever the person chose in Sound › Touch sounds. A keyboard that
 * played its own would ignore all three.
 *
 * The setting is read each time rather than cached, because someone can turn touch sounds off while the keyboard is
 * on screen and expect it to go quiet.
 *
 * Whether Bluetooth audio is connected is the opposite: asking the audio service on every key is a call into another
 * process, so the answer is kept and Android tells us when it changes. Neither needs a permission - the list of
 * outputs is open to any app, Bluetooth or not.
 */
class Feedback(private val context: Context) {

    private val audio: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val enabled: Boolean
        get() = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0
        }.getOrDefault(false)

    /** Whether a Bluetooth output is connected, as of Android's last word on it. Null until first asked. */
    @Volatile private var bluetooth: Boolean? = null

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = recheck()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = recheck()
    }
    private var listening = false

    private fun recheck() {
        bluetooth = runCatching {
            audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any { isBluetooth(it.type) }
        }.getOrNull() == true
    }

    /**
     * Starts keeping the Bluetooth answer. Registering hands over the current outputs straight away, so the first
     * key after this already knows.
     */
    fun start() {
        if (listening) return
        listening = true
        recheck()
        runCatching { audio?.registerAudioDeviceCallback(devices, null) }
    }

    /** Stops listening, for when the keys leave the screen for good. */
    fun stop() {
        if (!listening) return
        listening = false
        runCatching { audio?.unregisterAudioDeviceCallback(devices) }
        bluetooth = null
    }

    /** True while headphones or a speaker are connected over Bluetooth. */
    val bluetoothConnected: Boolean
        get() = bluetooth ?: run { recheck(); bluetooth == true }

    /**
     * Android's own keypress effects: a different one for the three keys that aren't letters. With [muteWithBluetooth]
     * on, nothing while Bluetooth audio is connected.
     */
    fun play(kind: KeyKind, muteWithBluetooth: Boolean = false) {
        if (!enabled) return
        if (muteWithBluetooth && bluetoothConnected) return
        audio?.playSoundEffect(effectFor(kind))
    }

    companion object {
        private fun effectFor(kind: KeyKind): Int = when (kind) {
            KeyKind.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
            KeyKind.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            KeyKind.ACTION -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }

        /**
         * Headphones, earbuds and speakers over Bluetooth, classic or LE. Not hearing aids: someone who hears the
         * click through theirs may be relying on it, and a switch named for headphones shouldn't take it away.
         */
        fun isBluetooth(type: Int): Boolean = type in BLUETOOTH_OUTPUTS

        // TYPE_BLE_BROADCAST is from Android 13; on 12 it is only a number no device reports.
        @android.annotation.SuppressLint("InlinedApi")
        private val BLUETOOTH_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
        )
    }
}
