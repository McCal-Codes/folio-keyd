package com.mccal.folio.keys

import android.content.SharedPreferences
import android.view.ViewConfiguration

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

/**
 * A narrower keyboard against one edge, for a thumb that can't reach across. Only on a window that would otherwise
 * fill its width: a screen big enough to split or centre the keys has already solved the reach problem.
 */
enum class OneHanded { OFF, LEFT, RIGHT }

/**
 * How the keys are drawn. [FOLIO] is rounded with a little depth, [MATERIAL] flat like Gboard, [SAMSUNG] squarer and
 * closer together. Only the shapes and colours change; where every key sits is the same in all three.
 */
enum class KeyStyle { FOLIO, MATERIAL, SAMSUNG }

/** Light or dark. [SYSTEM] follows the phone, which is what almost everyone wants and nobody has to choose. */
enum class Appearance { SYSTEM, DARK, LIGHT }

/**
 * How hard a key taps back. Every step is one of Android's own haptic effects, played through the view, so it needs
 * no vibration permission and still follows the phone's own touch-feedback setting; [Haptics] says which is which.
 */
enum class Vibration { OFF, LIGHT, MEDIUM, STRONG }

/**
 * A button the toolbar can carry, besides Hide, which is always there and always first.
 *
 * Stored by name, in order, so a button added later cannot change what someone already arranged.
 */
enum class ToolKey(val kind: KeyKind) {
    EMOJI(KeyKind.EMOJI), UNDO(KeyKind.UNDO), REDO(KeyKind.REDO), CURSOR_PAD(KeyKind.CURSOR_PAD),
    SELECT_ALL(KeyKind.SELECT_ALL), CUT(KeyKind.CUT), COPY(KeyKind.COPY), PASTE(KeyKind.PASTE),
    CLIPBOARD(KeyKind.CLIPBOARD), VOICE(KeyKind.VOICE),
}

/**
 * How long a key is held before what is behind it opens: accents, the period's symbols, the globe's language list.
 * [FOLLOW_PHONE] is Android's own Touch and hold delay, which someone may already have set in accessibility.
 */
enum class HoldDelay(private val ms: Long) {
    FOLLOW_PHONE(0), SHORTER(250), LONGER(600);

    val millis: Long get() = if (this == FOLLOW_PHONE) ViewConfiguration.getLongPressTimeout().toLong() else ms
}

/** How fast a held backspace keeps deleting, as the gap between deletes. The wait before it starts is the same. */
enum class BackspaceSpeed(val millis: Long) { SLOWER(90), NORMAL(55), FASTER(35) }

