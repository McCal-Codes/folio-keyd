package com.mccal.folio.keys

import android.text.SpannableString
import android.text.Spanned
import android.text.style.SuggestionSpan
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** What an "i" is followed by when it is a numeral rather than the word. */
private val NUMERAL_ENDS = setOf('.', ')', ':')

/** What ends a sentence, as far as guessing its next word goes. */
private val SENTENCE_ENDS = setOf('.', '!', '?', '\n')

/**
 * The little that editing needs from the keyboard service. Keeping it to this means the behaviour below can be driven
 * without an input method running, which is the only way to check what the keys actually do to someone's text.
 */
/**
 * What the suggestion thread worked out about one word, handed over for the moment that word is finished.
 *
 * One object rather than a handful of loose values, because every one of them is only meaningful together with
 * [word]: applying last word's verdict to this one is exactly the bug this shape prevents.
 */
data class Verdict(
    val word: String,
    /** The one correction confident enough to make unasked, or null. */
    val correction: String?,
    /** True when no dictionary, and nothing learned, has ever heard of this word. */
    val misspelled: Boolean,
    /** What to offer if the word is tapped after being underlined. */
    val suggestions: List<String> = emptyList(),
)

interface Ime {
    val connection: InputConnection?
    val editorInfo: EditorInfo?
    fun switchKeyboard()
    fun hideKeyboard()

    /** Hands the keys to whatever is drawing them. */
    fun show(rows: List<Row>, shift: Shift)

    /**
     * The word being typed has changed; work out what to offer for it.
     *
     * Handed over rather than answered here because looking a word up takes long enough to drop a frame, so it
     * happens off the thread the keys are drawn on.
     */
    fun suggest(word: String)

    /**
     * The same, knowing the word before it: lowercase, [SENTENCE_START] at the start of a sentence, or empty when
     * it is not known. With [word] empty this asks what might come next.
     */
    fun suggest(word: String, previous: String) = suggest(word)

    /**
     * A word was finished. Keep it if it is worth keeping.
     *
     * Only ever called for fields that allow it - never a password, never one that asked not to be learned from.
     */
    fun learn(word: String)

    /**
     * The same, saying whether the word was corrected as it was finished. A typo that was fixed is not a word to
     * learn; one left exactly as typed is evidence it was meant.
     */
    fun learn(word: String, corrected: Boolean) = learn(word)

    /** Swap the letters for the emoji grid, or back again. */
    fun showEmoji(showing: Boolean)

    /** An emoji typed from outside the grid, put at the front of its recents as a tap there would be. */
    fun rememberEmoji(emoji: String) {}

    /** Swap the letters for what has been copied lately, or back. */
    fun showClipboard(showing: Boolean)

    /**
     * The toolbar's Copy was pressed. The app does the copying, so the clipboard changes a moment later; this is
     * the keyboard's cue to look then, which is the one moment besides a field opening that it has reason to.
     */
    fun copied() {}

    /** Hand typing to the phone's voice keyboard. Keyd comes back when it is done. */
    fun startVoice() {}

    /** Swap the letters for the cursor pad, or back. */
    fun showCursorPad(showing: Boolean) {}

    /** The one-handed rail was used. Keep the new side as the setting, and hand it back to the keys. */
    fun oneHanded(side: OneHanded) {}

    /**
     * A correction stood: the next word began and it was not undone. Counted off the typing thread, like [learn],
     * and under the same rules - never from a password field, never when learning is off.
     */
    fun fixStood(typed: String, replacement: String) {}

    /** A correction was put back with backspace. Counted the same way. */
    fun putBack(typed: String) {}

    /** The strip asked, and was answered: Keep or Always when [accepted], No when not. */
    fun answered(offer: Insights.Offer, accepted: Boolean) {}

    /** How much is selected now, for the toolbar, or null when nothing is or the toolbar should not say. */
    fun selected(selection: Selected?) {}

    /** The strip's tip was answered with Got it. */
    fun tipDone(tip: Tip) {}

    /** A gesture with a tip was used: its tip is not needed any more. */
    fun gestureUsed(tip: Tip) {}

