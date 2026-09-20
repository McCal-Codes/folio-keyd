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
/** How much of the window the keys may take. A phone is held differently by every hand there is. */
enum class Size(val share: Float) { SMALL(0.86f), MEDIUM(1f), LARGE(1.14f) }

/**
 * Whether to split the keyboard in two on a big screen.
 *
 * [AUTO] splits when the window is large in both directions - an unfolded Fold, a tablet - and not otherwise.
 * The other two are for hands that disagree with that, which is most of the point of having the setting.
 */
enum class Split { AUTO, ALWAYS, NEVER }

/** Light or dark. [SYSTEM] follows the phone, which is what almost everyone wants and nobody has to choose. */
enum class Appearance { SYSTEM, DARK, LIGHT }

data class Settings(
    /** The row above the keys that offers words. Off means no strip, and no autocorrect either. */
    val suggestions: Boolean = true,
    /** Replace a clear typo when the word is finished. Never a real word, and always one backspace from undone. */
    val autocorrect: Boolean = true,
    /** Underline a finished word the dictionary has never heard of. */
    val spellCheck: Boolean = true,
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
    /** Swipe across the space bar to move the cursor. */
    val cursorSwipe: Boolean = true,
    /** Swipe left on backspace to take a whole word. */
    val deleteWordSwipe: Boolean = true,
    /** Swipe down on the space bar to put the keyboard away. */
    val swipeDownToHide: Boolean = true,
    val size: Size = Size.MEDIUM,
    val split: Split = Split.AUTO,
    val appearance: Appearance = Appearance.SYSTEM,
    /** Every key outlined and every label at full strength, for eyes the ordinary palette does not suit. */
    val highContrast: Boolean = false,
    /** The click. Follows the phone's own touch-sound setting as well; this can only turn it further off. */
    val sound: Boolean = true,
    /** The tap you feel. Follows the phone's own vibration setting as well. */
    val vibrate: Boolean = true,
) {

    fun save(prefs: SharedPreferences) {
        prefs.edit().apply {
            putBoolean(SUGGESTIONS, suggestions)
            putBoolean(AUTOCORRECT, autocorrect)
            putBoolean(SPELL_CHECK, spellCheck)
            putBoolean(LEARN, learn)
            putBoolean(AUTO_CAPITALISE, autoCapitalise)
            putBoolean(DOUBLE_SPACE, doubleSpaceFullStop)
            putBoolean(NUMBER_ROW, numberRow)
            putBoolean(ACCENTS, accents)
            putBoolean(KEY_PREVIEW, keyPreview)
            putBoolean(FLICK_ALTERNATE, flickForAlternate)
            putBoolean(FLICK_CAPITAL, flickForCapital)
            putBoolean(CURSOR_SWIPE, cursorSwipe)
            putBoolean(DELETE_WORD_SWIPE, deleteWordSwipe)
            putBoolean(SWIPE_DOWN_HIDE, swipeDownToHide)
            putString(SIZE, size.name)
            putString(SPLIT, split.name)
            putString(APPEARANCE, appearance.name)
            putBoolean(HIGH_CONTRAST, highContrast)
            putBoolean(SOUND, sound)
            putBoolean(VIBRATE, vibrate)
        }.apply()
    }

    companion object {
        const val SUGGESTIONS = "suggestions"
        const val AUTOCORRECT = "autocorrect"
        const val SPELL_CHECK = "spellCheck"
        const val LEARN = "learn"
        const val AUTO_CAPITALISE = "autoCapitalise"
        const val DOUBLE_SPACE = "doubleSpace"
        const val NUMBER_ROW = "numberRow"
        const val ACCENTS = "accents"
        const val KEY_PREVIEW = "keyPreview"
        const val FLICK_ALTERNATE = "flickAlternate"
        const val FLICK_CAPITAL = "flickCapital"
        const val CURSOR_SWIPE = "cursorSwipe"
        const val DELETE_WORD_SWIPE = "deleteWordSwipe"
        const val SWIPE_DOWN_HIDE = "swipeDownHide"
        const val SIZE = "size"
        const val SPLIT = "split"
        const val APPEARANCE = "appearance"
        const val HIGH_CONTRAST = "highContrast"
        const val SOUND = "sound"
        const val VIBRATE = "vibrate"

        fun load(prefs: SharedPreferences): Settings {
            val fallback = Settings()
            fun read(key: String, whenMissing: Boolean) = prefs.getBoolean(key, whenMissing)
            return Settings(
                suggestions = read(SUGGESTIONS, fallback.suggestions),
                autocorrect = read(AUTOCORRECT, fallback.autocorrect),
                spellCheck = read(SPELL_CHECK, fallback.spellCheck),
                learn = read(LEARN, fallback.learn),
                autoCapitalise = read(AUTO_CAPITALISE, fallback.autoCapitalise),
                doubleSpaceFullStop = read(DOUBLE_SPACE, fallback.doubleSpaceFullStop),
                numberRow = read(NUMBER_ROW, fallback.numberRow),
                accents = read(ACCENTS, fallback.accents),
                keyPreview = read(KEY_PREVIEW, fallback.keyPreview),
                flickForAlternate = read(FLICK_ALTERNATE, fallback.flickForAlternate),
                flickForCapital = read(FLICK_CAPITAL, fallback.flickForCapital),
                cursorSwipe = read(CURSOR_SWIPE, fallback.cursorSwipe),
                deleteWordSwipe = read(DELETE_WORD_SWIPE, fallback.deleteWordSwipe),
                swipeDownToHide = read(SWIPE_DOWN_HIDE, fallback.swipeDownToHide),
                size = choice(prefs, SIZE, fallback.size),
                split = choice(prefs, SPLIT, fallback.split),
                appearance = choice(prefs, APPEARANCE, fallback.appearance),
                highContrast = read(HIGH_CONTRAST, fallback.highContrast),
                sound = read(SOUND, fallback.sound),
                vibrate = read(VIBRATE, fallback.vibrate),
            )
        }

        /**
         * Reads a choice by name, falling back when the name means nothing.
         *
         * Stored by name rather than by position, so that adding a choice later cannot silently change what
         * someone already picked - which is what happens to every setting stored as a number.
         */
        private inline fun <reified T : Enum<T>> choice(prefs: SharedPreferences, key: String, fallback: T): T =
            runCatching { enumValueOf<T>(prefs.getString(key, null) ?: return fallback) }.getOrDefault(fallback)

        /** Everything back to how it arrived. */
        fun reset(prefs: SharedPreferences) = Settings().also { it.save(prefs) }

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