data class Settings(
    /** The row above the keys that offers words. Off means no strip, and no autocorrect either. */
    val suggestions: Boolean = true,
    /** Replace a clear typo when the word is finished. Never a real word, and always one backspace from undone. */
    val autocorrect: Boolean = true,
    /** Underline a finished word the dictionary has never heard of. */
    val spellCheck: Boolean = true,
    /** Remember words it does not know, so it stops arguing with your own vocabulary. */
    val learn: Boolean = true,
    /** The emoji a word is the name of, at the end of the strip: "pizza" offers 🍕. Only ever offered. */
    val suggestEmoji: Boolean = true,
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
    val oneHanded: OneHanded = OneHanded.OFF,
    val appearance: Appearance = Appearance.SYSTEM,
    /** Every key outlined and every label at full strength, for eyes the ordinary palette does not suit. */
    val highContrast: Boolean = false,
    /**
     * Keep what has been copied lately, so it can be put back without leaving the keyboard. Never from a field that
     * forbids it, never something its app marked sensitive, and forgotten after an hour unless pinned.
     */
    val clipboardHistory: Boolean = true,
    /** The click. Follows the phone's own touch-sound setting as well; this can only turn it further off. */
    val sound: Boolean = true,
    /** The tap you feel. Follows the phone's own vibration setting as well, so this can only make it quieter. */
    val vibration: Vibration = Vibration.MEDIUM,
    /**
     * No clicks while Bluetooth headphones or a speaker are connected. On by default: a click is for the person
     * holding the phone, and in headphones it lands on top of whatever they were listening to.
     */
    val muteWithBluetooth: Boolean = true,
    /** A black board when dark is in effect, which an OLED screen draws by switching the pixels off. */
    val pureBlack: Boolean = false,
    /**
     * The toolbar's buttons after Hide, in order, at most [MAX_TOOLS]. Voice is the mic on the toolbar and at the end
     * of the suggestion strip; it hands you to the phone's own voice keyboard, which is not Keyd and does use the
     * Internet, and with no voice keyboard on the phone it is not drawn at all.
     */
    val toolbar: List<ToolKey> = DEFAULT_TOOLBAR,
    val keyStyle: KeyStyle = KeyStyle.FOLIO,
    /** After the same fix or undo three times, the strip asks once whether to make it permanent. */
    val offerRules: Boolean = true,
    /** A swipe up on Z, X, C, V and A undoes, cuts, copies, pastes or selects all, instead of typing a capital. */
    val editSwipes: Boolean = true,
    /** Slide sideways from shift to select, the way the space bar moves the cursor. A tap is still shift. */
    val shiftSelect: Boolean = true,
    /** With text selected, the toolbar counts it and offers styles. Never in a password field. */
    val selectionTools: Boolean = true,
    /** A tip in the strip, once each, for a gesture nobody would find by looking. See [Tips]. */
    val gestureTips: Boolean = true,
    /** Two fingers swiped left across the keys undo, right redo. */
    val twoFingerUndo: Boolean = true,
    /**
     * What holding the period on the letters offers, in order: at most [MAX_PERIOD_SYMBOLS], each once, no spaces.
     * Empty means holding the period does nothing more than tapping it.
     */
    val periodSymbols: String = DEFAULT_PERIOD_SYMBOLS,
    val holdDelay: HoldDelay = HoldDelay.FOLLOW_PHONE,
    val backspaceSpeed: BackspaceSpeed = BackspaceSpeed.NORMAL,
) {

    fun save(prefs: SharedPreferences) {
        prefs.edit().apply {
            putBoolean(SUGGESTIONS, suggestions)
            putBoolean(AUTOCORRECT, autocorrect)
            putBoolean(SPELL_CHECK, spellCheck)
            putBoolean(LEARN, learn)
            putBoolean(SUGGEST_EMOJI, suggestEmoji)
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
            putString(ONE_HANDED, oneHanded.name)
            putString(APPEARANCE, appearance.name)
            putBoolean(HIGH_CONTRAST, highContrast)
            putBoolean(CLIPBOARD, clipboardHistory)
            putBoolean(SOUND, sound)
            putString(VIBRATION, vibration.name)
            // The old switch goes once its answer has been carried over, so it can't disagree with the new one.
            remove(VIBRATE)
            putBoolean(MUTE_WITH_BLUETOOTH, muteWithBluetooth)
            putBoolean(PURE_BLACK, pureBlack)
            putString(TOOLBAR, toolbar.joinToString(",") { it.name })
            // The two switches the list replaced go once their answers are in it, as the vibrate switch did.
            remove(VOICE_KEY)
            remove(CURSOR_PAD_KEY)
            putString(KEY_STYLE, keyStyle.name)
            putBoolean(OFFER_RULES, offerRules)
            putBoolean(EDIT_SWIPES, editSwipes)
            putBoolean(SHIFT_SELECT, shiftSelect)
            putBoolean(SELECTION_TOOLS, selectionTools)
            putBoolean(GESTURE_TIPS, gestureTips)
            putBoolean(TWO_FINGER_UNDO, twoFingerUndo)
            putString(PERIOD_SYMBOLS, periodSymbols)
            putString(HOLD_DELAY, holdDelay.name)
            putString(BACKSPACE_SPEED, backspaceSpeed.name)
        }.apply()
    }

    companion object {
        const val SUGGESTIONS = "suggestions"
        const val AUTOCORRECT = "autocorrect"
        const val SPELL_CHECK = "spellCheck"
        const val LEARN = "learn"
        const val SUGGEST_EMOJI = "suggestEmoji"
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
        const val ONE_HANDED = "oneHanded"
        const val APPEARANCE = "appearance"
        const val HIGH_CONTRAST = "highContrast"
        const val CLIPBOARD = "clipboardHistory"
        const val SOUND = "sound"
        /** Before there was a choice of strength, vibration was a switch. Read once, for anyone who set it then. */
        const val VIBRATE = "vibrate"
        const val VIBRATION = "vibration"
        const val MUTE_WITH_BLUETOOTH = "muteWithBluetooth"
        const val PURE_BLACK = "pureBlack"
        /** Before the toolbar could be arranged, the mic and the cursor pad key were switches. Read once, if set. */
        const val VOICE_KEY = "voiceKey"
        const val CURSOR_PAD_KEY = "cursorPadKey"
        const val TOOLBAR = "toolbar"
        const val KEY_STYLE = "keyStyle"
        const val OFFER_RULES = "offerRules"
        const val EDIT_SWIPES = "editSwipes"
        const val SHIFT_SELECT = "shiftSelect"
        const val SELECTION_TOOLS = "selectionTools"
        const val GESTURE_TIPS = "gestureTips"
        /** Set once someone arranges the toolbar themselves, so no later fix-up mistakes their list for a default. */
        const val TOOLBAR_ARRANGED = "toolbarArranged"
        /** Set once [giveBetasUndo] has looked, so it looks only once. */
        const val TOOLBAR_BETA_CHECKED = "toolbarBetaChecked"
        const val TWO_FINGER_UNDO = "twoFingerUndo"
        const val PERIOD_SYMBOLS = "periodSymbols"
        const val HOLD_DELAY = "holdDelay"
        const val BACKSPACE_SPEED = "backspaceSpeed"

        /** The period's symbols someone gets without choosing: the punctuation that isn't on the letters already. */
        const val DEFAULT_PERIOD_SYMBOLS = ",?!'\":;-"

        /** Eight fit in a row above the period on a phone-width screen, each still wide enough for a fingertip. */
        const val MAX_PERIOD_SYMBOLS = 8

        /**
         * What was typed into the Symbols field, as it is kept: spaces and repeats taken out, in the order typed, and
         * no more than [MAX_PERIOD_SYMBOLS]. Counted as the characters a person sees, so ❤️, 👍🏽 or a flag is one
         * symbol, not the two or three code points it is made of.
         */
        fun periodSymbols(typed: String): String {
            val kept = LinkedHashSet<String>()
            for (one in symbolList(typed)) {
                val point = one.codePointAt(0)
                if (Character.isWhitespace(point) || Character.isSpaceChar(point) || Character.isISOControl(point)) continue
                if (kept.size < MAX_PERIOD_SYMBOLS) kept += one
            }
            return kept.joinToString("")
        }

        /** The symbols one by one, the way the row above the period shows them. */
        fun symbolList(symbols: String): List<String> {
            val out = mutableListOf<String>()
            val breaks = android.icu.text.BreakIterator.getCharacterInstance()
            breaks.setText(symbols)
            var start = breaks.first()
            var end = breaks.next()
            while (end != android.icu.text.BreakIterator.DONE) {
                out += symbols.substring(start, end)
                start = end
                end = breaks.next()
            }
            return out
        }

        /** Buttons besides Hide. More than this and a narrow screen can't give each one a big enough target. */
        const val MAX_TOOLS = 7

        /** The toolbar someone gets without arranging it. */
        val DEFAULT_TOOLBAR = listOf(
            ToolKey.EMOJI, ToolKey.UNDO, ToolKey.CURSOR_PAD, ToolKey.COPY, ToolKey.PASTE, ToolKey.CLIPBOARD, ToolKey.VOICE,
        )

        /** What 0.2 showed after Hide, before the toolbar could be arranged and before Undo was on it. */
        val TOOLBAR_BEFORE_UNDO = listOf(
            ToolKey.EMOJI, ToolKey.CURSOR_PAD, ToolKey.COPY, ToolKey.PASTE, ToolKey.CLIPBOARD, ToolKey.VOICE,
        )

        /**
         * When the arranged toolbar was first built, 28 September 2026 at 15:48 UTC, a little before 0.3.0 beta 1.
         * Only 0.2 was out before then, so an install first made after it cannot have come from 0.2.
         */
        const val TOOLBAR_ARRIVED = 1_790_610_498_000L

        /**
         * Writes down the toolbar of someone who never arranged one, once, the first time Keyd runs without one.
         *
         * A fresh install gets the usual list, Undo and all. Someone coming from 0.2 keeps the six buttons they had:
         * starting them from the new list added Undo, and on a 360 dp screen, where only seven fit beside Hide, that
         * pushed the mic off the end. Anyone who changed a setting in 0.2 has its old switches stored, which says so
         * by itself; [updated] covers someone who never did. Decided once and kept, so what counts as an update
         * cannot change the toolbar under someone later.
         */
        fun settleToolbar(prefs: SharedPreferences, updated: Boolean, firstInstalled: Long? = null) {
            if (!prefs.contains(TOOLBAR)) {
                // An update over a 0.3 beta is not an update from 0.2: that install never had the six to keep.
                val from02 = updated && !sinceTheToolbar(firstInstalled)
                val list = toolbar(prefs, if (from02) TOOLBAR_BEFORE_UNDO else DEFAULT_TOOLBAR)
                prefs.edit()
                    .putString(TOOLBAR, list.joinToString(",") { it.name })
                    .remove(VOICE_KEY)
                    .remove(CURSOR_PAD_KEY)
                    .apply()
            }
            giveBetasUndo(prefs, firstInstalled)
        }

        /**
         * Puts Undo back for someone the betas mistook for a 0.2 upgrade, once.
         *
         * 0.3.0 beta 1 had no settling step and only wrote the toolbar down when a setting changed, so an install of
         * it that nobody touched had no list stored. Beta 2 then saw an update with no list and settled it as 0.2's
         * six, Undo and all left out, and every later version kept that. Such a list is exactly the six, and the
         * install is younger than the toolbar itself, which no 0.2 install is. Someone who arranged their toolbar is
         * never touched, and neither is anyone first installed before the toolbar existed, or when Android will not
         * say when that was: they may really have come from 0.2.
         */
        fun giveBetasUndo(prefs: SharedPreferences, firstInstalled: Long?) {
            if (prefs.getBoolean(TOOLBAR_BETA_CHECKED, false)) return
            val edit = prefs.edit().putBoolean(TOOLBAR_BETA_CHECKED, true)
            val stored = prefs.getString(TOOLBAR, null)
            if (stored == TOOLBAR_BEFORE_UNDO.joinToString(",") { it.name } &&
                !prefs.getBoolean(TOOLBAR_ARRANGED, false) && sinceTheToolbar(firstInstalled)
            ) edit.putString(TOOLBAR, DEFAULT_TOOLBAR.joinToString(",") { it.name })
            edit.apply()
        }

        /** Whether this install began after the toolbar could be arranged, so it never ran 0.2. */
        private fun sinceTheToolbar(firstInstalled: Long?) = firstInstalled != null && firstInstalled >= TOOLBAR_ARRIVED

        /** Whether this install has been updated since it was first installed. False if Android will not say. */
        fun updated(context: android.content.Context): Boolean = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.lastUpdateTime > info.firstInstallTime
        }.getOrDefault(false)

        /** When this app was first installed, kept by Android through every update. Null if it will not say. */
        fun firstInstalled(context: android.content.Context): Long? = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        }.getOrNull()?.takeIf { it > 0 }

        /** The stored list: names in order, anything unknown or repeated left out, and no more than [MAX_TOOLS]. */
        fun parseToolbar(stored: String): List<ToolKey> =
            stored.split(',').mapNotNull { name -> ToolKey.entries.firstOrNull { it.name == name.trim() } }
                .distinct().take(MAX_TOOLS)

        /**
         * The toolbar, or what the two old switches said: the cursor pad key off put Select all in its place, which
         * is where it went then, and the voice key off left the mic out. Anyone who never touched either gets the
         * usual list.
         */
        private fun toolbar(prefs: SharedPreferences, fallback: List<ToolKey>): List<ToolKey> {
            prefs.getString(TOOLBAR, null)?.let { return parseToolbar(it) }
            fun old(key: String) = runCatching { prefs.getBoolean(key, true) }.getOrDefault(true)
            // The old switches stored say this is 0.2's toolbar, which had no Undo.
            val base = if (prefs.contains(VOICE_KEY) || prefs.contains(CURSOR_PAD_KEY)) TOOLBAR_BEFORE_UNDO else fallback
            return base
                .map { if (it == ToolKey.CURSOR_PAD && !old(CURSOR_PAD_KEY)) ToolKey.SELECT_ALL else it }
                .filter { it != ToolKey.VOICE || old(VOICE_KEY) }
        }

        fun load(prefs: SharedPreferences): Settings {
            val fallback = Settings()
            fun read(key: String, whenMissing: Boolean) = prefs.getBoolean(key, whenMissing)
            return Settings(
                suggestions = read(SUGGESTIONS, fallback.suggestions),
                autocorrect = read(AUTOCORRECT, fallback.autocorrect),
                spellCheck = read(SPELL_CHECK, fallback.spellCheck),
                learn = read(LEARN, fallback.learn),
                suggestEmoji = read(SUGGEST_EMOJI, fallback.suggestEmoji),
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
                oneHanded = choice(prefs, ONE_HANDED, fallback.oneHanded),
                appearance = choice(prefs, APPEARANCE, fallback.appearance),
                highContrast = read(HIGH_CONTRAST, fallback.highContrast),
                clipboardHistory = read(CLIPBOARD, fallback.clipboardHistory),
                sound = read(SOUND, fallback.sound),
                vibration = vibration(prefs, fallback.vibration),
                muteWithBluetooth = read(MUTE_WITH_BLUETOOTH, fallback.muteWithBluetooth),
                pureBlack = read(PURE_BLACK, fallback.pureBlack),
                toolbar = toolbar(prefs, fallback.toolbar),
                keyStyle = choice(prefs, KEY_STYLE, fallback.keyStyle),
                offerRules = read(OFFER_RULES, fallback.offerRules),
                editSwipes = read(EDIT_SWIPES, fallback.editSwipes),
                shiftSelect = read(SHIFT_SELECT, fallback.shiftSelect),
                selectionTools = read(SELECTION_TOOLS, fallback.selectionTools),
                gestureTips = read(GESTURE_TIPS, fallback.gestureTips),
                twoFingerUndo = read(TWO_FINGER_UNDO, fallback.twoFingerUndo),
                periodSymbols = periodSymbols(prefs.getString(PERIOD_SYMBOLS, null) ?: fallback.periodSymbols),
                holdDelay = choice(prefs, HOLD_DELAY, fallback.holdDelay),
                backspaceSpeed = choice(prefs, BACKSPACE_SPEED, fallback.backspaceSpeed),
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

        /**
         * The strength, or what the old switch said: on was the tap every key made then, which is [Vibration.MEDIUM],
         * and off stays off.
         */
        private fun vibration(prefs: SharedPreferences, fallback: Vibration): Vibration = when {
            prefs.contains(VIBRATION) -> choice(prefs, VIBRATION, fallback)
            prefs.contains(VIBRATE) ->
                if (runCatching { prefs.getBoolean(VIBRATE, true) }.getOrDefault(true)) Vibration.MEDIUM else Vibration.OFF
            else -> fallback
        }

        /**
         * Everything back to how it arrived, per-app settings included: an app still set its own way after "every
         * setting back to normal" would be a setting that wasn't. Which apps have been typed in is not a setting, so
         * the list stays.
         */
        fun reset(prefs: SharedPreferences) = Settings().also {
            it.save(prefs)
            AppProfiles.load(prefs).apply { forgetChanges() }.save(prefs)
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
