package com.mccal.folio.keys

import android.graphics.Region
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

    /**
     * Whether to take the whole screen and give the app an extracted field instead.
     *
     * Android's own answer is "yes, if the screen is short", which on a landscape phone hides a perfectly usable app
     * behind a full-screen text box. The question worth asking is whether anything of the app would still be visible
     * above the keys, so that is the one asked here.
     */
    override fun onEvaluateFullscreenMode(): Boolean {
        val info = currentInputEditorInfo
        if (info != null && info.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0) return false
        val density = resources.displayMetrics.density
        val keyboardDp = (keyboard?.height ?: 0) / density
        return roomAbove(resources.configuration.screenHeightDp.toFloat(), keyboardDp) < ROOM_FOR_THE_APP_DP
    }

    /**
     * The keyboard's window only covers what it draws.
     *
     * The board floats with a margin around it, and without this the whole margin belongs to the keyboard: a tap
     * just above the keys, meant for the app, would be swallowed by an invisible strip.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val view = keyboard ?: return
        val top = view.top + view.panelTop
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(
            Region(view.left + view.panelSide, top, view.right - view.panelSide, view.bottom),
        )
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

    private companion object {
        /** Less of the app than this left showing, and an extracted field is more use than a sliver. */
        const val ROOM_FOR_THE_APP_DP = 130f

        /** What is left of the window once the keyboard has taken its share. */
        fun roomAbove(screenHeightDp: Float, keyboardHeightDp: Float) = screenHeightDp - keyboardHeightDp
    }

    override fun switchKeyboard() {
        // The globe hands over to whatever the person picked next; Android decides what that is, not Folio.
        if (!switchToNextInputMethod(false)) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }
}