    /** The gap after a word, with Suggestions off: nothing is offered there, but a tip may take it. */
    fun quietGap() {}
    /** Keyd's languages that are turned on in Android, in Android's order. Empty if Android will not say. */
    fun languages(): List<Language> = emptyList()

    /** Switch to one of those, the way Android's own picker would. */
    fun switchLanguage(language: Language) {}

    /** Android's list of every keyboard on the phone. */
    fun pickKeyboard() {}

    /** Keyd's own Languages page in Settings. */
    fun openLanguageSettings() {}
}

/**
 * What every key does. The service is wiring: this is the keyboard's behaviour, and it holds the state a keyboard has
 * between presses - which layer is showing, whether shift is on for one letter or locked, and what the field allows.
 */
class TextActions(private val ime: Ime) : KeyboardView.Listener {

    var rules = FieldRules()
        private set
    var layer = Layer.LETTERS
        private set
    var shift = Shift.OFF
        private set

    /**
     * Whether shift is on for one letter because the person tapped it, rather than because a sentence is starting.
     * Only then does backspace delete forward: after a full stop shift comes on by itself, and a backspace there is
     * someone taking back the space they just typed.
     */
    private var shiftByHand = false

    /**
     * This backspace press deletes the character after the cursor, decided when it went down and kept for every
     * repeat of the hold, since the shift that decided it is used up by the first delete.
     */
    private var forward = false

    /** What the person has chosen. Re-read whenever a field opens, so a change takes effect without a restart. */
    var settings = Settings()

    /** Which language the keyboard is in. Android decides, through the subtype the person picked. */
    var language = Language.ENGLISH

    /**
     * Characters typed from the keys since this field opened: a count, nothing more. A gesture tip waits for some, so
     * it never greets someone the moment the keyboard appears.
     */
    var typedHere = 0
        private set

    /** Whether the last thing typed was the space that ended a word, for the double-space full stop. */
    private var lastWasSpace = false

    /**
     * The word being typed, tracked as it is typed.
     *
     * The alternative is asking the app what is before the cursor after every keystroke, which is a blocking call
     * into another process for something this already knows. It is dropped whenever that knowledge could be stale -
     * a new field, a moved cursor, a paste - rather than trusted past its usefulness.
     */
    private val word = StringBuilder()

    /**
     * The word before the one being typed, lowercase: what the strip predicts from, and what tells "wont" from
     * "won't". Empty when it is not known - after a comma, a moved cursor, a backspace into the text before - and
     * [SENTENCE_START] after a full stop, which is a fact worth knowing in itself.
     */
    private var previous = ""

    /**
     * Whether the cursor is where a new word would begin: after a space, a new line, a taken suggestion, or at the
     * start of a field. Only there does the strip offer what comes next - straight after a full stop, a tapped
     * word would land with no space before it.
     */
    private var wordStart = false

    /**
     * A word that was underlined, waiting to be learned until it is known that it stayed as typed: the next word
     * beginning says it did. Backspace, a moved cursor or a tap into the text may be it being fixed, and drop it.
     */
    private var underlined: String? = null

    /** Everything the suggestion thread worked out about the word being typed. Null until it has. */
    private var verdict: Verdict? = null

    /** What the last autocorrect replaced, so the next backspace can put it back. */
    private var undo: Pair<String, String>? = null

    /**
     * The last correction, until it is known whether it stood: the next word beginning says it did, backspace says
     * it did not. Only ever set where it may be counted at all.
     */
    private var standing: Pair<String, String>? = null

    /** Where the selection is, as the editor last said. -1 until it has said anything. */
    private var selStart = -1
    private var selEnd = -1

    /** Whether what is typed here may be counted or kept. The same test for learning a word and counting a fix. */
    private val remembering get() = settings.learn && !rules.ephemeral

    /**
     * Told, from the suggestion thread, what to do if this word is finished now.
     *
     * The decision is made there because it needs the dictionary; it is applied here, the instant a space arrives,
     * with no lookup on the typing thread at all.
     */
    fun offered(answer: Verdict) {
        verdict = answer
    }

