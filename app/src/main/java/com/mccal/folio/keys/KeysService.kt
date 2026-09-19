package com.mccal.folio.keys

import android.content.Context
import android.graphics.Region
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
    private var emoji: EmojiPanel? = null

    private val prefs by lazy { getSharedPreferences("keys", Context.MODE_PRIVATE) }

    /**
     * Both panels live in the window at once, and only one is visible.
     *
     * Building the emoji grid the first time someone taps the smiley would show them an empty keyboard for a frame,
     * and Android only asks for the input view once, so the pair is made together and swapped.
     */
    override fun onCreateInputView(): View {
        val keys = KeyboardView(this).also { it.listener = actions }
        val grid = EmojiPanel(this).also {
            it.visibility = View.GONE
            it.recents = Emoji.decode(prefs.getString(RECENTS, null))
            it.listener = object : EmojiPanel.Listener {
                override fun onEmoji(value: String) {
                    actions.onEmoji(value)
                    remember(value)
                }

                override fun onBackspace() = actions.onBackspace()

                override fun onLetters() = showEmoji(false)
            }
        }
        keyboard = keys
        emoji = grid
        actions.refresh()
        return FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(keys)
            addView(grid)
        }
    }

    private fun remember(value: String) {
        val grid = emoji ?: return
        val updated = Emoji.remember(grid.recents, value)
        grid.recents = updated
        prefs.edit().putString(RECENTS, Emoji.encode(updated)).apply()
    }

    override fun showEmoji(showing: Boolean) {
        keyboard?.visibility = if (showing) View.GONE else View.VISIBLE
        emoji?.visibility = if (showing) View.VISIBLE else View.GONE
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
        // A new field starts on the letters: nobody opens a password box wanting the emoji they left open.
        showEmoji(false)
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
        const val RECENTS = "emojiRecents"

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
