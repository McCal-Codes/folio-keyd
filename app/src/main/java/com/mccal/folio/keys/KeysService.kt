package com.mccal.folio.keys

import android.inputmethodservice.InputMethodService
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Folio Keys.
 *
 * The keyboard itself: it owns the [KeyboardView], reads what the field asks for, and is the only place that touches
 * the text. Nothing is stored and nothing is sent — the app holds no INTERNET permission, so what it sees while you
 * type cannot leave the phone even if a later version wanted it to.
 */
class KeysService : InputMethodService(), KeyboardView.Listener {

    private var keyboard: KeyboardView? = null
    private var layer = Layer.LETTERS
    private var rules = FieldRules()

    override fun onCreateInputView(): View =
        KeyboardView(this).also {
            it.listener = this
            keyboard = it
            refresh()
        }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        rules = Layouts.rulesFor(info)
        layer = if (rules.kind == FieldKind.NUMBER || rules.kind == FieldKind.PHONE) Layer.NUMBERS else Layer.LETTERS
        keyboard?.rules = rules
        keyboard?.shift = if (autoCaps()) Shift.ONCE else Shift.OFF
        refresh()
    }

    /** Sentence capitals, but only when the field asked for them and there is nothing typed yet. */
    private fun autoCaps(): Boolean {
        if (rules.password) return false
        val info = currentInputEditorInfo ?: return false
        val connection = currentInputConnection ?: return false
        return connection.getCursorCapsMode(info.inputType) != 0
    }

    private fun refresh() {
        val view = keyboard ?: return
        view.rows = Layouts.rows(layer, view.shift != Shift.OFF, rules)
    }

    // ---- what the keys do -------------------------------------------------------------------------------------

    override fun onText(text: String) {
        val view = keyboard ?: return
        // The key already carries the right case: the layout builds an upper-case key when shift is on.
        currentInputConnection?.commitText(text, 1)
        // A one-shot shift falls back to lower case after the letter it capitalised.
        if (view.shift == Shift.ONCE) {
            view.shift = Shift.OFF
            refresh()
        }
    }

    override fun onBackspace() {
        val connection = currentInputConnection ?: return
        val selected = connection.getSelectedText(0)
        if (!TextUtils.isEmpty(selected)) connection.commitText("", 1) else connection.deleteSurroundingText(1, 0)
    }

    /** Swiping the backspace takes a word, which is what every other keyboard does and what hands expect. */
    override fun onDeleteWord() {
        val connection = currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(64, 0) ?: return
        val remove = Words.charsToRemoveForWord(before)
        if (remove > 0) connection.deleteSurroundingText(remove, 0)
    }

    override fun onShift() {
        val view = keyboard ?: return
        view.shift = when (view.shift) {
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
        val info = currentInputEditorInfo
        val connection = currentInputConnection ?: return
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_UNSPECIFIED
        when {
            rules.multiline -> connection.commitText("\n", 1)
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED ->
                connection.performEditorAction(action)
            else -> sendKey(connection, KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onSwitchKeyboard() {
        // The globe hands over to whatever the person picked next; Android decides what that is, not Folio.
        if (!switchToNextInputMethod(false)) {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showInputMethodPicker()
        }
    }

    /** Arrow keys rather than a selection call: they behave the same in an editor, a terminal and a web page. */
    override fun onCursor(steps: Int) {
        val connection = currentInputConnection ?: return
        val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(minOf(kotlin.math.abs(steps), 8)) { sendKey(connection, code) }
    }

    private fun sendKey(connection: InputConnection, code: Int) {
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
