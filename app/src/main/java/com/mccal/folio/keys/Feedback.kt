package com.mccal.folio.keys

import android.content.Context
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
 */
class Feedback(private val context: Context) {

    private val audio: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val enabled: Boolean
        get() = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0
        }.getOrDefault(false)

    /** Android's own keypress effects: a different one for the three keys that aren't letters. */
    fun play(kind: KeyKind) {
        if (!enabled) return
        audio?.playSoundEffect(effectFor(kind))
    }

    private companion object {
        fun effectFor(kind: KeyKind): Int = when (kind) {
            KeyKind.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
            KeyKind.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            KeyKind.ACTION -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
    }
}