    private fun wordChanged() {
        val quiet = rules.password || rules.noSuggestions || !settings.suggestions
        val context = if (quiet || rules.address || (word.isEmpty() && !wordStart)) "" else previous
        ime.suggest(if (quiet) "" else word.toString(), context)
        // Suggestions off turns off the guessing, not the strip, and the gap after a word is the only place a tip is
        // ever shown. Asked for here because the gap above has no word before it when suggestions are off, so a tip
        // never had its turn. Not where the field itself is quiet: a password, an address, or an app that asked.
        if (!settings.suggestions && !rules.password && !rules.noSuggestions && !rules.address &&
            word.isEmpty() && wordStart && previous.isNotEmpty()
        ) {
            ime.quietGap()
        }
    }

    /**
     * Two spaces in a row become a full stop and a space.
     *
     * Every phone keyboard has done this since the first one, and it is the fastest way to end a sentence with a
     * thumb. It only fires after a letter, so pressing space twice in empty space, or after punctuation, still
     * gives two spaces - which is what someone doing it deliberately wanted.
     */
    private fun doubleSpace(text: String): Boolean {
        if (!settings.doubleSpaceFullStop || text != " " || !lastWasSpace || rules.address) return false
        val connection = ime.connection ?: return false
        val before = connection.getTextBeforeCursor(2, 0)?.toString() ?: return false
        if (before.length != 2 || before[1] != ' ' || !before[0].isLetterOrDigit()) return false
        connection.beginBatchEdit()
        connection.deleteSurroundingText(1, 0)
        connection.commitText(". ", 1)
        connection.endBatchEdit()
        lastWasSpace = false
        previous = SENTENCE_START
        wordStart = true
        word.setLength(0)
        wordChanged()
        recapitalize()
        return true
    }

    /**
     * The word just ended, by a space or a full stop or anything else that is not a letter.
     *
     * This is the only moment a word is offered for learning: while it is still being typed it is a prefix, and
     * every prefix of every word is not something worth remembering.
     */
    private fun finished(ending: String = " ") {
        val done = word.toString()
        word.setLength(0)
        if (done.isEmpty()) return
        // Only this word's verdict counts. A slower answer about the word before it is thrown away here rather
        // than applied to whatever happens to be under the cursor now.
        val answer = verdict?.takeIf { it.word == done }
        verdict = null
        previous = done.lowercase()
        // An address is left exactly as typed: "keyd.dev" ends the word "keyd" at the period, and a keyboard that
        // "fixed" it would be typing somewhere else.
        if (rules.address) return
        val fix = answer?.correction?.takeIf { Settings.correcting(settings) }
        val marked = fix == null && settings.spellCheck && answer?.misspelled == true && !rules.ephemeral
        if (remembering && !marked) ime.learn(done, corrected = fix != null)
        underlined = if (remembering && marked) done else null
        // "i" is "I". That is capitalization rather than correction, so it follows the capitals setting, and it is
        // left alone wherever the app asked for no suggestions - a code editor, a username.
        // Not before a full stop, a bracket or a colon, where "i" is more likely a numeral or a list item: "i.",
        // "i)", "part i:".
        val capital = if (settings.autoCapitalise && !rules.noSuggestions && ending.firstOrNull() !in NUMERAL_ENDS) {
            Contractions.capitalI(done, language)
        } else {
            null
        }
        when {
            fix != null -> {
                autocorrect(done, fix)
                previous = fix.substringAfterLast(' ').lowercase()
            }
            capital != null -> autocorrect(done, capital, counted = false)
            settings.spellCheck && answer?.misspelled == true -> underline(done, answer.suggestions)
        }
    }

