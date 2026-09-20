package com.mccal.folio.keys

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlin.math.abs
import kotlin.math.min

/**
 * The little that editing needs from the keyboard service. Keeping it to this means the behaviour below can be driven
 * without an input method running, which is the only way to check what the keys actually do to someone's text.
 */
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
     * A word was finished. Keep it if it is worth keeping.
     *
     * Only ever called for fields that allow it - never a password, never one that asked not to be learned from.
     */
    fun learn(word: String)

    /** Swap the letters for the emoji grid, or back again. */
    fun showEmoji(showing: Boolean)
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
     * The word being typed, tracked as it is typed.
     *
     * The alternative is asking the app what is before the cursor after every keystroke, which is a blocking call
     * into another process for something this already knows. It is dropped whenever that knowledge could be stale -
     * a new field, a moved cursor, a paste - rather than trusted past its usefulness.
     */
    private val word = StringBuilder()

    /** The word the last correction was worked out for, and what to replace it with. Both null when there is none. */
    private var correctFor: String? = null
    private var correction: String? = null

    /** What the last autocorrect replaced, so the next backspace can put it back. */
    private var undo: Pair<String, String>? = null

    /**
     * Told, from the suggestion thread, what to do if this word is finished now.
     *
     * The decision is made there because it needs the dictionary; it is applied here, the instant a space arrives,
     * with no lookup on the typing thread at all.
     */
    fun offered(forWord: String, replacement: String?) {
        correctFor = forWord
        correction = replacement
    }

    private fun wordChanged() = ime.suggest(if (rules.password) "" else word.toString())

    /**
     * The word just ended, by a space or a full stop or anything else that is not a letter.
     *
     * This is the only moment a word is offered for learning: while it is still being typed it is a prefix, and
     * every prefix of every word is not something worth remembering.
     */
    private fun finished() {
        val done = word.toString()
        word.setLength(0)
        if (done.isEmpty()) return
        if (!rules.ephemeral) ime.learn(done)
        autocorrect(done)
    }

    /**
     * Replaces the word just finished, if the suggestion thread decided one was worth replacing.
     *
     * The ending that finished the word - a space, a full stop - has already been typed, so what comes out is
     * removed and put back with the word corrected and the ending kept exactly as it was.
     */
    private fun autocorrect(typed: String) {
        undo = null
        val replacement = correction?.takeIf { correctFor == typed } ?: return
        correction = null
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
    }

    private fun forget() {
        if (word.isEmpty()) return
        word.setLength(0)
        wordChanged()
    }

    /** A new field: read what it asks for, start on the right layer, and capitalise if it wants that. */
    fun startInput(info: EditorInfo?) {
        rules = Layouts.rulesFor(info)
        layer = if (rules.kind == FieldKind.NUMBER || rules.kind == FieldKind.PHONE) Layer.NUMBERS else Layer.LETTERS
        shift = if (autoCaps(info)) Shift.ONCE else Shift.OFF
        word.setLength(0)
        wordChanged()
        refresh()
    }

    fun refresh() = ime.show(Layouts.rows(layer, shift != Shift.OFF, rules), shift)

    /** Sentence capitals, but only when the field asked for them and there is nothing typed yet. */
    private fun autoCaps(info: EditorInfo?): Boolean {
        if (rules.password || info == null) return false
        return ime.connection?.getCursorCapsMode(info.inputType)?.let { it != 0 } == true
    }

    override fun onText(text: String) {
        undo = null
        // The key already carries the right case: the layout builds an upper-case key when shift is on.
        ime.connection?.commitText(text, 1) ?: return
        // A letter continues the word; anything else - a space, a full stop, a bracket - ends it.
        if (text.length == 1 && (text[0].isLetter() || text[0] == '\'')) {
            word.append(text)
        } else {
            finished()
        }
        wordChanged()
        if (shift == Shift.ONCE) {
            shift = Shift.OFF
            refresh()
        }
    }

    override fun onBackspace() {
        val connection = ime.connection ?: return
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
                wordChanged()
                return
            }
        }
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
    }

    /** Holding the key down: whatever was selected went with the first delete, so don't ask again. */
    override fun onBackspaceRepeat() {
        ime.connection?.deleteSurroundingText(1, 0) ?: return
        if (word.isNotEmpty()) word.setLength(word.length - 1)
        wordChanged()
    }

    /** Swiping the backspace takes a word, which is what every other keyboard does and what hands expect. */
    override fun onDeleteWord() {
        val connection = ime.connection ?: return
        val before = connection.getTextBeforeCursor(64, 0) ?: return
        val remove = Words.charsToRemoveForWord(before)
        if (remove > 0) connection.deleteSurroundingText(remove, 0)
        forget()
    }

    override fun onShift() {
        shift = when (shift) {
            Shift.OFF -> Shift.ONCE
            Shift.ONCE -> Shift.LOCKED
            Shift.LOCKED -> Shift.OFF
        }
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
        finished()
        wordChanged()
        when {
            rules.multiline -> connection.commitText("\n", 1)
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED ->
                connection.performEditorAction(action)
            else -> sendKey(connection, KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onSwitchKeyboard() = ime.switchKeyboard()

    override fun onHide() = ime.hideKeyboard()

    override fun onEmojiPanel() = ime.showEmoji(true)

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
        if (typed.isEmpty()) return
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
        word.setLength(0)
        wordChanged()
    }

    /** An emoji is text like any other, which is the whole reason it can be typed by a keyboard at all. */
    fun onEmoji(emoji: String) {
        ime.connection?.commitText(emoji, 1)
        forget()
    }

    // The editing a field always supports, through Android's own menu actions rather than by reading the text.
    override fun onSelectAll() = menu(android.R.id.selectAll)

    override fun onCopy() = menu(android.R.id.copy)

    override fun onPaste() = menu(android.R.id.paste)

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
    }

    private fun sendKey(connection: InputConnection, code: Int) {
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private companion object {
        /** However fast the swipe, one gesture shouldn't fling the cursor across a paragraph. */
        const val MAX_CURSOR_STEPS = 8
    }
}
