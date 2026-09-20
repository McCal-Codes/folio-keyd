package com.mccal.folio.keys

import android.content.Context
import android.graphics.Region
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
     * Looking a word up takes long enough to drop a frame, so it happens on a thread of its own.
     *
     * One thread, one pending job: every keystroke replaces the one before it, so a fast typist is never queueing
     * up suggestions for words they have already finished. The short delay is what makes that true - mid-word
     * keystrokes arrive closer together than this, so most of them cost nothing at all.
     */
    private val thinking = HandlerThread("suggestions").apply { start() }
    private val background by lazy { Handler(thinking.looper) }
    private val main = Handler(Looper.getMainLooper())
    private var dictionary: Dictionary? = null
    private var proximity: Suggestions.Proximity? = null
    private var proximityFor: List<Placement>? = null

    /**
     * Tags the suggestion jobs, so cancelling them cancels only them.
     *
     * The dictionary is read on this same thread, and a keystroke arriving before that read has begun would
     * otherwise cancel it - leaving a keyboard that silently never suggests anything for the rest of the session.
     */
    private val suggesting = Any()

    /** Which word the strip is currently being worked out for. A slower answer to an older word is thrown away. */
    private var asked = 0

    override fun onCreate() {
        super.onCreate()
        // Read once, off the main thread: the keyboard has to be on screen before the dictionary is needed.
        background.post { dictionary = runCatching { Dictionary.load(this) }.getOrNull() }
    }

    override fun onDestroy() {
        super.onDestroy()
        thinking.quitSafely()
    }

    override fun suggest(word: String) {
        background.removeCallbacksAndMessages(suggesting)
        val mine = ++asked
        if (word.length < 2) {
            keyboard?.suggestions = emptyList()
            return
        }
        val keys = keyboard?.placements
        background.postDelayed(
            {
                val words = dictionary ?: return@postDelayed
                // Rebuilt whenever the keys have moved - unfolding splits the keyboard, and correcting against
                // where the keys used to be is worse than not correcting by proximity at all.
                if (keys != null && keys !== proximityFor) {
                    proximity = Suggestions.Proximity(keys)
                    proximityFor = keys
                }
                val found = runCatching { Suggestions.forWord(word, words, proximity) }.getOrDefault(emptyList())
                main.post {
                    // A job already running cannot be cancelled, so it checks on the way out whether the word it
                    // was asked about is still the word being typed. Without this a slow answer for "te" lands
                    // after a fast one for "teh" and the strip shows the wrong thing.
                    if (mine != asked) return@post
                    keyboard?.suggestions = if (found.isEmpty()) emptyList() else listOf(word) + found
                }
            },
            suggesting,
            THINK_MS,
        )
    }

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
        if (showing) emoji?.opened()
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
        val shown = listOfNotNull(keyboard, emoji).firstOrNull { it.visibility == View.VISIBLE }
        val keyboardDp = (shown?.height ?: 0) / density
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
        // Whichever panel is actually on screen. A hidden view keeps its last bounds, so asking the keyboard while
        // the emoji grid is showing would describe a window that is no longer the one being touched.
        val view = listOfNotNull(keyboard, emoji).firstOrNull { it.visibility == View.VISIBLE } ?: return
        if (view.width == 0 || view.height == 0) return
        val pad = (PANEL_PAD_DP * resources.displayMetrics.density).toInt()
        val top = view.top + pad
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(Region(view.left + pad, top, view.right - pad, view.bottom))
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

        /** Long enough that a fast typist skips most lookups, short enough not to feel behind. */
        const val THINK_MS = 40L

        /** The margin both panels leave around themselves. */
        const val PANEL_PAD_DP = 6f

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