    /**
     * Replaces the word just finished, if the suggestion thread decided one was worth replacing.
     *
     * The ending that finished the word - a space, a full stop - has already been typed, so what comes out is
     * removed and put back with the word corrected and the ending kept exactly as it was.
     */
    private fun autocorrect(typed: String, replacement: String, counted: Boolean = true) {
        undo = null
        standing = null
        val connection = ime.connection ?: return
        if (rules.password) return
        // The ending is whatever was typed after the word: a space, a full stop, a bracket.
        val tail = connection.getTextBeforeCursor(typed.length + 1, 0)?.toString() ?: return
        if (tail.length != typed.length + 1 || !tail.startsWith(typed)) return
        val ending = tail.substring(typed.length)
        connection.beginBatchEdit()
        connection.deleteSurroundingText(tail.length, 0)
        connection.commitText(replacement + ending, 1)
        connection.endBatchEdit()
        undo = typed to (replacement + ending)
        if (remembering && counted) standing = typed to replacement
    }

    private fun forget() {
        // The cursor went somewhere else: what is typed next is not the word after the correction.
        standing = null
        underlined = null
        previous = ""
        wordStart = false
        // Asked again even with no word, so a prediction for where the cursor used to be does not stay on screen.
        word.setLength(0)
        wordChanged()
    }

    /** A new field: read what it asks for, start on the right layer, and capitalise if it wants that. */
    fun startInput(info: EditorInfo?) {
        rules = Layouts.rulesFor(info)
        typedHere = 0
        layer = if (rules.kind == FieldKind.NUMBER || rules.kind == FieldKind.PHONE) Layer.NUMBERS else Layer.LETTERS
        val capitals = autoCaps(info)
        shift = if (settings.autoCapitalise && capitals) Shift.ONCE else Shift.OFF
        shiftByHand = false
        forward = false
        // The field says whether the cursor is at the start of a sentence; that is all that is known about before it.
        previous = if (capitals) SENTENCE_START else ""
        wordStart = capitals
        word.setLength(0)
        standing = null
        underlined = null
        wordChanged()
        refresh()
        // The last field's toolbar goes whatever this one says. Starting at -1 and then being told -1 is no change,
        // which used to leave the count and Cut, Copy and Paste showing over a field with nothing selected.
        selStart = -1
        selEnd = -1
        ime.selected(null)
        selectionChanged(info?.initialSelStart ?: -1, info?.initialSelEnd ?: -1)
    }

    /**
     * The editor says the selection moved. Only when something is actually selected is the text read, once per
     * change, to count it; a cursor moving while someone types costs nothing. The text is counted and let go: it is
     * never kept, and never logged.
     */
    fun selectionChanged(start: Int, end: Int) {
        if (selectionMoved(start, end)) countSelection()
    }

    /**
     * Where the selection is now, noted without reading it. True when it moved. The service counts it separately,
     * through [countSelection], once a slide from shift has stopped moving it.
     */
    fun selectionMoved(start: Int, end: Int): Boolean {
        if (start == selStart && end == selEnd) return false
        selStart = start
        selEnd = end
        return true
    }

    /** Reads and counts what is selected now, for the toolbar. */
    fun countSelection() = ime.selected(selection())

    private fun selection(): Selected? {
        if (!settings.selectionTools || rules.password || selStart < 0 || selEnd < 0 || selStart == selEnd) return null
        if (Selected.tooLong(abs(selEnd - selStart))) return Selected(Selected.CAP, 0, capped = true)
        val text = ime.connection?.getSelectedText(0) ?: return null
        if (text.isEmpty()) return null
        return Selected.of(text)
    }

    fun refresh() =
        ime.show(Layouts.rows(layer, shift != Shift.OFF, rules, settings.numberRow, language), shift)

    /** Sentence capitals, but only when the field asked for them and there is nothing typed yet. */
    private fun autoCaps(info: EditorInfo?): Boolean {
        if (rules.password || info == null) return false
        return ime.connection?.getCursorCapsMode(info.inputType)?.let { it != 0 } == true
    }

