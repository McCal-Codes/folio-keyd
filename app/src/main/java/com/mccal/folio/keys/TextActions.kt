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

    /** A new field: read what it asks for, start on the right layer, and capitalise if it wants that. */
    fun startInput(info: EditorInfo?) {
        rules = Layouts.rulesFor(info)
        layer = if (rules.kind == FieldKind.NUMBER || rules.kind == FieldKind.PHONE) Layer.NUMBERS else Layer.LETTERS
        shift = if (autoCaps(info)) Shift.ONCE else Shift.OFF
        refresh()
    }

    fun refresh() = ime.show(Layouts.rows(layer, shift != Shift.OFF, rules), shift)

    /** Sentence capitals, but only when the field asked for them and there is nothing typed yet. */
    private fun autoCaps(info: EditorInfo?): Boolean {
        if (rules.password || info == null) return false
        return ime.connection?.getCursorCapsMode(info.inputType)?.let { it != 0 } == true
    }

    override fun onText(text: String) {
        // The key already carries the right case: the layout builds an upper-case key when shift is on.
        ime.connection?.commitText(text, 1) ?: return
        if (shift == Shift.ONCE) {
            shift = Shift.OFF
            refresh()
        }
    }

    override fun onBackspace() {
        val connection = ime.connection ?: return
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) connection.commitText("", 1) else connection.deleteSurroundingText(1, 0)
    }

    /** Swiping the backspace takes a word, which is what every other keyboard does and what hands expect. */
    override fun onDeleteWord() {
        val connection = ime.connection ?: return
        val before = connection.getTextBeforeCursor(64, 0) ?: return
        val remove = Words.charsToRemoveForWord(before)
        if (remove > 0) connection.deleteSurroundingText(remove, 0)
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
        when {
            rules.multiline -> connection.commitText("\n", 1)
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED ->
                connection.performEditorAction(action)
            else -> sendKey(connection, KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onSwitchKeyboard() = ime.switchKeyboard()

    override fun onHide() = ime.hideKeyboard()

    // The editing a field always supports, through Android's own menu actions rather than by reading the text.
    override fun onSelectAll() = menu(android.R.id.selectAll)

    override fun onCopy() = menu(android.R.id.copy)

    override fun onPaste() = menu(android.R.id.paste)

    private fun menu(action: Int) {
        ime.connection?.performContextMenuAction(action)
    }

    /** Arrow keys rather than a selection call: they behave the same in an editor, a terminal and a web page. */
    override fun onCursor(steps: Int) {
        val connection = ime.connection ?: return
        val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(min(abs(steps), MAX_CURSOR_STEPS)) { sendKey(connection, code) }
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
