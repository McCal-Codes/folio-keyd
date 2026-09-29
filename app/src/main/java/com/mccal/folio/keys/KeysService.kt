package com.mccal.folio.keys

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
    private var emojiSearch: EmojiSearchPanel? = null
    private var clipboard: ClipboardPanel? = null
    private var pad: CursorPad? = null
    private var root: View? = null

    /**
     * Whether the editor has told us the cursor is behind the keys, confirmed over [CursorWatch.SETTLE_MS].
     *
     * It latches while the keyboard stays up. Going fullscreen moves everything, which changes where the cursor is,
     * which would change the answer again - a keyboard flickering in and out of fullscreen while someone types is far
     * worse than either state. But it is forgotten whenever the keyboard goes away or a field opens: a latch that
     * outlived the keyboard kept WhatsApp fullscreen on every tap until the app was closed.
     */
    private val cursor = CursorWatch.Decision()

    /** Between onWindowShown and onWindowHidden. Cursor reports outside that are the keyboard coming or going. */
    private var windowShown = false

    /** The app the last field was in, so the strip's question does not follow someone into the next one. */
    private var offeredIn: String? = null

    /** Whether this field has had its one chance at a gesture tip, taken or not. */
    private var tipAsked = false

    /** The tips that will not be shown again, read when a field opens, so a swipe never has to read preferences. */
    private var tipsDone: MutableSet<Tip> = HashSet()

    /** Counts a selection once it has stopped moving. See [onUpdateSelection]. */
    private val countSelection = Runnable { if (isInputViewShown) actions.countSelection() }

    private val settle = Runnable {
        if (cursor.confirm(windowShown)) {
            DevLog.event(this, "fullscreen", "taken" to 1)
            updateFullscreenMode()
        }
    }

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

    /** The suggestion thread's looper, so a test can wait for it. */
    internal val suggestionLooper: Looper get() = thinking.looper
    private var dictionary: Dictionary? = null
    private var nextWords: NextWords? = null
    private var loadedFor: Language? = null
    private var learned: Learned? = null
    private var shortcuts: Shortcuts? = null
    private var insights: Insights? = null

    /**
     * Counted but not yet saved. A fix is counted on every corrected word, and writing the whole preferences file for
     * each one was most of what the suggestion thread did while someone typed; the counts are saved a moment later
     * instead, and whenever the field or the keyboard goes.
     */
    private var insightsDirty = false
    private val saveInsights = Runnable { flushInsights() }

    /**
     * What this service last wrote, or read, for each of the stores. Only touched on the suggestion thread.
     *
     * Settings writes the same stores - answering from What it fixes, forgetting, an import - while the keyboard can
     * be up. A keyboard that then saved its own copy would put back what Settings just changed, so before anything is
     * changed here, [sync] reads again whatever no longer matches.
     */
    private val written = HashMap<String, String?>()
    private var proximity: Suggestions.Proximity? = null

    /** Which language's emoji names the search has. Only touched on the suggestion thread, like [loadedFor]. */
    private var emojiNamesFor: Language? = null
    private var emojiNames: EmojiSearch? = null
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
        Settings.settleToolbar(prefs, Settings.updated(this), Settings.firstInstalled(this))
        spanTheCutout()
        // Read once, off the main thread: the keyboard has to be on screen before the dictionary is needed.
        background.post {
            loadDictionary(actions.language)
            sync()
        }
    }

    /**
     * Reads again any store that someone else has written since this service last did: Settings, the setup screen,
     * an import. Cheap when nothing changed, which is nearly always: a comparison per store and nothing else.
     *
     * Counts not yet saved are dropped if Settings wrote the counts in between. Settings is where someone forgets
     * them or answers for them, and what they did there wins over a second or two of counting.
     */
    private fun sync() {
        val words = prefs.getString(LEARNED, null)
        val seen = prefs.getString(SEEN, null)
        if (learned == null || words != written[LEARNED] || seen != written[SEEN]) {
            learned = Learned.decode(words, seen)
            written[LEARNED] = words
            written[SEEN] = seen
        }
        val rules = prefs.getString(SHORTCUTS, null)
        if (shortcuts == null || rules != written[SHORTCUTS]) {
            shortcuts = Shortcuts.decode(rules)
            written[SHORTCUTS] = rules
        }
        val counts = prefs.getString(INSIGHTS, null)
        if (insights == null || counts != written[INSIGHTS]) {
            insights = Insights.decode(counts)
            insightsDirty = false
            written[INSIGHTS] = counts
        }
    }

    /** Saves on the suggestion thread, noting what was saved so [sync] can tell it from a write made elsewhere. */
    private fun save(vararg stores: Pair<String, String>) {
        val edit = prefs.edit()
        for ((key, value) in stores) {
            edit.putString(key, value)
            written[key] = value
        }
        edit.apply()
    }

    /** Counted: saved within [SAVE_COUNTS_MS], or sooner if the field closes first. */
    private fun countsChanged() {
        if (insightsDirty) return
        insightsDirty = true
        background.postDelayed(saveInsights, SAVE_COUNTS_MS)
    }

    /** Only on the suggestion thread. */
    private fun flushInsights() {
        background.removeCallbacks(saveInsights)
        if (!insightsDirty) return
        // Written elsewhere since: that wins, and there is nothing of ours left to save.
        val store = insights
        if (store == null || prefs.getString(INSIGHTS, null) != written[INSIGHTS]) {
            sync()
            return
        }
        insightsDirty = false
        save(INSIGHTS to store.encode())
    }

    /** The field or the keyboard went: whatever has been counted is saved now rather than in a moment. */
    private fun saveCountsNow() {
        background.removeCallbacks(saveInsights)
        background.post(saveInsights)
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
        // Small beside the dictionary, and without it the strip is only empty after a space, so a failure is logged
        // and nothing more.
        nextWords = runCatching { NextWords.load(this, language) }
            .onFailure { DevLog.error(this, "NextWords.load", it) }.getOrNull()
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
    /**
     * Reads the emoji names for a language, the first time the search is opened in it.
     *
     * Not with the dictionary when the keyboard starts: most people never search for an emoji, and the ones who do
     * will not notice a file this small being read in the moment it takes the search row to appear.
     */
    private fun loadEmojiNames(language: Language) {
        // Kept, and handed to whichever panel is showing now: rotating, unfolding or a change of dark mode builds the
        // views again, and a new panel that was never given the names found nothing at all.
        emojiNames?.takeIf { emojiNamesFor == language }?.let { names ->
            main.post { emojiSearch?.search = names }
            return
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val names = runCatching { EmojiSearch.load(this, language) }
            .onFailure { DevLog.error(this, "EmojiSearch.load", it) }.getOrNull() ?: return
        DevLog.event(this, "emojiNames", "ms" to android.os.SystemClock.elapsedRealtime() - started,
            "emoji" to names.size)
        emojiNames = names
        emojiNamesFor = language
        main.post { emojiSearch?.search = names }
    }

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
        main.removeCallbacks(settle)
        main.removeCallbacks(countSelection)
        saveCountsNow()
        super.onDestroy()
        thinking.quitSafely()
    }

    override fun suggest(word: String) = suggest(word, "")

    override fun suggest(word: String, previous: String) {
        background.removeCallbacksAndMessages(suggesting)
        val mine = ++asked
        // Nothing typed yet, straight after a word: what might come next. Not while the strip has a question
        // waiting, which only ever gets asked in a gap like this one.
        if (word.isEmpty() && previous.isNotEmpty() && keyboard?.offer == null) {
            actions.offered(Verdict(word, null, misspelled = false))
            // A tip, when it is time for one, has this gap instead of what might come next.
            if (keyboard?.tip != null || placeTip()) {
                keyboard?.suggestions = emptyList()
                return
            }
            val shift = actions.shift
            background.postDelayed(
                {
                    val found = runCatching { Suggestions.predict(previous, nextWords, shift) }.getOrDefault(emptyList())
                    main.post {
                        if (mine != asked) return@post
                        keyboard?.typedFirst = false
                        keyboard?.suggestedEmoji = null
                        keyboard?.suggestions = found
                    }
                },
                suggesting,
                THINK_MS,
            )
            return
        }
        if (word.length < 2) {
            actions.offered(Verdict(word, null, misspelled = false))
            keyboard?.suggestedEmoji = null
            keyboard?.suggestions = emptyList()
            return
        }
        val keys = keyboard?.placements
        val language = actions.language
        val contractions = Contractions.of(language)
        val emojiOn = actions.settings.suggestEmoji
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
                    Suggestions.forWord(word, words, proximity, learned, shortcuts, contractions, previous, nextWords)
                }.getOrDefault(emptyList())
                val fix = runCatching {
                    Suggestions.correction(
                        word, words, proximity, learned, contractions, previous,
                        compounds = language == Language.GERMAN, next = nextWords,
                    )
                }.getOrNull()
                // "Never heard of it" is a different question from "here is what you probably meant", and a word
                // can be the first without the second - a name, a word in another language, something made up.
                val unknown = runCatching {
                    word.length >= Learned.SHORTEST &&
                        !Suggestions.known(word.lowercase(), words) &&
                        (learned?.count(word.lowercase()) ?: 0) == 0 &&
                        shortcuts?.expand(word) == null
                }.getOrDefault(false)
                // The emoji names are read the first time a word could use them, after this word's answer rather than
                // ahead of it, so the first suggestions never wait on a second file. Until then, no emoji.
                val names = emojiNames?.takeIf { emojiNamesFor == language }
                if (emojiOn && names == null) background.post { loadEmojiNames(language) }
                val emoji = if (!emojiOn) null else runCatching {
                    Suggestions.emoji(word, words, names)?.let { SuggestedEmoji(it, names?.nameOf(it) ?: it) }
                }.getOrNull()
                // Timings and counts only: the word itself never goes in the log.
                val took = android.os.SystemClock.elapsedRealtime() - started
                DevLog.event(this, "suggest", "ms" to took, "found" to found.size, "fixed" to if (fix != null) 1 else 0,
                    "emoji" to if (emoji != null) 1 else 0)
                if (took > DevLog.SLOW_MS) DevLog.problem(this, "slow-suggestion", "ms" to took)
                main.post {
                    // A job already running cannot be cancelled, so it checks on the way out whether the word it
                    // was asked about is still the word being typed. Without this a slow answer for "te" lands
                    // after a fast one for "teh" and the strip shows the wrong thing.
                    if (mine != asked) return@post
                    actions.offered(Verdict(word, fix, misspelled = unknown, suggestions = found))
                    keyboard?.typedFirst = true
                    keyboard?.suggestedEmoji = emoji
                    keyboard?.suggestions = if (found.isEmpty() && emoji == null) emptyList() else listOf(word) + found
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
    override fun learn(word: String) = learn(word, corrected = false)

    override fun learn(word: String, corrected: Boolean) {
        // A typo that was corrected, and the correction stood, is the one thing that must not be learned: learned,
        // it would never be corrected again. If it is put back instead, [putBack] decides.
        if (corrected) return
        // With autocorrect off, every typo is left as typed, so being left alone says nothing about a word.
        val watching = Settings.correcting(actions.settings)
        val today = System.currentTimeMillis() / DAY_MS
        background.post {
            val words = dictionary ?: return@post
            sync()
            val store = learned ?: Learned().also { learned = it }
            val lower = word.lowercase()
            // Known the way the strip knows it: "l'homme" is two known words, not a new one.
            val known = Suggestions.known(lower, words)
            // Once per install, the first time the key positions are known: clear out slips learned before the
            // neighbouring-key rule existed.
            val keys = proximity
            if (keys != null && !prefs.getBoolean(PRUNED_SLIPS, false)) {
                val gone = store.prune { words.nearCommonWord(it, keys) }
                prefs.edit().putBoolean(PRUNED_SLIPS, true).apply()
                save(LEARNED to store.encode())
                DevLog.event(this, "pruned", "words" to gone)
            }
            if (!Learned.worthLearning(lower, known, nearMiss = false)) return@post
            // The expensive question last, and only for words that got this far. A near miss is not learned, but it
            // is noticed: left alone often enough, it was meant.
            if (store.count(lower) == 0 && words.nearCommonWord(lower, proximity)) {
                if (watching && store.sighted(lower, today)) save(store)
                return@post
            }
            store.learn(lower)
            save(store)
        }
    }

    /**
     * A correction stood, or was put back. Counted on the suggestion thread and saved from there a moment later; if
     * that was the third time, the strip is handed the question on the way back.
     */
    override fun fixStood(typed: String, replacement: String) {
        val offering = actions.settings.offerRules
        background.post {
            sync()
            val store = insights ?: Insights().also { insights = it }
            val offer = store.fixStood(typed, replacement, offering)
            countsChanged()
            offer?.let { main.post { place(it) } }
        }
    }

    override fun putBack(typed: String) {
        val offering = actions.settings.offerRules
        background.post {
            sync()
            val store = insights ?: Insights().also { insights = it }
            val offer = store.undone(typed, offering)
            countsChanged()
            // Put back twice, it is a word, whatever it is one edit away from. Kept now rather than asked about on
            // the third time, because there should not be a third time.
            if (store.putBacks(typed) >= Learned.PUT_BACKS) {
                val words = learned ?: Learned().also { learned = it }
                if (words.count(typed.lowercase()) == 0) {
                    words.keep(typed.lowercase())
                    save(words)
                }
                return@post
            }
            offer?.let { main.post { place(it) } }
        }
    }

    /**
     * A gesture tip in this gap between words, if one is due: after [Tips.AFTER_CHARS] typed in this field, and at most
     * once per field; [Tips.due] says the rest. True when one went on screen.
     *
     * Every keystroke of a word passes through here, so the cheap tests come first and the preferences are only
     * read once per field, the first time the count is reached.
     */
    private fun placeTip(): Boolean {
        if (tipAsked || actions.typedHere < Tips.AFTER_CHARS) return false
        tipAsked = true
        val keys = keyboard ?: return false
        val today = System.currentTimeMillis() / DAY_MS
        val tips = Tips.load(prefs)
        val tip = tips.due(actions.settings, actions.rules, today) ?: return false
        // Counted as it goes on screen: put away by typing, it has still been shown once.
        val now = tips.shownOn(tip, today)
        now.save(prefs)
        if (now.done(tip)) tipsDone += tip
        keys.tip = tip
        return true
    }

    override fun tipDone(tip: Tip) = gestureUsed(tip)

    override fun gestureUsed(tip: Tip) {
        if (keyboard?.tip == tip) keyboard?.tip = null
        if (!tipsDone.add(tip)) return
        Tips.load(prefs).finish(tip).save(prefs)
    }

    /**
     * The strip's question, unless the field it was earned in has gone and a password field or one that asked not to
     * be learned from has taken its place. Asking "Keep 'Folio'?" over a password is saying what was typed before it.
     */
    private fun place(offer: Insights.Offer) {
        if (actions.rules.ephemeral) return
        keyboard?.offer = offer
    }

    /**
     * Keep puts the word in with the learned ones, which is what stops it being corrected; Always makes a text
     * shortcut. Either answer, or No, is remembered so the same question is never asked again.
     */
    override fun answered(offer: Insights.Offer, accepted: Boolean) {
        DevLog.event(this, "offer", "keep" to if (offer.keep) 1 else 0, "accepted" to if (accepted) 1 else 0)
        background.post {
            sync()
            val store = insights ?: Insights().also { insights = it }
            val words = learned ?: Learned().also { learned = it }
            val rules = shortcuts ?: Shortcuts().also { shortcuts = it }
            store.answer(offer, accepted, words, rules)
            background.removeCallbacks(saveInsights)
            insightsDirty = false
            save(INSIGHTS to store.encode(), LEARNED to words.encode(), SHORTCUTS to rules.encode())
        }
    }

    /** Learned words and their sightings, saved together so the two never disagree. */
    private fun save(store: Learned) = save(LEARNED to store.encode(), SEEN to store.encodeSeen())

    /** Everything it has picked up about how someone writes, gone: the learned words and the fixes counted. */
    fun forgetLearned() {
        background.post {
            learned?.clear()
            insights = Insights()
            insightsDirty = false
            background.removeCallbacks(saveInsights)
            written[LEARNED] = null
            written[INSIGHTS] = null
            written[SEEN] = null
            prefs.edit().remove(LEARNED).remove(INSIGHTS).remove(SEEN).apply()
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

                override fun onSearch() = showEmojiSearch(true)
            }
        }
        val finder = EmojiSearchPanel(this).also {
            it.visibility = View.GONE
            it.listener = object : EmojiSearchPanel.Listener {
                // The same as a tap on the grid, recents and all. The search stays open for the next one.
                override fun onEmoji(emoji: String) {
                    actions.onEmoji(emoji)
                    remember(emoji)
                }

                override fun onBack() = showEmoji(true)

                override fun onHide() = requestHideSelf(0)
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

                /** Held a pinned clip: open a new text shortcut with it filled in. Nothing is saved until Add. */
                override fun onShortcutFromClip(text: String) {
                    runCatching {
                        startActivity(
                            android.content.Intent(this@KeysService, ShortcutsActivity::class.java)
                                .putExtra(ShortcutsActivity.EXTRA_EXPANSION, text)
                                // A task of its own, kept out of Recents: otherwise it opens on top of Keyd's
                                // settings, and Back after making the shortcut lands there instead of in the app
                                // that was being typed in.
                                .addFlags(
                                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                        android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                                        android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                                        android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                                ),
                        )
                    }
                    requestHideSelf(0)
                }

                override fun onBackspace() = actions.onBackspace()

                override fun onLetters() = showClipboard(false)
            }
        }
        val arrows = CursorPad(this).also {
            it.visibility = View.GONE
            it.listener = object : CursorPad.Listener {
                override fun onMove(move: CursorPad.Move, selecting: Boolean) {
                    val (code, meta) = CursorPad.keyEvents(move, selecting)
                    actions.onMove(code, meta)
                }

                override fun onSelectAll() = actions.onSelectAll()

                override fun onCut() = actions.onCut()

                override fun onBackspace() = actions.onBackspace()

                override fun onLetters() = showCursorPad(false)
            }
        }
        // One Feedback for both sets of letters, so one callback is registered for Bluetooth rather than two.
        finder.keys.feedback = keys.feedback
        if (windowShown) keys.feedback.start()
        keyboard = keys
        emoji = grid
        emojiSearch = finder
        clipboard = clips
        pad = arrows
        actions.refresh()
        return KeyboardFrame(this, keys).also { root = it }.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(keys)
            addView(grid)
            addView(finder)
            addView(clips)
            addView(arrows)
        }
    }

    override fun rememberEmoji(emoji: String) = remember(emoji)

    private fun remember(value: String) {
        val grid = emoji ?: return
        val updated = Emoji.remember(grid.recents, value)
        grid.recents = updated
        emojiSearch?.recents = updated
        prefs.edit().putString(RECENTS, Emoji.encode(updated)).apply()
    }

    override fun showEmoji(showing: Boolean) {
        if (showing) {
            showClipboard(false)
            showCursorPad(false)
        }
        keyboard?.visibility = if (showing) View.GONE else View.VISIBLE
        emoji?.visibility = if (showing) View.VISIBLE else View.GONE
        // Back to the grid or to the letters, the search closes either way.
        showEmojiSearch(false)
        if (showing) emoji?.opened()
    }

    /**
     * Swaps the emoji grid for the search, or closes it.
     *
     * The names are asked for here, on the suggestion thread, and arrive a moment later; until they do the results
     * row is simply empty, which is what it shows before anything is typed anyway.
     */
    private fun showEmojiSearch(showing: Boolean) {
        val finder = emojiSearch ?: return
        if (showing) {
            keyboard?.visibility = View.GONE
            emoji?.visibility = View.GONE
            clipboard?.visibility = View.GONE
            pad?.visibility = View.GONE
            finder.recents = emoji?.recents.orEmpty()
            finder.opened(actions.settings, actions.language)
            val language = actions.language
            background.post { loadEmojiNames(language) }
        } else {
            // What was searched for goes with the search, rather than waiting in memory for the next time it opens.
            finder.closed()
        }
        finder.visibility = if (showing) View.VISIBLE else View.GONE
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
            showCursorPad(false)
            clipboard?.clips = Clipboard.load(prefs, System.currentTimeMillis())
            clipboard?.appearance = actions.settings.appearance
            clipboard?.highContrast = actions.settings.highContrast
            clipboard?.keyStyle = actions.settings.keyStyle
            clipboard?.feel(actions.settings)
        }
        keyboard?.visibility = if (showing) View.GONE else View.VISIBLE
        clipboard?.visibility = if (showing) View.VISIBLE else View.GONE
    }

    /** Swaps the letters for the arrows and selection keys, or back. It opens with selecting off every time. */
    override fun showCursorPad(showing: Boolean) {
        val shown = pad ?: return
        if (showing) {
            emoji?.visibility = View.GONE
            emojiSearch?.visibility = View.GONE
            clipboard?.visibility = View.GONE
            shown.appearance = actions.settings.appearance
            shown.highContrast = actions.settings.highContrast
            shown.keyStyle = actions.settings.keyStyle
            shown.feel(actions.settings)
            shown.opened()
        }
        keyboard?.visibility = if (showing) View.GONE else View.VISIBLE
        shown.visibility = if (showing) View.VISIBLE else View.GONE
    }

    /**
     * From the rail beside a one-handed keyboard. Saved as the usual setting rather than this app's, since the hand
     * that wants the keys on the other side wants them there in every app, and the panels move with the letters.
     */
    override fun oneHanded(side: OneHanded) {
        Settings.load(prefs).copy(oneHanded = side).save(prefs)
        actions.settings = actions.settings.copy(oneHanded = side)
        keyboard?.settings = actions.settings
        root?.requestLayout()
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
        if (cursor.taken) return true
        val density = resources.displayMetrics.density
        val shown = listOfNotNull(keyboard, emoji, emojiSearch, clipboard, pad).firstOrNull { it.visibility == View.VISIBLE }
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
        // The same field tapped again after the keyboard was hidden arrives here without onFinishInput in between.
        forgetCursor()
        DevLog.event(this, "field", "class" to ((info?.inputType ?: 0) and android.text.InputType.TYPE_MASK_CLASS),
            "restarting" to if (restarting) 1 else 0)
        // Re-read each time a field opens, so a change on the settings screen takes effect without a restart. The app
        // the field belongs to may have its own answers, and they are laid over the usual ones here, before anything
        // reads them. A password field's own rules still come after all of this, in TextActions: the field decides.
        val chosen = settingsFor(info?.packageName, noted = !Layouts.rulesFor(info).ephemeral)
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
        keyboard?.voiceAvailable = voiceKeyboard() != null
        emoji?.appearance = chosen.appearance
        emoji?.highContrast = chosen.highContrast
        emoji?.keyStyle = chosen.keyStyle
        emoji?.feel(chosen)
        clipboard?.feel(chosen)
        pad?.feel(chosen)
        main.removeCallbacks(countSelection)
        actions.startInput(info)
        keyboard?.rules = actions.rules   // one reading of the field, not two
        // A keyboard may read the clipboard while it is the one on screen, so this is the moment to look. The field
        // has just been read, which is what decides whether anything may be kept from it at all.
        rememberClip(chosen)
        // A new field starts on the letters: nobody opens a password box wanting the emoji, or the list of things
        // they copied, that they left open. The emoji search closes too, and forgets what was typed into it.
        showEmoji(false)
        showClipboard(false)
        showCursorPad(false)
        // And with nothing held over from the last one. A touch that never got its release - the window taken
        // away mid-press, a call arriving - would otherwise leave a finger down forever.
        keyboard?.forgetTouches()
        // Re-read in case the setup screen has been used to forget everything since the last field.
        background.post { sync() }
        // The strip's question waits for a gap, but not across a change of mind on the settings screen, into another
        // app, or into a field that asked not to be learned from.
        val app = info?.packageName
        if (!chosen.offerRules || actions.rules.ephemeral || app != offeredIn) keyboard?.offer = null
        offeredIn = app
        // A tip is for the field it was shown in. Read again here, in case Settings has asked for them all again.
        keyboard?.tip = null
        tipAsked = false
        tipsDone = Tips.load(prefs).finished().toMutableSet()
        // Ask the editor to keep telling us where the cursor is. Most will not, which is why nothing depends on it.
        currentInputConnection?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR)
    }

    /**
     * The usual settings with [packageName]'s own laid over them, noting the app as one typed in lately.
     *
     * The package name is all that is kept - it says which app, not which field or what was in it - and only Keyd's
     * own list of the last few apps holds it. Nothing is written when it is the app the last field was in, and
     * nothing when [noted] is false: a password field, or one that asked not to be learned from, is not "typed in"
     * for anyone's list, though an app already on it still gets its own settings there.
     */
    internal fun settingsFor(packageName: String?, noted: Boolean = true): Settings {
        val usual = Settings.load(prefs)
        val apps = AppProfiles.load(prefs)
        if (noted && apps.typedIn(packageName, this.packageName)) apps.save(prefs)
        return apps.apply(usual, packageName)
    }

    private fun EmojiPanel.feel(settings: Settings) {
        vibration = settings.vibration
        pureBlack = settings.pureBlack
    }

    private fun ClipboardPanel.feel(settings: Settings) {
        vibration = settings.vibration
        holdDelay = settings.holdDelay
        pureBlack = settings.pureBlack
    }

    private fun CursorPad.feel(settings: Settings) {
        vibration = settings.vibration
        pureBlack = settings.pureBlack
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

    /**
     * The editor saying the selection moved. Passed on to be counted when something is selected; what is selected is
     * read there, once per change, and never kept or logged.
     */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!actions.selectionMoved(newSelStart, newSelEnd)) return
        main.removeCallbacks(countSelection)
        // A cursor is never read, so the count goes at once. A selection is read, which is a blocking call into the
        // app, and sliding from shift moves it once per key: it is counted when it stops, not at every step.
        if (newSelStart == newSelEnd) actions.countSelection()
        else if (isInputViewShown) main.postDelayed(countSelection, SELECTION_SETTLE_MS)
    }

    override fun selected(selection: Selected?) {
        keyboard?.selection = selection
    }

    /** A new field is a fresh question: whatever was true of the last one says nothing about this one. */
    override fun onFinishInput() {
        super.onFinishInput()
        forgetCursor()
        main.removeCallbacks(countSelection)
        saveCountsNow()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        windowShown = true
        // Only while the keys are on screen: hiding keeps the view attached, so this cannot wait for a detach.
        keyboard?.feedback?.start()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        windowShown = false
        forgetCursor()
        main.removeCallbacks(countSelection)
        saveCountsNow()
        keyboard?.feedback?.stop()
        // Hiding keeps the view attached, so onDetachedFromWindow never runs: a backspace held by one finger while
        // another hides the keyboard would otherwise keep deleting out of sight, and a long-press popup would wait.
        keyboard?.forgetTouches()
        emojiSearch?.forgetTouches()
    }

    /** Every time the keyboard comes or goes, whether the screen should be taken is asked again from nothing. */
    private fun forgetCursor() {
        main.removeCallbacks(settle)
        if (cursor.reset()) {
            DevLog.event(this, "fullscreen", "taken" to 0)
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
        if (cursor.taken || isFullscreenMode) return
        val view = root ?: return
        if (view.width == 0 || !view.isLaidOut || !view.isShown) return
        val marker = floatArrayOf(info.insertionMarkerHorizontal, info.insertionMarkerBottom)
        info.matrix.mapPoints(marker)
        val where = view.locationOnScreen().let { top ->
            CursorWatch.read(marker[1], top, info.insertionMarkerFlags)
        }
        when (cursor.report(where, windowShown)) {
            CursorWatch.Next.CHECK_LATER -> main.postDelayed(settle, CursorWatch.SETTLE_MS)
            CursorWatch.Next.NOTHING -> if (!cursor.pending) main.removeCallbacks(settle)
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

        /** The subtype mode a voice input method declares. */
        const val VOICE_MODE = "voice"
        const val RECENTS = "emojiRecents"
        const val LEARNED = "learnedWords"
        const val SEEN = "seenWords"
        const val PRUNED_SLIPS = "prunedSlips1"
        const val SHORTCUTS = "shortcuts"
        const val INSIGHTS = "typingInsights"

        /** When to look at the clipboard after Copy, in milliseconds. */
        val COPY_LOOKS = longArrayOf(150, 600)

        const val DAY_MS = 24L * 60 * 60 * 1000

        /** Long enough that a fast typist skips most lookups, short enough not to feel behind. */
        const val THINK_MS = 40L

        /** How long a selection has to stay put before it is read and counted. */
        const val SELECTION_SETTLE_MS = 80L

        /** How long counted fixes wait to be saved, so a run of corrected words is one write rather than many. */
        const val SAVE_COUNTS_MS = 2000L

        /** The margin both panels leave around themselves. */
        const val PANEL_PAD_DP = 6f

        /** What is left of the window once the keyboard has taken its share. */
        fun roomAbove(screenHeightDp: Float, keyboardHeightDp: Float) = screenHeightDp - keyboardHeightDp
    }

    /**
     * The phone's voice keyboard, if it has one switched on: an input method with a subtype in "voice" mode, which is
     * how Google's voice typing and Samsung's both describe themselves. Asked each time a field opens rather than
     * remembered, because one can be turned off in Android's settings while Keyd is running.
     */
    private fun voiceKeyboard(): Pair<String, InputMethodSubtype>? {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return runCatching {
            manager.enabledInputMethodList.asSequence()
                .filter { it.packageName != packageName }
                .firstNotNullOfOrNull { method ->
                    manager.getEnabledInputMethodSubtypeList(method, true)
                        .firstOrNull { it.mode == VOICE_MODE }
                        ?.let { method.id to it }
                }
        }.getOrNull()
    }

    override fun startVoice() {
        val (id, subtype) = voiceKeyboard() ?: return
        DevLog.event(this, "voice", "handed" to 1)
        // Switching this way (rather than asking the person to pick from a list) is what lets the voice keyboard
        // hand back to Keyd with its own "back to keyboard" button when it's done.
        runCatching { switchInputMethod(id, subtype) }
    }

    /** Keyd's own entry in Android's list of turned-on keyboards, and the languages turned on for it there. */
    private fun ourSubtypes(): Pair<String, List<InputMethodSubtype>>? {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return runCatching {
            val method = manager.enabledInputMethodList.firstOrNull {
                it.packageName == packageName && it.serviceName == KeysService::class.java.name
            } ?: return null
            method.id to manager.getEnabledInputMethodSubtypeList(method, true)
        }.getOrNull()
    }

    private fun InputMethodSubtype.language() = Language.of(languageTag.ifEmpty { @Suppress("DEPRECATION") locale })

    /** Asked when the globe is held rather than kept: languages can be turned on and off while Keyd is running. */
    override fun languages(): List<Language> = ourSubtypes()?.second.orEmpty().map { it.language() }.distinct()

    override fun switchLanguage(language: Language) {
        if (language == actions.language) return
        val (id, subtypes) = ourSubtypes() ?: return
        val subtype = subtypes.firstOrNull { it.language() == language } ?: return
        DevLog.event(this, "language", "picked" to 1)
        // The same call the voice key uses; Android then tells onCurrentInputMethodSubtypeChanged, as its picker would.
        runCatching { switchInputMethod(id, subtype) }
    }

    override fun pickKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun openLanguageSettings() {
        runCatching { startActivity(languageSettings(this)) }
        requestHideSelf(0)
    }

    override fun switchKeyboard() {
        // The globe hands over to whatever the person picked next; Android decides what that is, not Folio.
        if (!switchToNextInputMethod(false)) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }
}