    override fun onText(text: String) {
        undo = null
        typedHere += text.length
        if (doubleSpace(text)) return
        // The key already carries the right case: the layout builds an upper-case key when shift is on.
        ime.connection?.commitText(text, 1) ?: return
        // A letter continues the word; anything else - a space, a full stop, a bracket - ends it.
        if (text.length == 1 && (text[0].isLetter() || text[0] == '\'')) {
            // The first letter of the next word, with no backspace in between: the correction before it stood, and
            // so did a word that was underlined.
            if (word.isEmpty()) {
                standing?.let { (typed, replacement) -> ime.fixStood(typed, replacement) }
                underlined?.let { ime.learn(it, corrected = false) }
                underlined = null
            }
            standing = null
            word.append(text)
        } else {
            finished(text)
            // A space keeps the word before; a full stop starts a sentence; anything else - a comma, a bracket, a
            // digit - puts too much between the two words for one to say anything about the next.
            previous = when {
                text == " " -> previous
                text.lastOrNull() in SENTENCE_ENDS -> SENTENCE_START
                else -> ""
            }
        }
        wordStart = text.lastOrNull()?.isWhitespace() == true
        lastWasSpace = text == " "
        wordChanged()
        if (shift == Shift.ONCE) {
            shift = Shift.OFF
            shiftByHand = false
            refresh()
        }
        // A space or a period may have started a sentence: ask the app again, the way it was asked when the field
        // opened. Letters never can, so they skip the round trip.
        if (!text[0].isLetter()) recapitalize()
    }

    /**
     * Shift for the start of a sentence, asked of the app wherever the cursor may have moved to a new one.
     *
     * It used to be asked only when a field opened, so a period and a space never brought the capital back, and
     * neither did deleting everything: "the quick brown fox" came out without its capital T after a clear. Caps lock
     * is left alone, since the person chose it.
     */
    private fun recapitalize() {
        if (shift == Shift.LOCKED || !settings.autoCapitalise) return
        val wanted = if (autoCaps(ime.editorInfo)) Shift.ONCE else Shift.OFF
        if (wanted != shift) {
            shift = wanted
            shiftByHand = false
            refresh()
        }
    }

    /**
     * Marks a finished word the dictionary has never heard of.
     *
     * The squiggle is the editor's own, drawn from a [SuggestionSpan] - the same mechanism the platform's spell
     * checker uses, so it looks exactly like a misspelling looks everywhere else on the phone rather than like
     * something this keyboard invented.
     *
     * The span carries our suggestions too, and is flagged so the editor offers them when the word is tapped.
     * Samsung's underline tells you a word is wrong and leaves you to fix it; this one brings the answers with it.
     */
    private fun underline(typed: String, suggestions: List<String>) {
        val connection = ime.connection ?: return
        if (rules.ephemeral) return
        val tail = connection.getTextBeforeCursor(typed.length + 1, 0)?.toString() ?: return
        if (tail.length != typed.length + 1 || !tail.startsWith(typed)) return
        val marked = SpannableString(tail)
        marked.setSpan(
            SuggestionSpan(
                Locale.US,
                suggestions.take(MAX_SUGGESTIONS_IN_SPAN).toTypedArray(),
                SuggestionSpan.FLAG_MISSPELLED or SuggestionSpan.FLAG_EASY_CORRECT,
            ),
            0,
            typed.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        connection.beginBatchEdit()
        connection.deleteSurroundingText(tail.length, 0)
        connection.commitText(marked, 1)
        connection.endBatchEdit()
    }

    /**
     * Shift held down under another finger, then backspace: this press deletes forward. Only while it is held: a
     * shift tapped for the next word's capital, then a backspace to fix a typo, is the commonest correction there
     * is, and it has to go backwards.
     */
    override fun onBackspaceStart(shiftHeld: Boolean) {
        forward = shiftHeld
    }

    override fun onBackspace() {
        val connection = ime.connection ?: return
        val ahead = forward
        forward = false
        if (ahead) {
            // A selection goes whole whichever way the key deletes, which is what the ordinary delete below does.
            val selected = connection.getSelectedText(0)
            if (selected.isNullOrEmpty()) {
                deleteAhead(connection)
                return
            }
            shiftUsed()
        }
        // Backspace straight after a correction puts back what was actually typed. This is the whole reason a
        // correction is allowed to happen on its own: it is never more than one key away from being undone.
        undo?.let { (typed, replaced) ->
            undo = null
            val before = connection.getTextBeforeCursor(replaced.length, 0)?.toString()
            if (before == replaced) {
                connection.beginBatchEdit()
                connection.deleteSurroundingText(replaced.length, 0)
                connection.commitText(typed + replaced.takeLast(1), 1)
                connection.endBatchEdit()
                if (standing != null) ime.putBack(typed)
                standing = null
                previous = typed.lowercase()
                wordStart = replaced.last().isWhitespace()
                wordChanged()
                return
            }
        }
        standing = null
        underlined = null
        wordStart = false
        // Deleting back into the text before: the word before is no longer the one that was finished.
        if (word.isEmpty()) previous = ""
        // Asking for the selection is a blocking call into the app. Worth it once, to delete a selection whole.
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            connection.commitText("", 1)
            word.setLength(0)
        } else {
            connection.deleteSurroundingText(1, 0)
            if (word.isNotEmpty()) word.setLength(word.length - 1)
        }
        wordChanged()
        recapitalize()
    }

