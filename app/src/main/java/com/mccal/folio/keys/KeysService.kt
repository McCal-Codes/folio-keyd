package com.mccal.folio.keys

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * Folio Keys.
 *
 * This is wiring: it owns the view, hands it to [TextActions], and passes along what Android tells it. The behaviour -
 * what each key does, which layer is showing, how shift behaves - lives in [TextActions], where it can be tested.
 *
 * Nothing is stored and nothing is sent. The app holds no permissions at all, so what the keyboard sees while you type
 * cannot leave the phone even if a later version wanted it to.
 */
class KeysService : InputMethodService(), Ime {

    private val actions = TextActions(this)
    private var keyboard: KeyboardView? = null

    override fun onCreateInputView(): View =
        KeyboardView(this).also {
            it.listener = actions
            keyboard = it
            actions.refresh()
        }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        actions.startInput(info)
        keyboard?.rules = actions.rules   // one reading of the field, not two
    }

    // ---- Ime -----------------------------------------------------------------------------------------------------

    override val connection: InputConnection?
        get() = currentInputConnection

    override val editorInfo: EditorInfo?
        get() = currentInputEditorInfo

    override fun show(rows: List<Row>, shift: Shift) {
        keyboard?.let {
            it.shift = shift
            it.rows = rows
        }
    }

    override fun hideKeyboard() = requestHideSelf(0)

    override fun switchKeyboard() {
        // The globe hands over to whatever the person picked next; Android decides what that is, not Folio.
        if (!switchToNextInputMethod(false)) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }
}
