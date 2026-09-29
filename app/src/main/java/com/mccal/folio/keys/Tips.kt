package com.mccal.folio.keys

import android.content.SharedPreferences
import androidx.annotation.StringRes

/**
 * A gesture worth a line in the strip, once.
 *
 * Nobody finds a swipe by looking at a key. The space bar moves the cursor and the backspace takes a word, and
 * someone who never read the settings will type for a year without either. So each gets one short tip, where the
 * strip's questions already appear, and never again once it has been used or answered.
 *
 * [id] is what is stored, so the order here can change without anyone's tips coming back. [short] is the same tip in
 * fewer words for a narrow window; most are short enough already.
 */
enum class Tip(val id: String, @StringRes val text: Int, @StringRes val short: Int = text) {
    CURSOR_SWIPE("cursorSwipe", R.string.tip_cursor_swipe, R.string.tip_cursor_swipe_short),
    DELETE_WORD("deleteWord", R.string.tip_delete_word, R.string.tip_delete_word_short),
    EDIT_SWIPES("editSwipes", R.string.tip_edit_swipes),
    SHIFT_SELECT("shiftSelect", R.string.tip_shift_select),
    TWO_FINGER_UNDO("twoFingerUndo", R.string.tip_two_finger_undo),
    GLOBE_LANGUAGES("globeLanguages", R.string.tip_globe_languages),
    PERIOD_SYMBOLS("periodSymbols", R.string.tip_period_symbols);

    /** Whether the gesture works with these settings. A tip for something switched off would be teaching a lie. */
    fun on(settings: Settings): Boolean = when (this) {
        CURSOR_SWIPE -> settings.cursorSwipe
        DELETE_WORD -> settings.deleteWordSwipe
        EDIT_SWIPES -> settings.editSwipes
        SHIFT_SELECT -> settings.shiftSelect
        TWO_FINGER_UNDO -> Tips.twoFingerUndo(settings)
        GLOBE_LANGUAGES -> Tips.globeLanguages(settings)
        PERIOD_SYMBOLS -> Tips.periodSymbols(settings)
    }
}

/**
 * Which tips have been shown, how often, and the day the last one was.
 *
 * A tip is done when "Got it" is tapped, when the gesture is used, or after it has been shown [TIMES] times and put
 * away by typing each time: by then it has been read, or it never will be. At most one a day, and the keyboard asks
 * for at most one per field; see [KeysService]. Only counts are kept, never what was being typed.
 */
class Tips(private val shown: Map<Tip, Int> = emptyMap(), val day: Long = -1) {

    fun times(tip: Tip): Int = shown[tip] ?: 0

    fun done(tip: Tip): Boolean = times(tip) >= TIMES

    /** Every tip that will not be shown again. */
    fun finished(): Set<Tip> = Tip.entries.filterTo(HashSet()) { done(it) }

    /** The tip to show [today], or null: one already shown today, every tip done, or none switched on. */
    fun next(settings: Settings, today: Long): Tip? {
        if (day == today) return null
        return Tip.entries.firstOrNull { it.on(settings) && !done(it) }
    }

    /**
     * The tip for a field with these [rules], or null: tips switched off, a password field, where a strip saying
     * anything is saying too much, or a number or phone field, which has no space bar or letters to teach.
     */
    fun due(settings: Settings, rules: FieldRules, today: Long): Tip? {
        if (!settings.gestureTips || rules.password) return null
        if (rules.kind == FieldKind.NUMBER || rules.kind == FieldKind.PHONE) return null
        // Email and web addresses have no period key to hold, so that tip waits for a field that does.
        if (rules.kind == FieldKind.EMAIL || rules.kind == FieldKind.URL) {
            return next(settings.copy(periodSymbols = ""), today)
        }
        return next(settings, today)
    }

    /** [tip] went on screen [today]. */
    fun shownOn(tip: Tip, today: Long) = Tips(shown + (tip to times(tip) + 1), today)

    /** [tip] was answered or its gesture used: never shown again. */
    fun finish(tip: Tip) = Tips(shown + (tip to TIMES), day)

    fun encode(): String = shown.entries.joinToString(",") { (tip, times) -> "${tip.id}:$times" }

    fun save(prefs: SharedPreferences) {
        prefs.edit().putString(KEY, encode()).putLong(DAY, day).apply()
    }

    companion object {
        const val KEY = "tips"
        const val DAY = "tipDay"

        /** Shown this often and put away by typing each time, a tip counts as read. */
        const val TIMES = 3

        /** Typed in a field before a tip may appear, so it never greets someone the moment the keyboard opens. */
        const val AFTER_CHARS = 20

        fun decode(stored: String?, day: Long = -1): Tips {
            val shown = HashMap<Tip, Int>()
            stored?.split(',')?.forEach { part ->
                val tip = Tip.entries.firstOrNull { it.id == part.substringBefore(':') } ?: return@forEach
                val times = part.substringAfter(':', "").toIntOrNull() ?: return@forEach
                shown[tip] = times.coerceIn(0, TIMES)
            }
            return Tips(shown, day)
        }

        fun load(prefs: SharedPreferences): Tips =
            decode(runCatching { prefs.getString(KEY, null) }.getOrNull(), runCatching { prefs.getLong(DAY, -1) }.getOrDefault(-1))

        /** Every tip may be shown again, starting today. */
        fun reset(prefs: SharedPreferences) {
            prefs.edit().remove(KEY).remove(DAY).apply()
        }

        /** Two fingers swiped left to undo, while that is switched on. */
        internal fun twoFingerUndo(settings: Settings): Boolean = settings.twoFingerUndo

        /** Holding the globe for the language list, which is always there. */
        internal fun globeLanguages(settings: Settings): Boolean = true

        /** Holding the period for more symbols, unless someone emptied the list. */
        internal fun periodSymbols(settings: Settings): Boolean = settings.periodSymbols.isNotEmpty()
    }
}