    /**
     * The character after the cursor, whole: an emoji with its skin tone or a flag goes in one press, as it does
     * going backwards. Nothing at the end of the text. What is before the cursor is untouched, so the word being
     * typed and a correction waiting to be put back both still stand.
     */
    private fun deleteAhead(connection: InputConnection) {
        val after = connection.getTextAfterCursor(AHEAD, 0)
        if (!after.isNullOrEmpty()) connection.deleteSurroundingText(0, Words.firstCharacterLength(after))
        lastWasSpace = false
        shiftUsed()
    }

    /** Shift has done its one thing, the way a letter uses it up. */
    private fun shiftUsed() {
        if (shift != Shift.ONCE) return
        shift = Shift.OFF
        shiftByHand = false
        refresh()
    }

    /** Holding the key down: whatever was selected went with the first delete, so don't ask again. */
    override fun onBackspaceRepeat() {
        val connection = ime.connection ?: return
        if (forward) return deleteAhead(connection)
        connection.deleteSurroundingText(1, 0)
        if (word.isNotEmpty()) word.setLength(word.length - 1) else previous = ""
        underlined = null
        wordStart = false
        wordChanged()
        recapitalize()
    }

    /** Swiping the backspace takes a word, which is what every other keyboard does and what hands expect. */
    override fun onDeleteWord() {
        val connection = ime.connection ?: return
        val before = connection.getTextBeforeCursor(64, 0) ?: return
        val remove = Words.charsToRemoveForWord(before)
        if (remove > 0) connection.deleteSurroundingText(remove, 0)
        forget()
        recapitalize()
    }

    override fun onShift() {
        shift = when (shift) {
            Shift.OFF -> Shift.ONCE
            Shift.ONCE -> Shift.LOCKED
            Shift.LOCKED -> Shift.OFF
        }
        shiftByHand = shift == Shift.ONCE
        refresh()
    }

    override fun onLayer(layer: Layer) {
        this.layer = layer
        refresh()
    }

