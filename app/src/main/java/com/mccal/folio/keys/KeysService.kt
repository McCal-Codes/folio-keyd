package com.mccal.folio.keys

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype

/**
 * Keyd.
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
    private var clipboard: ClipboardPanel? = null
    private var root: View? = null

    /**
     * Set once the editor has told us the cursor is behind the keys, and cleared when a new field is opened.
     *
     * It latches on purpose. Going fullscreen moves everything, which changes where the cursor is, which would
     * change the answer again - a keyboard flickering in and out of fullscreen while someone types is far worse
     * than either state. Once taking the screen is the right answer for a field, it stays the answer.
     */
    private var cursorHidden = false

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
    private var loadedFor: Language? = null
    private var learned: Learned? = null
    private var shortcuts: Shortcuts? = null
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
        DevLog.catchCrashes(this)
        spanTheCutout()
        // Read once, off the main thread: the keyboard has to be on screen before the dictionary is needed.
        background.post {
            loadDictionary(actions.language)
            learned = Learned.decode(prefs.getString(LEARNED, null))
            shortcuts = Shortcuts.decode(prefs.getString(SHORTCUTS, null))
        }
    }

    /**
     * Lets the keyboard's window reach into the display cutout.
     *
     * Without this the system keeps the window clear of the camera, which on a Fold's cover screen held sideways
     * means a black bar down one edge and a keyboard shoved across into the other - and the field being typed into
     * pushed out beside it rather than sitting above it. The keys themselves still keep off the camera: that is
     * what the cutout inset in [KeyboardView] is for. This is about the window, not the keys.
     */
    private fun spanTheCutout() {
        runCatching {
            val window = window?.window ?: return
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    /**
     * Takes whatever is on the clipboard now, if the field allows it and the setting is on.
     *
     * Called when a field opens and again after the toolbar's Copy, which are the two moments the keyboard is on
     * screen and the clipboard has just changed. There is no listener: Android only tells the focused app anyway,
     * and a keyboard that polls in the background would be exactly the thing this app promises not to be.
     */
    private fun rememberClip(settings: Settings) {
        if (!settings.clipboardHistory) return
        val text = Clipboard.readable(Clipboard.manager(this), actions.rules) ?: return
        val now = System.currentTimeMillis()
        val history = Clipboard.load(prefs, now)
        // Every field that opens would otherwise rewrite the list to say the same thing. A keyboard opens a lot.
        if (history.firstOrNull()?.text == text) return
        // Forgotten or cleared, but Android still has it: saving it again would undo what they just did.
        if (Clipboard.wasLetGo(prefs, text)) return
        Clipboard.moveOn(prefs)
        Clipboard.save(prefs, Clipboard.remembering(history, text, now))
    }

    /**
     * Reads the word list for a language, and keeps it until the language changes.
     *
     * Always on the suggestion thread: it is most of a megabyte of parsing, and doing it when someone taps the
     * globe would freeze the keyboard at the exact moment they are looking at it.
     */
    private fun loadDictionary(language: Language) {
        if (loadedFor == language && dictionary != null) return
        val started = android.os.SystemClock.elapsedRealtime()
        dictionary = runCatching { Dictionary.load(this, language) }
            .onFailure { DevLog.error(this, "Dictionary.load", it) }.getOrNull()
        DevLog.event(this, "dictionary", "ms" to android.os.SystemClock.elapsedRealtime() - started,
            "loaded" to if (dictionary != null) 1 else 0)
        loadedFor = if (dictionary != null) language else null
        // Which keys are where has changed, so what counts as a near miss has changed with it.
        proximity = null
        proximityFor = null
    }

    /**
     * Android telling us the person picked a different language.
     *
     * The layout changes at once, because that is what they are looking at; the word list follows a moment later
     * on its own thread.
     */
    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        val language = Language.of(newSubtype?.languageTag)
        if (language == actions.language) return
        actions.language = language
        keyboard?.language = language
        actions.refresh()
        background.post { loadDictionary(language) }
    }

    override fun onDestroy() {
        super.onDestroy()
        thinking.quitSafely()
    }

    override fun suggest(word: String) {
        background.removeCallbacksAndMessages(suggesting)
        val mine = ++asked
        if (word.length < 2) {
            actions.offered(Verdict(word, null, misspelled = false))
            keyboard?.suggestions = emptyList()
            return
        }
        val keys = keyboard?.placements
        background.postDelayed(
            {
                val started = android.os.SystemClock.elapsedRealtime()
                val words = dictionary ?: return@postDelayed
                // Rebuilt whenever the keys have moved - unfolding splits the keyboard, and correcting against
                // where the keys used to be is worse than not correcting by proximity at all.
                if (keys != null && keys !== proximityFor) {
                    proximity = Suggestions.Proximity(keys)
                    proximityFor = keys
                }
                val found = runCatching {
                    Suggestions.forWord(word, words, proximity, learned, shortcuts)
                }.getOrDefault(emptyList())
                val fix = runCatching {
                    Suggestions.correction(word, words, proximity, learned)
                }.getOrNull()
                // "Never heard of it" is a different question from "here is what you probably meant", and a word
                // can be the first without the second - a name, a word in another language, something made up.
                val unknown = runCatching {
                    word.length >= Learned.SHORTEST &&
                        !words.contains(word.lowercase()) &&
                        (learned?.count(word.lowercase()) ?: 0) == 0 &&
                        shortcuts?.expand(word) == null
                }.getOrDefault(false)
                // Timings and counts only: the word itself never goes in the log.
                val took = android.os.SystemClock.elapsedRealtime() - started
                DevLog.event(this, "suggest", "ms" to took, "found" to found.size, "fixed" to if (fix != null) 1 else 0)
                if (took > DevLog.SLOW_MS) DevLog.problem(this, "slow-suggestion", "ms" to took)
                main.post {
                    // A job already running cannot be cancelled, so it checks on the way out whether the word it
                    // was asked about is still the word being typed. Without this a slow answer for "te" lands
                    // after a fast one for "teh" and the strip shows the wrong thing.
                    if (mine != asked) return@post
                    actions.offered(Verdict(word, fix, misspelled = unknown, suggestions = found))
                    keyboard?.suggestions = if (found.isEmpty()) emptyList() else listOf(word) + found
                }
            },
            suggesting,
            THINK_MS,
        )
    }

    /**
     * Decided on the suggestion thread, because it asks the dictionary two questions.
     *
     * Saved every time rather than on a timer: an input method is killed without warning, and a word learned and
     * then lost teaches nothing. It is a few hundred bytes.
     */
    override fun learn(word: String) {
        background.post {
            val words = dictionary ?: return@post
            val store = learned ?: Learned().also { learned = it }
            val lower = word.lowercase()
            val known = words.contains(lower)
            if (!Learned.worthLearning(lower, known, nearMiss = false)) return@post
            // The expensive question last, and only for words that got this far.
            if (store.count(lower) == 0 && words.nearCommonWord(lower, proximity)) return@post
            store.learn(lower)
            prefs.edit().putString(LEARNED, store.encode()).apply()
        }
    }

    /** Everything it has picked up about how someone writes, gone. */
    fun forgetLearned() {
        background.post {
            learned?.clear()
            prefs.edit().remove(LEARNED).apply()
        }
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
        val clips = ClipboardPanel(this).also {
            it.visibility = View.GONE
            it.listener = object : ClipboardPanel.Listener {
                override fun onClip(text: String) {
                    actions.onText(text)
                    showClipboard(false)
                }

                override fun onPinClip(text: String, pinned: Boolean) = editClips {
                    Clipboard.pinning(it, text, pinned)
                }

                override fun onForgetClip(text: String) = editClips { Clipboard.forgetting(it, text) }

                override fun onClearClips() = editClips { Clipboard.cleared(it) }

                override fun onBackspace() = actions.onBackspace()

                override fun onLetters() = showClipboard(false)
            }
        }
        keyboard = keys
        emoji = grid
        clipboard = clips
        actions.refresh()
        return FrameLayout(this).also { root = it }.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(keys)
            addView(grid)
            addView(clips)
        }
    }

    private fun remember(value: String) {
        val grid = emoji ?: return
        val updated = Emoji.remember(grid.recents, value)
        grid.recents = updated
        prefs.edit().putString(RECENTS, Emoji.encode(updated)).apply()
    }

    override fun showEmoji(showing: Boolean) {
        if (showing) showClipboard(false)
        keyboard?.visibility = if (showing) View.GONE else View.VISIBLE
        emoji?.visibility = if (showing) View.VISIBLE else View.GONE
        if (showing) emoji?.opened()
    }

    /**
     * Swaps the letters for the clipboard, or back.
     *
     * The list is read when it opens rather than kept in step as clips arrive: what is on screen should be what is
     * stored at the moment someone looks, and the hour an unpinned clip lives means a list read earlier can be
     * showing something that has since expired.
     */
    /**
     * After the toolbar's Copy: the app copies, then the clipboard changes, so look shortly afterwards - twice, because
     * a slow app can take longer than the first look, and a clip already at the top is skipped rather than saved again.
     * Only while a field is still connected; nothing here keeps running once the keyboard has gone.
     */
    override fun copied() {
        for (wait in COPY_LOOKS) {
            main.postDelayed({ if (currentInputConnection != null) rememberClip(actions.settings) }, wait)
        }
    }

    override fun showClipboard(showing: Boolean) {
        if (showing) {
            showEmoji(false)
            clipboard?.clips = Clipboard.load(prefs, System.currentTimeMillis())
            clipboard?.appearance = actions.settings.appearance
            clipboard?.highContrast = actions.settings.highContrast
        }
        keyboard?.visibility = if (showing) View.GONE else View.VISIBLE
        clipboard?.visibility = if (showing) View.VISIBLE else View.GONE
    }

    /** One place that changes the stored list and puts the panel back in step with it. */
    private fun editClips(change: (List<Clipboard.Clip>) -> List<Clipboard.Clip>) {
        val now = System.currentTimeMillis()
        val updated = change(Clipboard.load(prefs, now))
        Clipboard.save(prefs, updated)
        clipboard?.clips = updated
        // If what Android holds is no longer in the list, it was forgotten or cleared just now: note it, or the next
        // field to open would read it and put it straight back.
        Clipboard.readable(Clipboard.manager(this), actions.rules)
            ?.takeIf { current -> updated.none { it.text == current } }
            ?.let { Clipboard.letGo(prefs, it) }
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
        // The editor has told us the line being typed on is behind the keys, and no amount of resizing is going to
        // fix that, so the screen is taken and the text shown in a field of its own.
        if (cursorHidden) return true
        val density = resources.displayMetrics.density
        val shown = listOfNotNull(keyboard, emoji).firstOrNull { it.visibility == View.VISIBLE }
        val keyboardDp = (shown?.height ?: 0) / density
        return roomAbove(resources.configuration.screenHeightDp.toFloat(), keyboardDp) < ROOM_FOR_THE_APP_DP
    }

    /**
     * Deliberately not overridden.
     *
     * There was a version of this that claimed a touchable region, so that the few pixels of margin around the
     * floating panel passed taps through to the app behind. The numbers it used were a child view's, and
     * `onComputeInsets` wants them in the window - which is the same thing right up until the input view stops
     * filling the window. In fullscreen mode the extract field sits above it, everything shifts, and the region
     * lands somewhere the keys are not: the keyboard draws perfectly and answers nothing at all.
     *
     * A six-pixel margin is not worth a keyboard that sometimes does not work, so Android works the insets out.
     */

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        DevLog.event(this, "field", "class" to ((info?.inputType ?: 0) and android.text.InputType.TYPE_MASK_CLASS),
            "restarting" to if (restarting) 1 else 0)
        // Re-read each time a field opens, so a change on the settings screen takes effect without a restart.
        val chosen = Settings.load(prefs)
        actions.settings = chosen
        // Asked every time rather than only when it changes: a subtype can be switched while another app is in
        // front, and the first we hear of it is the next field that opens.
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val language = Language.of(
            runCatching { manager.currentInputMethodSubtype?.languageTag }.getOrNull(),
        )
        if (language != actions.language) {
            actions.language = language
            background.post { loadDictionary(language) }
        }
        keyboard?.language = language
        keyboard?.settings = chosen
        emoji?.appearance = chosen.appearance
        emoji?.highContrast = chosen.highContrast
        actions.startInput(info)
        keyboard?.rules = actions.rules   // one reading of the field, not two
        // A keyboard may read the clipboard while it is the one on screen, so this is the moment to look. The field
        // has just been read, which is what decides whether anything may be kept from it at all.
        rememberClip(chosen)
        // A new field starts on the letters: nobody opens a password box wanting the emoji, or the list of things
        // they copied, that they left open.
        showEmoji(false)
        showClipboard(false)
        // And with nothing held over from the last one. A touch that never got its release - the window taken
        // away mid-press, a call arriving - would otherwise leave a finger down forever.
        keyboard?.forgetTouches()
        // Re-read in case the setup screen has been used to forget everything since the last field.
        background.post {
            learned = Learned.decode(prefs.getString(LEARNED, null))
            shortcuts = Shortcuts.decode(prefs.getString(SHORTCUTS, null))
        }
        // Ask the editor to keep telling us where the cursor is. Most will not, which is why nothing depends on it.
        currentInputConnection?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR)
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

    /** A new field is a fresh question: whatever was true of the last one says nothing about this one. */
    override fun onFinishInput() {
        super.onFinishInput()
        if (cursorHidden) {
            cursorHidden = false
            updateFullscreenMode()
        }
    }

    /**
     * The editor saying where the cursor ended up.
     *
     * Almost the only thing worth doing with this: noticing that it is underneath us.
     */
    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        super.onUpdateCursorAnchorInfo(info)
        if (cursorHidden || isFullscreenMode) return
        val view = root ?: return
        if (view.width == 0) return
        val marker = floatArrayOf(info.insertionMarkerHorizontal, info.insertionMarkerBottom)
        info.matrix.mapPoints(marker)
        val where = view.locationOnScreen().let { top ->
            CursorWatch.read(marker[1], top, info.insertionMarkerFlags)
        }
        if (where == CursorWatch.Where.HIDDEN) {
            cursorHidden = true
            updateFullscreenMode()
        }
    }

    private fun View.locationOnScreen(): Int {
        val at = IntArray(2)
        getLocationOnScreen(at)
        return at[1]
    }

    override fun hideKeyboard() = requestHideSelf(0)

    private companion object {
        /**
         * Less of the app than this left showing, and an extracted field is more use than a sliver.
         *
         * Below about this much there is no height to divide: the keyboard cannot be made small enough to leave
         * anything worth looking at, so taking the screen and showing the text in a field of its own is the only
         * way someone still sees what they are typing.
         */
        const val ROOM_FOR_THE_APP_DP = 160f
        const val RECENTS = "emojiRecents"
        const val LEARNED = "learnedWords"
        const val SHORTCUTS = "shortcuts"

        /** When to look at the clipboard after Copy, in milliseconds. */
        val COPY_LOOKS = longArrayOf(150, 600)

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
