package com.mccal.folio.keys

import android.content.SharedPreferences

/**
 * What someone has decided the keyboard should do.
 *
 * Every keyboard has a settings screen and most of them are a wall of switches with names that only make sense to
 * whoever wrote them. The aim here is fewer of them, each one saying plainly what it changes and what it costs -
 * and each one honest about what it cannot change: a password field ignores several of these no matter what is set,
 * because the field decides, not the person and not the keyboard.
 *
 * Defaults are what someone who never opens this screen should get, which is why they are all the ordinary answer.
 */
data class Settings(
    /** The row above the keys that offers words. Off means no strip, and no autocorrect either. */
    val suggestions: Boolean = true,
    /** Replace a clear typo when the word is finished. Never a real word, and always one backspace from undone. */
    val autocorrect: Boolean = true,
    /** Remember words it does not know, so it stops arguing with your own vocabulary. */
    val learn: Boolean = true,
    /** A capital at the start of a sentence. */
    val autoCapitalise: Boolean = true,
    /** Two spaces in a row become a full stop and a space, the way every phone keyboard has since the first one. */
    val doubleSpaceFullStop: Boolean = true,
    /** A row of digits above the letters, instead of reaching them through the 123 key. */
    val numberRow: Boolean = false,
    /** Holding a key offers its accents. Off means holding a top-row key still gives its digit. */
    val accents: Boolean = true,
    /** The bubble that shows the letter above your finger. Never shown in a password field whatever this says. */
    val keyPreview: Boolean = true,
    /** Flick a key downwards for the character printed in its corner - the digit on the top row. */
    val flickForAlternate: Boolean = true,
    /** Flick a letter upwards for its capital. */
    val flickForCapital: Boolean = true,
    /** The click. Follows the phone's own touch-sound setting as well; this can only turn it further off. */
    val sound: Boolean = true,
    /** The tap you feel. Follows the phone's own vibration setting as well. */
    val vibrate: Boolean = true,
) {

    fun save(prefs: SharedPreferences) {
        prefs.edit().apply {
            putBoolean(SUGGESTIONS, suggestions)
            putBoolean(AUTOCORRECT, autocorrect)
            putBoolean(LEARN, learn)
            putBoolean(AUTO_CAPITALISE, autoCapitalise)
            putBoolean(DOUBLE_SPACE, doubleSpaceFullStop)
            putBoolean(NUMBER_ROW, numberRow)
            putBoolean(ACCENTS, accents)
            putBoolean(KEY_PREVIEW, keyPreview)
            putBoolean(FLICK_ALTERNATE, flickForAlternate)
            putBoolean(FLICK_CAPITAL, flickForCapital)
            putBoolean(SOUND, sound)
            putBoolean(VIBRATE, vibrate)
        }.apply()
    }

    companion object {
        const val SUGGESTIONS = "suggestions"
        const val AUTOCORRECT = "autocorrect"
        const val LEARN = "learn"
        const val AUTO_CAPITALISE = "autoCapitalise"
        const val DOUBLE_SPACE = "doubleSpace"
        const val NUMBER_ROW = "numberRow"
        const val ACCENTS = "accents"
        const val KEY_PREVIEW = "keyPreview"
        const val FLICK_ALTERNATE = "flickAlternate"
        const val FLICK_CAPITAL = "flickCapital"
        const val SOUND = "sound"
        const val VIBRATE = "vibrate"

        fun load(prefs: SharedPreferences): Settings {
            val fallback = Settings()
            fun read(key: String, whenMissing: Boolean) = prefs.getBoolean(key, whenMissing)
            return Settings(
                suggestions = read(SUGGESTIONS, fallback.suggestions),
                autocorrect = read(AUTOCORRECT, fallback.autocorrect),
                learn = read(LEARN, fallback.learn),
                autoCapitalise = read(AUTO_CAPITALISE, fallback.autoCapitalise),
                doubleSpaceFullStop = read(DOUBLE_SPACE, fallback.doubleSpaceFullStop),
                numberRow = read(NUMBER_ROW, fallback.numberRow),
                accents = read(ACCENTS, fallback.accents),
                keyPreview = read(KEY_PREVIEW, fallback.keyPreview),
                flickForAlternate = read(FLICK_ALTERNATE, fallback.flickForAlternate),
                flickForCapital = read(FLICK_CAPITAL, fallback.flickForCapital),
                sound = read(SOUND, fallback.sound),
                vibrate = read(VIBRATE, fallback.vibrate),
            )
        }

        /**
         * Turning the strip off turns autocorrect off with it.
         *
         * They are one thing wearing two names: a correction is the strip's top answer applied without being asked,
         * so a keyboard that offers nothing cannot go and apply the nothing it offered. Leaving them independent
         * would let someone switch off every visible sign of the feature while it quietly kept changing their
         * words, which is the worst of the four combinations.
         */
        fun correcting(settings: Settings) = settings.suggestions && settings.autocorrect
    }
}