    override fun onAction() {
        val connection = ime.connection ?: return
        val action = ime.editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_UNSPECIFIED
        finished("\n")
        // A new line, or a message sent: either way what comes next starts a sentence.
        previous = SENTENCE_START
        wordStart = true
        wordChanged()
        when {
            rules.multiline -> connection.commitText("\n", 1)
            // The app asked for Enter itself: some search boxes and web forms listen for the key, not the action.
            rules.plainEnter -> sendKey(connection, KeyEvent.KEYCODE_ENTER)
            // performEditorAction's answer only says whether the call reached the app, not whether the app did
            // anything with it, so there is no "it ignored Search" to fall back from.
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED ->
                connection.performEditorAction(action)
            else -> sendKey(connection, KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onSwitchKeyboard() = ime.switchKeyboard()

    override fun onHide() = ime.hideKeyboard()

    // The other panels have backspaces of their own, which always delete backwards: shift is not on screen there.
    override fun onEmojiPanel() {
        forward = false
        ime.showEmoji(true)
    }

    override fun languageChoices(): List<Language> = ime.languages()

    override fun onLanguage(language: Language) = ime.switchLanguage(language)

    override fun onOtherKeyboards() = ime.pickKeyboard()

    override fun onLanguageSettings() = ime.openLanguageSettings()

    override fun onOffer(offer: Insights.Offer, accepted: Boolean) = ime.answered(offer, accepted)

    override fun onTipDone(tip: Tip) = ime.tipDone(tip)

    override fun onGesture(tip: Tip) = ime.gestureUsed(tip)

    /**
     * A suggestion, taken.
     *
     * Only ever from a tap: nothing here runs on its own. What was typed is removed and the chosen word put in its
     * place, with the space that was going to follow it anyway.
     */
    override fun onSuggestion(chosen: String) {
        undo = null
        val connection = ime.connection ?: return
        val typed = word.toString()
        if (typed.isEmpty()) {
            if (wordStart) predicted(connection, chosen)
            return
        }
        // Checked before deleting, because this deletes by count. The word is tracked as it is typed, and if the
        // app has changed the text underneath us - a formatter, an autofill, a paste we did not see - that count
        // would take a bite out of something the person wrote. One call, on a tap, to never do that.
        val before = connection.getTextBeforeCursor(typed.length, 0)
        if (before != null && before.toString() != typed) {
            word.setLength(0)
            wordChanged()
            return
        }
        connection.beginBatchEdit()
        connection.deleteSurroundingText(typed.length, 0)
        connection.commitText("$chosen ", 1)
        connection.endBatchEdit()
        previous = chosen.substringAfterLast(' ').lowercase()
        lastWasSpace = false
        wordStart = true
        word.setLength(0)
        wordChanged()
    }

    /**
     * The emoji at the end of the strip, taken: the word stays exactly as typed, and the emoji goes in after it with a
     * space either side, "pizza 🍕 ", ready for the next word. The emoji follows the word rather than replacing it,
     * as Gboard's and iOS's do, because the word was the point and the emoji is a flourish on it.
     *
     * Nothing is learned from it and nothing is corrected: the word was not finished by a space, so the pending fix,
     * if there was one, is simply not made. It goes in the emoji recents, the same as a tap on the grid.
     */
    override fun onSuggestedEmoji(emoji: String) {
        undo = null
        val connection = ime.connection ?: return
        if (word.isEmpty()) return
        connection.commitText(" $emoji ", 1)
        ime.rememberEmoji(emoji)
        // The emoji is not a word to predict from, and a space after it is not the second of a double space.
        previous = ""
        lastWasSpace = false
        wordStart = true
        word.setLength(0)
        wordChanged()
    }

    /**
     * A word the strip predicted, taken after a space: it goes in with a space after it, as a typed word would.
     *
     * The same as typing its first letter as far as everything else is concerned - a correction before it has
     * stood, and a capital waiting for one letter has been used.
     */
    private fun predicted(connection: InputConnection, chosen: String) {
        standing?.let { (typed, replacement) -> ime.fixStood(typed, replacement) }
        standing = null
        underlined?.let { ime.learn(it, corrected = false) }
        underlined = null
        // Asked rather than trusted, like a suggestion taken mid-word: if the app moved the cursor or changed the
        // text since the space, the word still goes in as a word of its own.
        val before = connection.getTextBeforeCursor(1, 0)
        val gap = if (before.isNullOrEmpty() || before.last().isWhitespace()) "" else " "
        connection.commitText("$gap$chosen ", 1)
        previous = chosen.substringAfterLast(' ').lowercase()
        lastWasSpace = false
        wordStart = true
        if (shift == Shift.ONCE) {
            shift = Shift.OFF
            shiftByHand = false
            refresh()
        }
        wordChanged()
    }

    /** An emoji is text like any other, which is the whole reason it can be typed by a keyboard at all. */
    fun onEmoji(emoji: String) {
        ime.connection?.commitText(emoji, 1)
        forget()
    }

    // The editing a field always supports, through Android's own menu actions rather than by reading the text.
    override fun onSelectAll() = menu(android.R.id.selectAll)

    override fun onClipboardPanel() {
        forward = false
        ime.showClipboard(true)
    }

    override fun onCopy() {
        menu(android.R.id.copy)
        ime.copied()
    }

    override fun onPaste() = menu(android.R.id.paste)

    override fun onCut() = menu(android.R.id.cut)

    /**
     * Undo and redo as Ctrl+Z and Ctrl+Shift+Z, what a hardware keyboard sends. Android's own text fields have
     * understand them, as do most editors and web pages; an app that ignores them does nothing, and nothing breaks.
     */
    override fun onUndo() {
        undo = null
        onMove(KeyEvent.KEYCODE_Z, CTRL)
    }

    override fun onRedo() {
        undo = null
        onMove(KeyEvent.KEYCODE_Z, CTRL or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
    }

    /** Sliding from shift: the arrows the cursor pad sends while selecting, one per step, with Shift held. */
    override fun onSelectMove(steps: Int) {
        val connection = ime.connection ?: return
        val (code, meta) = CursorPad.keyEvents(if (steps > 0) CursorPad.Move.RIGHT else CursorPad.Move.LEFT, true)
        repeat(min(abs(steps), MAX_CURSOR_STEPS)) { sendKey(connection, code, meta) }
        forget()
    }

    /**
     * The selection, restyled in place and still selected, so another style or a copy can follow. Only what the
     * cap allows is read; past it nothing changes. Never in a password field.
     */
    override fun onStyle(style: TextStyle) {
        if (rules.password) return
        val connection = ime.connection ?: return
        // Too long is known from where the selection is, so it is never read just to be turned down.
        if (selStart >= 0 && selEnd >= 0 && Selected.tooLong(abs(selEnd - selStart))) return
        val text = connection.getSelectedText(0) ?: return
        if (text.isEmpty() || Selected.tooLong(text.length)) return
        val styled = style.on(text)
        val start = min(selStart, selEnd)
        connection.beginBatchEdit()
        connection.commitText(styled, 1)
        if (start >= 0) connection.setSelection(start, start + styled.length)
        connection.endBatchEdit()
        undo = null
        forget()
    }

    override fun onVoice() = ime.startVoice()

    override fun onCursorPad() {
        forward = false
        ime.showCursorPad(true)
    }

    override fun onOneHanded(side: OneHanded) = ime.oneHanded(side)

    /**
     * One step from the cursor pad, as the key a hardware keyboard would send: an arrow, Ctrl and an arrow for a
     * word, Home or End for the line, with Shift held while selecting. Editors, terminals and web pages all
     * understand those, which a setSelection call worked out from text we can't fully see would not.
     */
    fun onMove(code: Int, meta: Int) {
        val connection = ime.connection ?: return
        sendKey(connection, code, meta)
        forget()
        recapitalize()
    }

    private fun sendKey(connection: InputConnection, code: Int, meta: Int) {
        val time = android.os.SystemClock.uptimeMillis()
        connection.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0, meta))
        connection.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun menu(action: Int) {
        ime.connection?.performContextMenuAction(action)
        forget()   // what is before the cursor is no longer what we thought
    }

    /** Arrow keys rather than a selection call: they behave the same in an editor, a terminal and a web page. */
    override fun onCursor(steps: Int) {
        val connection = ime.connection ?: return
        val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(min(abs(steps), MAX_CURSOR_STEPS)) { sendKey(connection, code) }
        forget()
        recapitalize()
    }

    private fun sendKey(connection: InputConnection, code: Int) {
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private companion object {
        /** However fast the swipe, one gesture shouldn't fling the cursor across a paragraph. */
        const val MAX_CURSOR_STEPS = 8

        const val CTRL = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON

        /** Enough for the editor's menu to be useful without becoming a list to read. */
        const val MAX_SUGGESTIONS_IN_SPAN = 3

        /** How far past the cursor to read for the next character: longer than any emoji sequence in use. */
        const val AHEAD = 32
    }
}
