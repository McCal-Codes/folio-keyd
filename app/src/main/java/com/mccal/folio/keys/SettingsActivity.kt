package com.mccal.folio.keys

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * The settings screen, grouped the way Gboard, Samsung Keyboard and iOS group theirs.
 *
 * It used to be one long page with a paragraph under every switch and buttons in between, which reads as a manual
 * rather than a settings screen. Now the first page holds the two things people actually change - suggestions and
 * autocorrect - plus where the rest lives, and each of those is a page of its own with one short footer per group.
 * The explanations are the same ones; they sit under the group they explain instead of under every row.
 *
 * Every setting on these pages is one Keyd has today. The designs in the Mockup Lab go further, and none of that is
 * shown here until it exists.
 */
class SettingsActivity : Activity() {

    private enum class Page(val title: Int, val parent: Page?) {
        MAIN(R.string.page_main, null),
        TYPING(R.string.page_typing, MAIN),
        WHAT_IT_FIXES(R.string.page_what_it_fixes, TYPING),
        APPS(R.string.row_per_app, TYPING),
        /** One app from that list. Its title is the app's name, so [title] is only the fallback. */
        APP(R.string.row_per_app, APPS),
        KEYS(R.string.page_keys, MAIN),
        TOOLBAR(R.string.row_toolbar, KEYS),
        PERIOD(R.string.page_period, KEYS),
        LANGUAGES(R.string.row_languages, MAIN),
        LOOK(R.string.page_look, MAIN),
        FEEL(R.string.page_feel, MAIN),
        CLIPBOARD(R.string.page_clipboard, MAIN),
        PRIVACY(R.string.page_privacy, MAIN),
        DEVELOPER(R.string.page_developer, MAIN),
        WHATS_NEW(R.string.row_whats_new, MAIN),
        /** Everything in the release What's New shows. Its title is the version, so [title] is only the fallback. */
        CHANGES(R.string.fixes_and_improvements, WHATS_NEW),
        HISTORY(R.string.row_earlier_versions, WHATS_NEW),
        SEARCH(R.string.page_search, MAIN),
        REPORT(R.string.page_report, MAIN),
        PREVIEW(R.string.page_preview, REPORT),
    }

    private lateinit var settings: Settings
    private val prefs by lazy { getSharedPreferences("keys", Context.MODE_PRIVATE) }
    private var page = Page.MAIN

    /** The package whose page is open, while [Page.APP] is. */
    private var app: String? = null
    private var scroll: ScrollView? = null
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(value: Float) = (value * density).toInt()

    /** iOS's grouped-list colours, in both appearances. Every text pair clears WCAG AA on the surface under it. */
    private class Palette(dark: Boolean) {
        val background = if (dark) Color.BLACK else Color.parseColor("#F2F2F7")
        val card = if (dark) Color.parseColor("#1C1C1E") else Color.WHITE
        val text = if (dark) Color.WHITE else Color.BLACK
        val secondary = if (dark) Color.parseColor("#98989F") else Color.parseColor("#6C6C70")
        val divider = if (dark) Color.parseColor("#38383A") else Color.parseColor("#C6C6C8")
        val link = if (dark) Color.parseColor("#0A84FF") else Color.parseColor("#0066CC")
        val destructive = if (dark) Color.parseColor("#FF453A") else Color.parseColor("#D70015")
        val on = if (dark) Color.parseColor("#30D158") else Color.parseColor("#1B7A33")
        // An off switch's track. The divider grey was 1.71:1 on a white card in light mode, with a white thumb on it:
        // too faint to see there was a control at all. This clears the 3:1 a control needs (WCAG 1.4.11).
        val off = if (dark) Color.parseColor("#636366") else Color.parseColor("#8E8E93")
        // A segmented control's track and its raised choice: iOS's grouped fill under a card-colored thumb.
        val track = if (dark) Color.BLACK else Color.parseColor("#E3E3E8")
        val raised = if (dark) Color.parseColor("#3A3A3C") else Color.WHITE
    }

    private lateinit var colors: Palette

    // What the report form holds. Kept here rather than in the fields, because every page switch rebuilds the
    // fields, and saved with the page so turning the phone doesn't lose what someone wrote.
    private var answers = DevLog.Answers()
    private var include = DevLog.Include()

    // Android 16 no longer calls onBackPressed for an app that targets it; the system's back gesture goes through
    // this callback instead, and only while there is a page to step back to.
    private val back: Any? by lazy {
        if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { page.parent?.let { show(it) } } else null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Each page names itself in large type, as Gboard's and Samsung's settings do; the window's own bar above it
        // would say "Keyd settings" a second time. The label stays, for Recents and the launcher.
        actionBar?.hide()
        DevLog.catchCrashes(this)
        // Settings can open before the keyboard has run since an update, so the toolbar is settled here too.
        Settings.settleToolbar(prefs, Settings.updated(this), Settings.firstInstalled(this))
        settings = Settings.load(prefs)
        page = savedInstanceState?.getString(PAGE)?.let { runCatching { Page.valueOf(it) }.getOrNull() }
            // Language settings, from the list the globe opens. The only page another screen may open this on.
            ?: Page.LANGUAGES.takeIf { intent?.getStringExtra(EXTRA_PAGE) == Page.LANGUAGES.name }
            // Like Folio: the first time Settings opens after an update, it opens on what's new.
            ?: if (WhatsNew.shouldShow(this, versionName())) Page.WHATS_NEW else Page.MAIN
        app = savedInstanceState?.getString(APP)
        query = savedInstanceState?.getString(QUERY).orEmpty()
        if (page == Page.APP && app == null) page = Page.APPS
        savedInstanceState?.let { state ->
            answers = DevLog.Answers(
                state.getString(ANSWER_APP).orEmpty(), state.getString(ANSWER_DID).orEmpty(), state.getString(ANSWER_SAW).orEmpty(),
            )
            state.getBooleanArray(INCLUDE)?.takeIf { it.size == 4 }?.let { flags ->
                include = DevLog.Include(flags[0], flags[1], flags[2], flags[3])
            }
        }
        colors = Palette(
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
        )
        window.decorView.setBackgroundColor(colors.background)
        render()
    }

    override fun onResume() {
        super.onResume()
        // Languages and shortcuts are changed on other screens; coming back should show what they are now.
        settings = Settings.load(prefs)
        render(keepScroll = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PAGE, page.name)
        outState.putString(APP, app)
        outState.putString(QUERY, query)
        outState.putString(ANSWER_APP, answers.app)
        outState.putString(ANSWER_DID, answers.did)
        outState.putString(ANSWER_SAW, answers.saw)
        outState.putBooleanArray(INCLUDE, booleanArrayOf(include.device, include.settings, include.errors, include.log))
    }

    // Keyd supports Android 12, which has no OnBackInvokedDispatcher: there, this is still how Back arrives. From
    // Android 13 the callback above takes over and this is never called, which is what lint is warning about.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Only reached below Android 13; above it the back callback does this.")
    override fun onBackPressed() {
        val parent = page.parent
        if (parent != null) show(parent) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun show(next: Page) {
        page = next
        render()
    }

    /**
     * Saves a change without rebuilding the page. A switch already shows its new state and a tick list moves its own
     * tick, so only rows that depend on another are touched - rebuilding everything used to send TalkBack's focus
     * back to the top of the page after every switch.
     */
    private fun change(update: Settings) {
        settings = update
        settings.save(prefs)
        // Read back rather than trusting the copy: saving can change more than the one switch.
        settings = Settings.load(prefs)
        refreshDependents()
    }

    /** Autocorrect follows suggestions: off and greyed while the strip is off, its own choice kept for when it's back. */
    private var autocorrect: Switch? = null
    private var autocorrectLabel: TextView? = null
    private var autocorrectRow: View? = null

    /** Suggest an emoji follows suggestions too, on the same page now, so it greys in place like autocorrect. */
    private var emojiSwitch: Pair<Switch, TextView>? = null

    private fun refreshDependents() {
        preview?.let { board ->
            board.settings = settings
            board.rows = Layouts.rows(Layer.LETTERS, false, board.rules, numberRow = settings.numberRow)
            board.requestLayout()
            board.invalidate()
        }
        emojiSwitch?.let { (switch, label) ->
            switch.isEnabled = settings.suggestions
            label.setTextColor(if (settings.suggestions) colors.text else colors.secondary)
            (switch.parent as? View)?.let { row -> row.isEnabled = settings.suggestions; row.isClickable = settings.suggestions }
        }
        val switch = autocorrect ?: return
        val enabled = settings.suggestions
        switch.setOnCheckedChangeListener(null)   // showing the state is not the person changing it
        switch.isChecked = Settings.correcting(settings)
        switch.isEnabled = enabled
        switch.setOnCheckedChangeListener { _, checked -> change(settings.copy(autocorrect = checked)) }
        autocorrectLabel?.setTextColor(if (enabled) colors.text else colors.secondary)
        autocorrectRow?.isEnabled = enabled
        autocorrectRow?.isClickable = enabled
    }

    private fun render(keepScroll: Boolean = false) {
        autocorrect = null
        preview = null
        emojiSwitch = null
        val y = if (keepScroll) scroll?.scrollY ?: 0 else 0
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(32f))
        }
        // What's New is a sheet, not a settings page: no back link, and Continue is how it is left.
        page.parent?.takeIf { page != Page.WHATS_NEW }?.let { parent ->
            column.addView(TextView(this).apply {
                text = getString(R.string.back_to, getString(parent.title))
                setTextColor(colors.link)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                minHeight = dp(48f)
                gravity = Gravity.CENTER_VERTICAL
                contentDescription = getString(parent.title)
                setOnClickListener { show(parent) }
            })
        }
        // What's New draws its own centred header, as Folio's does.
        if (page != Page.WHATS_NEW) column.addView(TextView(this).apply {
            text = when (page) {
                Page.APP -> appName(app.orEmpty())
                Page.CHANGES -> shownRelease()?.let { getString(R.string.status_version, it.version) } ?: getString(page.title)
                else -> getString(page.title)
            }
            setTextColor(colors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            isAccessibilityHeading = true
            setPadding(0, dp(if (page.parent == null) 24f else 4f), 0, dp(10f))
        })
        content(page, column)
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column)
            fitsSystemWindows = true
        }
        setContentView(scroll)
        val found = highlight?.let { column.findViewWithTag<View>(rowTag(it)) }
        highlight = null
        if (found != null) scroll?.post { reveal(found) } else scroll?.post { scroll?.scrollTo(0, y) }
        // A button that moved a row rebuilt the page; TalkBack goes back to the button, now in its new place.
        focusAfterRender?.let { wanted ->
            focusAfterRender = null
            column.findViewWithTag<View>(wanted)?.let { view ->
                view.post { view.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = back as OnBackInvokedCallback
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
            if (page.parent != null) {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            }
        }
    }

    /** A page's rows, into [column]: what the screen shows, and what search reads while [index] is set. */
    private fun content(page: Page, column: LinearLayout) {
        when (page) {
            Page.MAIN -> main(column)
            Page.TYPING -> typing(column)
            Page.WHAT_IT_FIXES -> whatItFixes(column)
            Page.APPS -> apps(column)
            Page.APP -> app(column, app.orEmpty())
            Page.KEYS -> keys(column)
            Page.TOOLBAR -> toolbar(column)
            Page.PERIOD -> period(column)
            Page.LANGUAGES -> languagePage(column)
            Page.LOOK -> look(column)
            Page.FEEL -> feel(column)
            Page.CLIPBOARD -> clipboard(column)
            Page.PRIVACY -> privacy(column)
            Page.DEVELOPER -> developer(column)
            Page.WHATS_NEW -> whatsNew(column)
            Page.REPORT -> report(column)
            Page.PREVIEW -> preview(column)
            Page.CHANGES -> changes(column)
            Page.HISTORY -> history(column)
            Page.SEARCH -> search(column)
        }
    }

    // ---- Pages ---------------------------------------------------------------------------------------------------

    private fun main(column: LinearLayout) {
        searchField(column)
        status(column)
        if (DevLog.crashedSinceLooked(this)) crashCard(column)
        group(column) {
            nav(it, SettingsIcon.Glyph.LANGUAGES, "#0071E3", getString(R.string.row_languages), typingIn()?.ownName ?: languages()) {
                show(Page.LANGUAGES)
            }
            val count = Shortcuts.decode(prefs.getString(SHORTCUTS, null)).size
            nav(it, SettingsIcon.Glyph.SHORTCUTS, "#5E5CE6", getString(R.string.row_shortcuts), count.toString()) {
                startActivity(Intent(this, ShortcutsActivity::class.java))
            }
        }
        header(column, getString(R.string.header_typing))
        group(column) {
            nav(it, SettingsIcon.Glyph.WAND, "#248A3D", getString(R.string.page_typing), null,
                subtitle = getString(R.string.row_typing_sub)) { show(Page.TYPING) }
            nav(it, SettingsIcon.Glyph.HAND, "#C93400", getString(R.string.page_keys), null,
                subtitle = getString(R.string.row_keys_sub)) { show(Page.KEYS) }
        }
        header(column, getString(R.string.header_look_and_feel))
        group(column) {
            nav(it, SettingsIcon.Glyph.PALETTE, "#8944AB", getString(R.string.page_look), null,
                subtitle = getString(R.string.row_look_sub)) { show(Page.LOOK) }
            nav(it, SettingsIcon.Glyph.SOUND, "#D70015", getString(R.string.page_feel), null,
                subtitle = feelSummary()) { show(Page.FEEL) }
        }
        header(column, getString(R.string.header_more))
        group(column) {
            nav(
                it, SettingsIcon.Glyph.CLIPBOARD, "#636366", getString(R.string.page_clipboard),
                getString(if (settings.clipboardHistory) R.string.value_on else R.string.value_off),
            ) { show(Page.CLIPBOARD) }
            nav(it, SettingsIcon.Glyph.PRIVACY, "#1B7A33", getString(R.string.page_privacy), getString(R.string.value_no_internet)) {
                show(Page.PRIVACY)
            }
        }
        if (DevLog.isDevBuild(packageName)) {
            val errors = DevLog.errors(this).size
            group(column) {
                nav(
                    it, SettingsIcon.Glyph.KEYS, "#B44A0C", getString(R.string.page_developer),
                    if (errors == 0) getString(R.string.value_no_errors)
                    else resources.getQuantityString(R.plurals.value_errors, errors, errors),
                ) { show(Page.DEVELOPER) }
            }
            footer(column, getString(R.string.footer_developer_row))
        }
        header(column, getString(R.string.header_about))
        group(column) {
            nav(it, SettingsIcon.Glyph.SPARKLES, "#0071E3", getString(R.string.row_whats_new), null) { openWhatsNew() }
            nav(it, SettingsIcon.Glyph.REPORT, "#9A5200", getString(R.string.row_report), null) { show(Page.REPORT) }
            value(it, getString(R.string.row_version), versionName())
        }
        footer(column, getString(R.string.footer_main))
    }

    /** What Sound and vibration is set to, in a few words: "Medium vibration, sound on". */
    private fun feelSummary(): String = getString(
        R.string.summary_feel,
        getString(when (settings.vibration) {
            Vibration.OFF -> R.string.summary_vibration_off
            Vibration.LIGHT -> R.string.summary_vibration_light
            Vibration.MEDIUM -> R.string.summary_vibration_medium
            Vibration.STRONG -> R.string.summary_vibration_strong
        }),
        getString(if (settings.sound) R.string.summary_sound_on else R.string.summary_sound_off),
    )

    // ---- Search --------------------------------------------------------------------------------------------------

    /** What is typed in the search field, kept with the page so turning the phone doesn't lose it. */
    private var query = ""

    /** The row to scroll to and mark once its page is drawn, by title: where a search result leads. */
    private var highlight: String? = null

    /** Set while search reads the pages: every row helper notes its row here instead of building it. */
    private var index: MutableList<SearchEntry>? = null
    private var indexPage = Page.MAIN
    private var indexHeader: String? = null
    private var indexGroupStart = 0

    /** Notes a row for search while [index] is set, and says so, so the helper builds nothing. */
    private fun indexed(title: String, subtitle: String? = null): Boolean {
        val list = index ?: return false
        list += SearchEntry(title, subtitle, null, indexHeader, indexPage.name, getString(indexPage.title))
        return true
    }

    /**
     * Every row on every page someone can search, read from the pages themselves. The pages are drawn into nothing
     * with [index] set, so this can never fall out of step with what the pages show.
     */
    internal fun searchIndex(): List<SearchEntry> {
        val out = mutableListOf<SearchEntry>()
        index = out
        try {
            for (searched in SEARCHED + listOfNotNull(Page.DEVELOPER.takeIf { DevLog.isDevBuild(packageName) })) {
                indexPage = searched
                indexHeader = null
                indexGroupStart = out.size
                content(searched, LinearLayout(this))
            }
        } finally {
            index = null
        }
        return out.distinctBy { it.title to it.page }
    }

    /** The field on the first page. It only looks like one: a tap opens the search page, with the keyboard up. */
    private fun searchField(column: LinearLayout) {
        if (index != null) return
        column.addView(searchBox().apply {
            addView(TextView(context).apply {
                text = getString(R.string.search_hint)
                setTextColor(colors.secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            })
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.search_hint)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            }
            setOnClickListener { show(Page.SEARCH) }
        })
    }

    /** The rounded field both search boxes are drawn in, with the magnifier at its start. */
    private fun searchBox() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48f)
        setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
        val fill = colors.card
        background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(12f).toFloat() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4f) }
        addView(SearchGlyph(context, colors.secondary), LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { marginEnd = dp(10f) })
    }

    /**
     * Samsung's settings search: every setting whose name, the line under it or the note under its group holds
     * what was typed, each with the page it is on, so a setting can be found without knowing which group it was
     * put in. The results change as each letter is typed; the field is never rebuilt, so the keyboard stays up.
     */
    private fun search(column: LinearLayout) {
        if (index != null) return
        val entries = searchIndex()
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun showResults() {
            results.removeAllViews()
            if (query.isBlank()) return
            val hits = SettingsSearch.find(entries, query)
            if (hits.isEmpty()) {
                results.addView(TextView(this).apply {
                    text = getString(R.string.search_none, query.trim())
                    setTextColor(colors.secondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    gravity = Gravity.CENTER
                    setPadding(dp(16f), dp(32f), dp(16f), dp(16f))
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                })
                return
            }
            header(results, resources.getQuantityString(R.plurals.search_results, hits.size, hits.size))
            (results.getChildAt(results.childCount - 1) as? TextView)?.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            group(results) { card ->
                hits.forEach { hit ->
                    nav(card, null, null, hit.title, null, subtitle = hit.where) {
                        highlight = hit.title
                        show(Page.valueOf(hit.page))
                    }
                }
            }
        }
        val field = EditText(this).apply {
            setText(query)
            hint = getString(R.string.search_hint)
            setTextColor(colors.text)
            setHintTextColor(colors.secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER
            contentDescription = getString(R.string.search_hint)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    showResults()
                }
            })
        }
        column.addView(searchBox().apply {
            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        column.addView(results)
        footer(column, getString(R.string.search_note))
        showResults()
        field.requestFocus()
        field.post { getSystemService(InputMethodManager::class.java)?.showSoftInput(field, 0) }
    }

    /** Scrolls a search result's row into view and marks it for a moment, and gives it TalkBack's focus. */
    private fun reveal(row: View) {
        var top = 0
        var v: View? = row
        while (v != null && v !== scroll) {
            top += v.top
            v = v.parent as? View
        }
        scroll?.scrollTo(0, (top - dp(96f)).coerceAtLeast(0))
        val before = row.background
        val mark = colors.link
        row.background = GradientDrawable().apply { setColor(mark); alpha = 48 }
        row.postDelayed({ row.background = before }, 1500)
        row.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
    }

    private fun rowTag(title: String) = "row:$title"

    /**
     * Shown after Keyd has crashed, until the person either sends a report or says not now. The crash is already
     * written down; this only asks, once, whether they want to pass it on.
     */
    private fun crashCard(column: LinearLayout) {
        group(column) { card ->
            row(card, iconSpace = true).apply {
                addView(SettingsIcon(context, SettingsIcon.Glyph.WARNING, Color.parseColor("#D70015")),
                    LinearLayout.LayoutParams(dp(29f), dp(29f)).apply { marginEnd = dp(13f) })
                addView(label(getString(R.string.crash_title)))
                isFocusable = true
            }
            link(card, getString(R.string.crash_send)) { show(Page.REPORT) }
            link(card, getString(R.string.crash_not_now)) {
                DevLog.markLooked(this)
                render(keepScroll = true)
            }
        }
        footer(column, getString(R.string.footer_crash))
    }

    /**
     * The report form: three questions in the person's own words, and which of Keyd's own records to add. Nothing
     * here is sent; the next page shows the whole report, and only its Share button hands it to anyone.
     */
    private fun report(column: LinearLayout) {
        footer(column, getString(R.string.report_intro))
        header(column, getString(R.string.header_what_happened))
        group(column) { card ->
            question(card, getString(R.string.report_app), answers.app, multiLine = false) { answers = answers.copy(app = it) }
            question(card, getString(R.string.report_did), answers.did, multiLine = true) { answers = answers.copy(did = it) }
            question(card, getString(R.string.report_saw), answers.saw, multiLine = true) { answers = answers.copy(saw = it) }
        }
        header(column, getString(R.string.header_include))
        val errors = DevLog.errors(this).size
        val lines = DevLog.lines(this).size
        // A log with nothing in it would add a section that says so; the switch is off and greyed instead.
        if (lines == 0 && include.log) include = include.copy(log = false)
        group(column) { card ->
            switchRow(card, getString(R.string.include_device), include.device) { include = include.copy(device = it) }
            switchRow(card, getString(R.string.include_settings), include.settings,
                subtitle = getString(R.string.include_settings_note)) { include = include.copy(settings = it) }
            switchRow(card, getString(R.string.include_errors), include.errors,
                subtitle = if (errors == 0) getString(R.string.include_errors_none)
                else resources.getQuantityString(R.plurals.value_errors, errors, errors),
            ) { include = include.copy(errors = it) }
            switchRow(card, getString(R.string.include_log), include.log,
                subtitle = if (lines == 0) getString(R.string.include_log_empty)
                else resources.getQuantityString(R.plurals.include_log_lines, lines, lines),
                enabled = lines > 0,
            ) { include = include.copy(log = it) }
        }
        footer(column, getString(R.string.footer_include))
        primary(column, getString(R.string.report_preview)) { show(Page.PREVIEW) }
        footer(column, getString(R.string.footer_report_send))
    }

    /** Every line that would be shared, exactly as it will go, and the one button that shares it. */
    private fun preview(column: LinearLayout) {
        val text = DevLog.report(this, buildLine(), answers, include)
        footer(column, getString(R.string.preview_note))
        group(column) { card ->
            card.addView(TextView(this).apply {
                this.text = text
                setTextColor(colors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            })
        }
        primary(column, getString(R.string.preview_share)) {
            runCatching {
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.report_subject_user))
                        .putExtra(Intent.EXTRA_TEXT, text),
                    getString(R.string.preview_share),
                ))
                // Whether it was actually sent is the other app's business; opening the sheet means it was seen.
                DevLog.markLooked(this)
            }
        }
        footer(column, getString(R.string.footer_preview))
    }

    /** One question on the report form: its label above, a field the label names for TalkBack, 48dp at least. */
    private fun question(card: LinearLayout, title: String, current: String, multiLine: Boolean, changed: (String) -> Unit) {
        row(card, iconSpace = false).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.NO_GRAVITY
            val field = EditText(context).apply {
                id = View.generateViewId()
                setText(current)
                setTextColor(colors.text)
                setHintTextColor(colors.secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                minHeight = dp(48f)
                background = null
                setPadding(0, dp(4f), 0, dp(4f))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    (if (multiLine) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) = changed(s?.toString().orEmpty())
                })
            }
            addView(TextView(context).apply {
                text = title
                setTextColor(colors.secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                labelFor = field.id
            })
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /**
     * The card at the top: which keyboard this is, and whether Android is actually using it. Android keeps "added"
     * and "chosen" on two different screens and says nothing about either afterwards, so this reads them back, with
     * the one button that fixes whichever step is missing.
     */
    private fun status(column: LinearLayout) {
        val manager = getSystemService(InputMethodManager::class.java)
        val state = setupState(
            added = ourMethod()?.let { method -> manager?.enabledInputMethodList?.any { it.id == method.id } } == true,
            current = runCatching { AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD) }.getOrNull(),
            packageName = packageName,
        )
        group(column) { card ->
            if (index != null) return@group
            val name = applicationInfo.loadLabel(packageManager).toString()
            // "Keyd is on" only when it is both on and chosen; otherwise the name, and what is missing under it.
            val title = if (state == SetupState.IN_USE) getString(R.string.status_on, name) else name
            val line = when (state) {
                SetupState.IN_USE -> getString(R.string.status_chosen, versionName())
                SetupState.ADDED -> getString(R.string.status_line, getString(R.string.status_added), versionName())
                SetupState.NOT_ADDED -> getString(R.string.status_line, getString(R.string.status_not_added), versionName())
            }
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
                addView(android.widget.ImageView(context).apply {
                    setImageDrawable(applicationInfo.loadIcon(packageManager))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(56f), dp(56f)).apply { marginEnd = dp(14f) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = title
                        setTextColor(colors.text)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    addView(TextView(context).apply {
                        text = line
                        setTextColor(if (state == SetupState.IN_USE) colors.secondary else colors.link)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                isFocusable = true
                contentDescription = "$title, $line"
                card.addView(this)
            }
            when (state) {
                SetupState.NOT_ADDED -> link(card, getString(R.string.status_turn_on)) {
                    runCatching { startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS)) }
                }
                SetupState.ADDED -> link(card, getString(R.string.status_choose)) { manager?.showInputMethodPicker() }
                SetupState.IN_USE -> Unit
            }
        }
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

    private fun versionCode(): Long =
        runCatching { packageManager.getPackageInfo(packageName, 0).longVersionCode }.getOrDefault(0L)

    private fun buildLine(): String = "$packageName ${versionName()} (${versionCode()}) ${BuildConfig.GIT_COMMIT}"

    private val historyOpen = mutableSetOf<String>()

    /** Which release What's New shows when something other than this build's own is asked for; see [openWhatsNew]. */
    private var release: String? = null

    /** What's New for [version], or for this build when null. Also how a test draws another release's sheet. */
    internal fun openWhatsNew(version: String? = null) {
        release = version
        show(Page.WHATS_NEW)
    }

    /** The release What's New is about: the one asked for, else this build's, else the newest in the file. */
    private fun shownRelease(): ReleaseNotes? {
        val notes = WhatsNew.notes(this)
        val version = release ?: WhatsNew.releaseVersion(versionName())
        return notes.firstOrNull { it.version == version } ?: notes.firstOrNull()
    }

    /** The release's new features, and everything else it changed or fixed, as What's New splits them. */
    private fun ReleaseNotes.features() = sections.filter { it.first.equals("Added", true) }.flatMap { it.second }.map(WhatsNew::split)
    private fun ReleaseNotes.others() = sections.filterNot { it.first.equals("Added", true) }

    /**
     * What's New, as a sheet like Apple's and Folio's: the icon, the title and a version pill; the first five new
     * features, each with a picture of its own, a bold title and a line about it; then a row for the rest of the
     * features and one for the fixes, both leading to the whole release, and every earlier release a tap further.
     * All of it comes from the CHANGELOG.md bundled into this build.
     */
    private fun whatsNew(column: LinearLayout) {
        val shown = shownRelease()
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(28f), 0, dp(8f))
            addView(android.widget.ImageView(context).apply {
                setImageDrawable(applicationInfo.loadIcon(packageManager))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(72f), dp(72f)))
            addView(TextView(context).apply {
                text = getString(R.string.whats_new_heading)
                setTextColor(colors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isFocusable = true
                isAccessibilityHeading = true
                setPadding(0, dp(12f), 0, 0)
            })
            shown?.let { notes ->
                addView(TextView(context).apply {
                    text = getString(R.string.status_version, notes.version)
                    setTextColor(colors.link)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                    val tint = colors.link
                    background = GradientDrawable().apply { setColor(tint); alpha = 40; cornerRadius = dp(14f).toFloat() }
                    setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
                    contentDescription = listOfNotNull(
                        text, notes.date?.takeIf { !it.equals("Unreleased", true) },
                    ).joinToString(", ")
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8f) })
            }
        })
        val features = shown?.features().orEmpty()
        val highlights = features.take(SHOWN_FEATURES)
        val glyphs = WhatsNew.glyphs(highlights.map { it.title ?: it.detail })
        highlights.forEachIndexed { i, note ->
            column.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(4f), dp(12f), dp(4f), dp(2f))
                val (glyph, tile) = glyphs[i]
                addView(SettingsIcon(context, glyph, Color.parseColor(tile)),
                    LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { marginEnd = dp(14f) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    note.title?.let { title ->
                        addView(TextView(context).apply {
                            text = title
                            setTextColor(colors.text)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                            typeface = Typeface.DEFAULT_BOLD
                        })
                    }
                    addView(TextView(context).apply {
                        text = note.detail
                        setTextColor(colors.secondary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setLineSpacing(0f, 1.1f)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                isFocusable = true
            })
        }
        val more = features.drop(SHOWN_FEATURES)
        val fixes = shown?.others().orEmpty().sumOf { it.second.size }
        if (more.isNotEmpty() || fixes > 0) group(column) { card ->
            if (more.isNotEmpty()) nav(
                card, null, null, resources.getQuantityString(R.plurals.more_features, more.size, more.size), null,
                subtitle = more.mapIndexed { i, note ->
                    val name = note.title ?: note.detail
                    if (i == 0) name else name.replaceFirstChar { it.lowercase() }
                }.joinToString(", "),
            ) { show(Page.CHANGES) }
            if (fixes > 0) nav(card, null, null, getString(R.string.fixes_and_improvements), fixes.toString()) { show(Page.CHANGES) }
        }
        if (WhatsNew.notes(this).any { it != shown }) group(column) {
            nav(it, null, null, getString(R.string.row_earlier_versions), null) { show(Page.HISTORY) }
        }
        primary(column, getString(R.string.action_continue)) { show(Page.MAIN) }
    }

    /** Everything in the release What's New is about: the features past the first five, then each other section. */
    private fun changes(column: LinearLayout) {
        val shown = shownRelease() ?: return
        val more = shown.features().drop(SHOWN_FEATURES)
        if (more.isNotEmpty()) {
            header(column, getString(R.string.header_more_features))
            group(column) { card -> more.forEach { note(card, it) } }
        }
        shown.others().forEach { (heading, items) ->
            if (heading.isNotBlank()) header(column, heading.uppercase(java.util.Locale.ROOT))
            group(column) { card -> items.map(WhatsNew::split).forEach { note(card, it) } }
        }
        if (WhatsNew.notes(this).any { it != shown }) group(column) {
            nav(it, null, null, getString(R.string.row_earlier_versions), null) { show(Page.HISTORY) }
        }
    }

    /** One changelog line in a card: its bold title, if it has one, then the rest. Read, not pressed. */
    private fun note(card: LinearLayout, note: NoteItem) {
        row(card, iconSpace = false).apply {
            val line = android.text.SpannableStringBuilder()
            note.title?.let { title ->
                line.append("$title: ")
                line.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, title.length + 1, 0)
            }
            line.append(note.detail)
            addView(label("").apply {
                text = line
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setLineSpacing(0f, 1.1f)
            })
            isFocusable = true
        }
    }

    /** Every release before the one What's New is about, each opening to its notes, as Version History did. */
    private fun history(column: LinearLayout) {
        val shown = shownRelease()
        WhatsNew.notes(this).filter { it != shown }.forEach { notes ->
            val open = notes.version in historyOpen
            group(column) { card ->
                disclosure(card, getString(R.string.status_version, notes.version), notes.date, open) {
                    if (open) historyOpen -= notes.version else historyOpen += notes.version
                    render(keepScroll = true)
                }
                if (open) notes.sections.forEach { (_, items) ->
                    items.forEach { text ->
                        row(card, iconSpace = false).apply { addView(label("• " + text.replace("**", ""))) }
                    }
                }
            }
        }
    }

    /** A row that opens and closes what's under it, with the count or date on the right, like iOS disclosure rows. */
    private fun disclosure(card: LinearLayout, title: String, value: String?, open: Boolean, toggle: () -> Unit) {
        row(card, iconSpace = false).apply {
            addView(label(title))
            value?.let { addView(trailing(it)) }
            addView(TextView(context).apply {
                text = if (open) "⌃" else "⌄"
                setTextColor(colors.secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            isClickable = true
            background = selectable()
            contentDescription = listOfNotNull(title, value).joinToString(", ")
            stateDescription = getString(if (open) R.string.state_expanded else R.string.state_collapsed)
            setOnClickListener { toggle() }
        }
    }

    /** Keyd Dev only: switch detailed logging on, see recent errors, and share or clear them. */
    /**
     * The kinds of field the test box can be, each the way another app would ask for it, so the keys, the return key
     * and the address rules can be tried here instead of in someone's messages or browser.
     */
    internal enum class TestKind(val label: Int, val inputType: Int, val action: Int) {
        TEXT(R.string.test_kind_text, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_NONE),
        SEARCH(R.string.test_kind_search, InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH),
        // The Google app's search box and some browsers' are built this way: more than one line, and still Search.
        SEARCH_LINES(R.string.test_kind_search_lines, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_SEARCH),
        URL(R.string.test_kind_url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_GO),
        EMAIL(R.string.test_kind_email, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, EditorInfo.IME_ACTION_DONE),
    }

    /** Which kind the test box is, kept across the page redrawing, not across launches. */
    private var testKind = TestKind.TEXT

    /** Keyd Dev's own place to type: a field, and the kinds of field it can pretend to be. */
    private fun testBox(column: LinearLayout) {
        header(column, getString(R.string.header_test_box))
        lateinit var field: EditText
        fun apply(kind: TestKind) {
            field.inputType = kind.inputType
            // Never personal learning: testing a fix would otherwise teach Keyd the typo it was testing.
            field.imeOptions = kind.action or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(field)
        }
        group(column) { card ->
            row(card, iconSpace = false).apply {
                field = object : EditText(context) {
                    // Android adds "plain Enter" to every field of several lines on its own; the search boxes this
                    // kind stands in for take it off again, so this one does too.
                    override fun onCreateInputConnection(info: EditorInfo): android.view.inputmethod.InputConnection? =
                        super.onCreateInputConnection(info).also {
                            if (testKind == TestKind.SEARCH_LINES) {
                                info.imeOptions = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION.inv()
                            }
                        }
                }.apply {
                    hint = getString(R.string.test_box_hint)
                    setTextColor(colors.text)
                    setHintTextColor(colors.secondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    minHeight = dp(48f)
                    background = null
                    setPadding(0, dp(4f), 0, dp(4f))
                    // Says what the return key sent, so Search, Go and Done can be checked here, not in a browser.
                    setOnEditorActionListener { _, action, _ ->
                        val name = when (action) {
                            EditorInfo.IME_ACTION_SEARCH -> "Search"
                            EditorInfo.IME_ACTION_GO -> "Go"
                            EditorInfo.IME_ACTION_DONE -> "Done"
                            EditorInfo.IME_ACTION_SEND -> "Send"
                            EditorInfo.IME_ACTION_NEXT -> "Next"
                            else -> "Enter"
                        }
                        android.widget.Toast.makeText(context, getString(R.string.test_box_sent, name), android.widget.Toast.LENGTH_SHORT).show()
                        true
                    }
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                addView(field)
            }
            val ticks = TestKind.entries.map { kind ->
                val tick = TextView(this).apply {
                    text = "✓"
                    setTextColor(colors.link)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                val line = row(card, iconSpace = false).apply {
                    addView(label(getString(kind.label)))
                    addView(tick)
                    isClickable = true
                    background = selectable()
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.isCheckable = true
                            info.isChecked = kind == testKind
                        }
                    }
                }
                Triple(kind, line, tick)
            }
            fun show() = ticks.forEach { (kind, line, tick) ->
                tick.visibility = if (kind == testKind) View.VISIBLE else View.INVISIBLE
                line.isSelected = kind == testKind
            }
            ticks.forEach { (kind, line, _) ->
                line.setOnClickListener {
                    testKind = kind
                    apply(kind)
                    show()
                    // Focus alone doesn't bring the keyboard up; ask for it, so the new kind of field is on screen.
                    field.requestFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, 0)
                }
            }
            show()
        }
        field.inputType = testKind.inputType
        field.imeOptions = testKind.action or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        footer(column, getString(R.string.footer_test_box))
    }

    private fun developer(column: LinearLayout) {
        testBox(column)
        group(column) {
            switchRow(it, getString(R.string.settings_dev_logging), DevLog.loggingOn(this)) { on -> DevLog.setLogging(this, on) }
        }
        footer(column, getString(R.string.footer_dev_logging, DevLog.MAX_LINES))
        header(column, getString(R.string.header_recent_errors))
        val errors = DevLog.errors(this)
        group(column) { card ->
            if (errors.isEmpty()) value(card, getString(R.string.row_errors), getString(R.string.value_none))
            errors.take(10).forEach { entry ->
                val first = entry.lineSequence().first()
                row(card, iconSpace = false).apply {
                    addView(TextView(context).apply {
                        text = first.substringAfter(' ').substringAfter(' ')
                        setTextColor(colors.text)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    // Entries start "yyyy-MM-dd HH:mm:ss"; the time is enough beside the name.
                    addView(trailing(first.split(' ').getOrNull(1).orEmpty()))
                    isFocusable = true
                }
            }
        }
        group(column) {
            link(it, getString(R.string.row_share_report)) {
                val text = DevLog.report(this, buildLine())
                runCatching {
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.report_subject))
                            .putExtra(Intent.EXTRA_TEXT, text),
                        getString(R.string.row_share_report),
                    ))
                }
            }
            action(it, getString(R.string.row_clear_log), enabled = true) {
                DevLog.clear(this)
                render(keepScroll = true)
            }
        }
        footer(column, getString(R.string.footer_dev_share))
        header(column, getString(R.string.header_build))
        group(column) {
            value(it, getString(R.string.row_app), packageName)
            value(it, getString(R.string.row_version), "${versionName()} (${versionCode()})")
            value(it, getString(R.string.row_commit), BuildConfig.GIT_COMMIT)
        }
    }

    private fun typing(column: LinearLayout) {
        header(column, getString(R.string.header_suggestions))
        group(column) {
            // The strip's own switch: fixing typos and the emoji both come from it, so both follow it.
            switchRow(
                it, getString(R.string.settings_suggestions), settings.suggestions,
                subtitle = getString(R.string.settings_suggestions_sub),
            ) { on -> change(settings.copy(suggestions = on)) }
            switchRow(
                it, getString(R.string.settings_suggest_emoji), settings.suggestEmoji,
                subtitle = getString(R.string.settings_suggest_emoji_sub), enabled = settings.suggestions,
            ) { on -> change(settings.copy(suggestEmoji = on)) }
        }
        // Kept from the old page: where the emoji come from is a privacy answer, not decoration.
        footer(column, getString(R.string.settings_suggest_emoji_note))
        header(column, getString(R.string.header_corrections))
        group(column) {
            // Autocorrect is the strip's top answer applied for you, so with the strip off it is off too, whatever it
            // is set to. The switch shows that - off, and greyed - and keeps the choice for when the strip is back.
            toggle(
                it, getString(R.string.settings_autocorrect), Settings.correcting(settings), enabled = settings.suggestions,
            ) { on -> settings.copy(autocorrect = on) }
            toggle(it, getString(R.string.settings_spell_check), settings.spellCheck) { on -> settings.copy(spellCheck = on) }
            toggle(it, getString(R.string.settings_learn), settings.learn) { on -> settings.copy(learn = on) }
            val fixes = Insights.decode(prefs.getString(INSIGHTS, null)).fixed().sumOf { entry -> entry.count }
            nav(it, null, null, getString(R.string.page_what_it_fixes),
                resources.getQuantityString(R.plurals.value_fixes, fixes, fixes)) { show(Page.WHAT_IT_FIXES) }
        }
        footer(column, getString(R.string.footer_corrections))
        // And what learning never touches.
        footer(column, getString(R.string.settings_learn_note))
        header(column, getString(R.string.header_capitals))
        group(column) {
            toggle(it, getString(R.string.settings_capitals), settings.autoCapitalise) { on -> settings.copy(autoCapitalise = on) }
            toggle(it, getString(R.string.settings_double_space), settings.doubleSpaceFullStop) { on -> settings.copy(doubleSpaceFullStop = on) }
        }
        val changed = AppProfiles.load(prefs).changed.size
        group(column) {
            nav(it, SettingsIcon.Glyph.APPS, "#C75C00", getString(R.string.row_per_app),
                resources.getQuantityString(R.plurals.value_apps, changed, changed)) { show(Page.APPS) }
        }
    }

    // ---- Per-app settings ----------------------------------------------------------------------------------------

    /**
     * Every app typed in lately: the ones set their own way first, then the rest. Only apps Keyd has actually been
     * used in are here, because that is the only way it hears of an app at all.
     */
    private fun apps(column: LinearLayout) {
        footer(column, getString(R.string.per_app_note))
        val apps = AppProfiles.load(prefs)
        if (apps.recentApps.isEmpty()) {
            group(column) { card ->
                row(card, iconSpace = false).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(16f), dp(20f), dp(16f), dp(20f))
                    addView(TextView(context).apply {
                        text = getString(R.string.per_app_empty)
                        setTextColor(colors.text)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                        gravity = Gravity.CENTER
                    })
                    addView(TextView(context).apply {
                        text = getString(R.string.per_app_empty_note)
                        setTextColor(colors.secondary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        gravity = Gravity.CENTER
                    })
                    isFocusable = true
                }
            }
        }
        if (apps.changed.isNotEmpty()) {
            header(column, getString(R.string.header_changed))
            group(column) { card ->
                apps.changed.forEach { app -> appRow(card, app, changeSummary(apps.changes(app, settings))) }
            }
        }
        if (apps.asUsual.isNotEmpty()) {
            header(column, getString(R.string.header_as_usual))
            group(column) { card -> apps.asUsual.forEach { app -> appRow(card, app, null) } }
        }
        if (apps.recentApps.isNotEmpty()) footer(column, getString(R.string.per_app_footer, AppProfiles.LIMIT))
    }

    /** "No fixing" when one thing is different, "2 changes" when more are. */
    private fun changeSummary(changes: List<AppProfiles.Change>): String = when (changes.size) {
        0 -> getString(R.string.change_none)
        1 -> getString(when (changes.single()) {
            AppProfiles.Change.SUGGESTIONS_OFF -> R.string.change_suggestions_off
            AppProfiles.Change.SUGGESTIONS_ON -> R.string.change_suggestions_on
            AppProfiles.Change.FIXING_OFF -> R.string.change_fixing_off
            AppProfiles.Change.FIXING_ON -> R.string.change_fixing_on
            AppProfiles.Change.LEARNING_OFF -> R.string.change_learning_off
            AppProfiles.Change.LEARNING_ON -> R.string.change_learning_on
            AppProfiles.Change.NUMBER_ROW_ON -> R.string.change_number_row_on
            AppProfiles.Change.NUMBER_ROW_OFF -> R.string.change_number_row_off
        })
        else -> resources.getQuantityString(R.plurals.value_changes, changes.size, changes.size)
    }

    /** One app in the list: its icon and name, what is different about it, and the way into its page. */
    private fun appRow(card: LinearLayout, app: String, value: String?) {
        val name = appName(app)
        row(card, iconSpace = true).apply {
            addView(android.widget.ImageView(context).apply {
                setImageDrawable(appIcon(app))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(29f), dp(29f)).apply { marginEnd = dp(13f) })
            addView(label(name))
            value?.let { addView(trailing(it)) }
            addView(chevron())
            isClickable = true
            background = selectable()
            contentDescription = listOfNotNull(name, value).joinToString(", ")
            setOnClickListener {
                this@SettingsActivity.app = app
                show(Page.APP)
            }
        }
    }

    /**
     * What autocorrect fixed and what was put back, most counted first, and the tap that makes either permanent.
     * Every row is one pair of words and a number; the text around them never reaches this screen.
     */
    private fun whatItFixes(column: LinearLayout) {
        footer(column, getString(R.string.what_it_fixes_intro))
        val insights = Insights.decode(prefs.getString(INSIGHTS, null))
        val rules = Shortcuts.decode(prefs.getString(SHORTCUTS, null))
        val learned = Learned.decode(prefs.getString(LEARNED, null))
        header(column, getString(R.string.header_fixed_for_you))
        group(column) { card ->
            val fixed = insights.fixed().take(SHOWN_PAIRS)
            if (fixed.isEmpty()) nothingYet(card)
            fixed.forEach { entry ->
                val replacement = entry.replacement ?: return@forEach
                val done = rules.expand(entry.typed)?.equals(replacement, ignoreCase = true) == true
                tally(
                    card, getString(R.string.fixed_pair, entry.typed, replacement), entry.count,
                    getString(R.string.fixed_tap), getString(R.string.fixed_rule_added), done,
                ) {
                    answer(Insights.Offer(entry.typed, replacement))
                    toast(getString(R.string.toast_rule_added))
                }
            }
        }
        header(column, getString(R.string.header_you_put_back))
        group(column) { card ->
            val back = insights.putBack().take(SHOWN_PAIRS)
            if (back.isEmpty()) nothingYet(card)
            back.forEach { entry ->
                tally(
                    card, entry.typed, entry.count, getString(R.string.put_back_tap), getString(R.string.put_back_kept),
                    learned.count(entry.typed.lowercase()) > 0,
                ) {
                    answer(Insights.Offer(entry.typed, null))
                }
            }
        }
        footer(column, getString(R.string.footer_what_it_fixes))
        group(column) {
            toggle(it, getString(R.string.settings_offer_rules), settings.offerRules) { on -> settings.copy(offerRules = on) }
        }
        footer(column, getString(R.string.footer_offer_rules))
        group(column) {
            action(it, getString(R.string.row_forget_counts), enabled = !insights.isEmpty()) { confirmForgetCounts() }
        }
    }

    private fun nothingYet(card: LinearLayout) {
        row(card, iconSpace = false).apply {
            addView(label(getString(R.string.insights_nothing_yet), colors.secondary))
            isFocusable = true
        }
    }

    /**
     * Keep or Always, from here rather than the strip, and the same answer: the strip never asks about it again.
     * Everything is read again at the tap, since the keyboard may have changed any of it since the page was drawn,
     * and it reads them all again when the next field opens.
     */
    private fun answer(offer: Insights.Offer) {
        val insights = Insights.decode(prefs.getString(INSIGHTS, null))
        val learned = Learned.decode(prefs.getString(LEARNED, null))
        val shortcuts = Shortcuts.decode(prefs.getString(SHORTCUTS, null))
        insights.answer(offer, accepted = true, learned, shortcuts)
        prefs.edit()
            .putString(INSIGHTS, insights.encode())
            .putString(LEARNED, learned.encode())
            .putString(SHORTCUTS, shortcuts.encode())
            .apply()
    }

    /**
     * One counted pair: the words, how many times, and what a tap does. Once done, the line under it says so and the
     * row stops being a button. Changed in place, so TalkBack stays where it was.
     */
    private fun tally(
        card: LinearLayout, title: String, count: Int, tapNote: String, doneNote: String, done: Boolean, run: () -> Unit,
    ) {
        row(card, iconSpace = false).apply {
            val times = resources.getQuantityString(R.plurals.value_times, count, count)
            val note = TextView(context).apply {
                text = if (done) doneNote else tapNote
                setTextColor(colors.secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                addView(note)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(trailing(times))
            fun describe() { contentDescription = "$title, $times, ${note.text}" }
            describe()
            isFocusable = true
            if (!done) {
                isClickable = true
                background = selectable()
                setOnClickListener {
                    run()
                    note.text = doneNote
                    describe()
                    setOnClickListener(null)
                    isClickable = false
                    sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
            }
        }
    }

    /**
     * An app's own name, or its package name when Android won't say.
     *
     * Keyd asks for no permission to see other apps. Android still shows a keyboard the app it is typing into, which
     * is how these names normally resolve; an app it has not typed in since the phone restarted may stay hidden, and
     * then the package name is the honest fallback.
     */
    private fun appName(app: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(app, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: app

    private fun appIcon(app: String): android.graphics.drawable.Drawable =
        runCatching { packageManager.getApplicationIcon(app) }.getOrNull() ?: packageManager.defaultActivityIcon

    /** Switches on one app's page, kept so turning "use the usual settings" on or off can grey them in place. */
    private val appSwitches = mutableListOf<Pair<Switch, TextView>>()

    /** One app's page: whether it follows the usual settings, and if not, its own four switches. */
    private fun app(column: LinearLayout, app: String) {
        appSwitches.clear()
        val name = appName(app)
        var profile = AppProfiles.load(prefs).profile(app)
        fun save(update: AppProfiles.Profile) {
            profile = update
            AppProfiles.load(prefs).apply { set(app, update) }.save(prefs)
        }
        lateinit var refresh: () -> Unit
        group(column) {
            switchRow(it, getString(R.string.app_use_usual), profile.useUsual) { on ->
                save(profile.copy(useUsual = on))
                refresh()
            }
        }
        footer(column, getString(R.string.app_use_usual_note, name))
        header(column, getString(R.string.header_in_app, name.uppercase()))
        group(column) { card ->
            appSwitch(card, getString(R.string.settings_suggestions)) { on -> save(profile.copy(suggestions = on)); refresh() }
            appSwitch(card, getString(R.string.settings_autocorrect)) { on -> save(profile.copy(autocorrect = on)) }
            appSwitch(card, getString(R.string.settings_learn)) { on -> save(profile.copy(learn = on)) }
            appSwitch(card, getString(R.string.settings_number_row)) { on -> save(profile.copy(numberRow = on)) }
        }
        refresh = {
            val mine = profile.over(settings)
            val states = listOf(
                mine.suggestions to !profile.useUsual,
                // Fixing is the strip's top answer applied, so it goes with the strip, as it does on the first page.
                Settings.correcting(mine) to (!profile.useUsual && mine.suggestions),
                mine.learn to !profile.useUsual,
                mine.numberRow to !profile.useUsual,
            )
            appSwitches.zip(states).forEach { (parts, state) ->
                val (switch, title) = parts
                val (checked, enabled) = state
                switch.tag = SHOWING
                switch.isChecked = checked
                switch.tag = null
                switch.isEnabled = enabled
                title.setTextColor(if (enabled) colors.text else colors.secondary)
                (switch.parent as View).isEnabled = enabled
                (switch.parent as View).isClickable = enabled
            }
        }
        refresh()
        group(column) { card ->
            action(card, getString(R.string.app_forget), enabled = true) {
                AppProfiles.load(prefs).apply { forget(app) }.save(prefs)
                this.app = null
                show(Page.APPS)
            }
        }
        footer(column, getString(R.string.app_forget_note))
    }

    /** A switch on an app's page. Its state is set by that page's refresh, which is not the person changing it. */
    private fun appSwitch(card: LinearLayout, title: String, changed: (Boolean) -> Unit) {
        switchRow(card, title, on = false) { on -> changed(on) }
        val row = card.getChildAt(card.childCount - 1) as ViewGroup
        val switch = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<Switch>().single()
        val label = row.getChildAt(0) as TextView
        switch.setOnCheckedChangeListener { _, checked -> if (switch.tag !== SHOWING) changed(checked) }
        appSwitches += switch to label
    }

    private fun keys(column: LinearLayout) {
        header(column, getString(R.string.header_keys))
        group(column) {
            toggle(it, getString(R.string.settings_number_row), settings.numberRow) { on -> settings.copy(numberRow = on) }
            toggle(it, getString(R.string.settings_accents), settings.accents) { on -> settings.copy(accents = on) }
            nav(it, null, null, getString(R.string.row_period), spacedSymbols(settings.periodSymbols)) { show(Page.PERIOD) }
            val buttons = settings.toolbar.size
            nav(it, null, null, getString(R.string.row_toolbar),
                resources.getQuantityString(R.plurals.value_buttons, buttons, buttons)) { show(Page.TOOLBAR) }
        }
        header(column, getString(R.string.header_gestures))
        group(column) {
            toggle(it, getString(R.string.settings_cursor_swipe), settings.cursorSwipe) { on -> settings.copy(cursorSwipe = on) }
            toggle(it, getString(R.string.settings_delete_word), settings.deleteWordSwipe) { on -> settings.copy(deleteWordSwipe = on) }
            switchRow(
                it, getString(R.string.settings_edit_swipes), settings.editSwipes,
                subtitle = getString(R.string.settings_edit_swipes_note),
            ) { on -> change(settings.copy(editSwipes = on)) }
            toggle(it, getString(R.string.settings_shift_select), settings.shiftSelect) { on -> settings.copy(shiftSelect = on) }
            toggle(it, getString(R.string.settings_selection_tools), settings.selectionTools) { on -> settings.copy(selectionTools = on) }
            switchRow(
                it, getString(R.string.settings_two_finger), settings.twoFingerUndo,
                subtitle = getString(R.string.settings_two_finger_note),
            ) { on -> change(settings.copy(twoFingerUndo = on)) }
            toggle(it, getString(R.string.settings_swipe_hide), settings.swipeDownToHide) { on -> settings.copy(swipeDownToHide = on) }
        }
        // Delete forward has no switch, so this line is the only place Settings can mention it.
        footer(column, getString(R.string.footer_shift_backspace))
        header(column, getString(R.string.header_flicks))
        group(column) {
            toggle(it, getString(R.string.settings_flick_down), settings.flickForAlternate) { on -> settings.copy(flickForAlternate = on) }
            toggle(it, getString(R.string.settings_flick_up), settings.flickForCapital) { on -> settings.copy(flickForCapital = on) }
        }
        header(column, getString(R.string.header_timing))
        group(column) {
            segmented(it, getString(R.string.segment_hold_delay), listOf(
                getString(R.string.hold_shorter) to HoldDelay.SHORTER,
                getString(R.string.hold_follow_phone) to HoldDelay.FOLLOW_PHONE,
                getString(R.string.hold_longer) to HoldDelay.LONGER,
            ), settings.holdDelay) { value -> settings.copy(holdDelay = value) }
            segmented(it, getString(R.string.segment_backspace_speed), listOf(
                getString(R.string.speed_slower) to BackspaceSpeed.SLOWER,
                getString(R.string.speed_normal) to BackspaceSpeed.NORMAL,
                getString(R.string.speed_faster) to BackspaceSpeed.FASTER,
            ), settings.backspaceSpeed) { value -> settings.copy(backspaceSpeed = value) }
        }
        footer(column, getString(R.string.footer_timing))
        header(column, getString(R.string.header_tips))
        group(column) {
            switchRow(
                it, getString(R.string.settings_gesture_tips), settings.gestureTips,
                subtitle = getString(R.string.settings_gesture_tips_note),
            ) { on -> change(settings.copy(gestureTips = on)) }
            // Nothing is lost by it, so it does not ask first; the keyboard reads the tips again when a field opens.
            link(it, getString(R.string.row_tips_again)) {
                Tips.reset(prefs)
                toast(getString(R.string.toast_tips_again))
            }
        }
    }

    /** The period's symbols with room between them, as the row above the period shows them: ", ? ! '". */
    private fun spacedSymbols(symbols: String): String =
        Settings.symbolList(symbols).joinToString(" ").ifEmpty { getString(R.string.value_off) }

    // ---- The period's symbols ------------------------------------------------------------------------------------

    /**
     * What holding the period offers: a picture of the row, the field the symbols are typed into, and the way back
     * to the usual ones. Saved as they are typed, tidied - spaces and repeats out, eight at most - and the picture
     * shows the tidied row, so what it shows is what the keyboard will.
     */
    private fun period(column: LinearLayout) {
        val preview = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            minHeight = dp(48f)
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
            background = GradientDrawable().apply { setColor(Color.parseColor("#4B4B50")); cornerRadius = dp(12f).toFloat() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12f) }
        }
        fun showRow(symbols: String) {
            val row = Settings.symbolList(symbols)
            preview.text = row.joinToString("   ").ifEmpty { " " }
            preview.visibility = if (row.isEmpty()) View.INVISIBLE else View.VISIBLE
            preview.contentDescription = if (row.isEmpty()) getString(R.string.period_preview_none)
                else getString(R.string.period_preview, row.joinToString(" "))
        }
        showRow(settings.periodSymbols)
        column.addView(preview)
        group(column) {
            question(it, getString(R.string.period_field), settings.periodSymbols, multiLine = false) { typed ->
                val symbols = Settings.periodSymbols(typed)
                if (symbols != settings.periodSymbols) change(settings.copy(periodSymbols = symbols))
                showRow(symbols)
            }
        }
        footer(column, getString(R.string.period_note))
        group(column) {
            link(it, getString(R.string.period_reset)) {
                change(settings.copy(periodSymbols = Settings.DEFAULT_PERIOD_SYMBOLS))
                render(keepScroll = true)
            }
        }
    }

    // ---- The toolbar ---------------------------------------------------------------------------------------------

    /** The row to give TalkBack's focus back to after the page is rebuilt, by its tag. */
    private var focusAfterRender: String? = null

    private fun toolName(tool: ToolKey): String = getString(when (tool) {
        ToolKey.EMOJI -> R.string.tool_emoji
        ToolKey.UNDO -> R.string.tool_undo
        ToolKey.REDO -> R.string.tool_redo
        ToolKey.CURSOR_PAD -> R.string.tool_cursor_pad
        ToolKey.SELECT_ALL -> R.string.tool_select_all
        ToolKey.CUT -> R.string.tool_cut
        ToolKey.COPY -> R.string.tool_copy
        ToolKey.PASTE -> R.string.tool_paste
        ToolKey.CLIPBOARD -> R.string.tool_clipboard
        ToolKey.VOICE -> R.string.tool_voice
    })

    /** Saves a new arrangement and draws the page again, since rows move between the two lists. */
    private fun arrange(toolbar: List<ToolKey>, focus: String? = null) {
        prefs.edit().putBoolean(Settings.TOOLBAR_ARRANGED, true).apply()
        change(settings.copy(toolbar = toolbar.distinct().take(Settings.MAX_TOOLS)))
        focusAfterRender = focus
        render(keepScroll = true)
    }

    /**
     * The toolbar's buttons: what it looks like now, the ones on it in order, and the ones that could be.
     *
     * Rows move with an up and a down button rather than by dragging: a drag needs a steady finger and a long press
     * TalkBack can't make, and two buttons read "Move Copy up" to anyone, however they use the phone.
     */
    private fun toolbar(column: LinearLayout) {
        val chosen = settings.toolbar
        group(column) { card ->
            card.addView(ToolbarPreview(this, chosen).apply {
                contentDescription = getString(
                    R.string.toolbar_preview,
                    (listOf(getString(R.string.tool_hide)) + chosen.map(::toolName)).joinToString(", "),
                )
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56f)))
        }
        header(column, getString(R.string.header_on_toolbar))
        group(column) { card ->
            value(card, getString(R.string.tool_hide), getString(R.string.value_always_first))
            chosen.forEachIndexed { index, tool -> chosenRow(card, tool, index, chosen) }
        }
        footer(column, getString(R.string.footer_on_toolbar))
        val unused = ToolKey.entries.filter { it !in chosen }
        if (unused.isNotEmpty()) {
            header(column, getString(R.string.header_more_buttons))
            val room = chosen.size < Settings.MAX_TOOLS
            group(column) { card ->
                unused.forEach { tool ->
                    switchRow(card, toolName(tool), on = false, enabled = room) { on ->
                        if (on) arrange(chosen + tool)
                    }
                }
            }
        }
        footer(column, getString(R.string.footer_more_buttons))
        group(column) { card ->
            link(card, getString(R.string.toolbar_reset)) { arrange(Settings.DEFAULT_TOOLBAR) }
        }
        footer(column, getString(R.string.footer_voice))
    }

    /** One button on the toolbar: its name, a move up and a move down, and the switch that takes it off. */
    private fun chosenRow(card: LinearLayout, tool: ToolKey, index: Int, chosen: List<ToolKey>) {
        val name = toolName(tool)
        row(card, iconSpace = false).apply {
            addView(label(name))
            fun moved(by: Int) = chosen.toMutableList().apply { add(index + by, removeAt(index)) }
            val up = getString(R.string.toolbar_move_up, name)
            val down = getString(R.string.toolbar_move_down, name)
            addView(moveButton("↑", up, enabled = index > 0) { arrange(moved(-1), focus = up) })
            addView(moveButton("↓", down, enabled = index < chosen.lastIndex) { arrange(moved(1), focus = down) })
            val switch = Switch(context).apply {
                isChecked = true
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(colors.on, colors.off),
                )
                contentDescription = name
                setOnCheckedChangeListener { _, checked -> if (!checked) arrange(chosen - tool) }
            }
            addView(switch)
            setOnClickListener { switch.toggle() }
            background = selectable()
        }
    }

    /** A 48dp button with an arrow on it, named for what it does. Greyed at the end of the list it can't pass. */
    private fun moveButton(arrow: String, name: String, enabled: Boolean, run: () -> Unit) = TextView(this).apply {
        text = arrow
        tag = name
        setTextColor(if (enabled) colors.link else colors.divider)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        minWidth = dp(48f)
        minHeight = dp(48f)
        contentDescription = name
        isEnabled = enabled
        isClickable = enabled
        isFocusable = true
        if (enabled) {
            background = selectable()
            setOnClickListener { run() }
        }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        }
    }

    /** The toolbar as the keyboard draws it, Hide first, in the page's own colors. Voice is drawn whether or not it shows. */
    private inner class ToolbarPreview(context: Context, private val chosen: List<ToolKey>) : View(context) {
        private val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            color = colors.text
        }
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = colors.text }

        init {
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val kinds = listOf(KeyKind.HIDE) + chosen.map { it.kind }
            val pad = 12 * resources.displayMetrics.density
            val slot = (width - 2 * pad) / kinds.size
            val size = minOf(height * 0.46f, slot * 0.6f)
            stroke.strokeWidth = maxOf(1.5f * resources.displayMetrics.density, size * 0.072f)
            kinds.forEachIndexed { index, kind ->
                Icons.tool(canvas, kind, pad + slot * (index + 0.5f), height / 2f, size, stroke, fill)
            }
        }
    }

    private fun look(column: LinearLayout) {
        keyboardPreview(column)
        header(column, getString(R.string.header_key_style))
        group(column) {
            pick(it, listOf(
                getString(R.string.settings_key_style_folio) to KeyStyle.FOLIO,
                getString(R.string.settings_key_style_material) to KeyStyle.MATERIAL,
                getString(R.string.settings_key_style_samsung) to KeyStyle.SAMSUNG,
            ), settings.keyStyle) { value -> settings.copy(keyStyle = value) }
        }
        header(column, getString(R.string.header_theme))
        group(column) {
            segmented(it, getString(R.string.settings_appearance), listOf(
                getString(R.string.settings_appearance_system) to Appearance.SYSTEM,
                getString(R.string.settings_appearance_light) to Appearance.LIGHT,
                getString(R.string.settings_appearance_dark) to Appearance.DARK,
            ), settings.appearance) { value -> settings.copy(appearance = value) }
            toggle(it, getString(R.string.settings_pure_black), settings.pureBlack) { on -> settings.copy(pureBlack = on) }
            toggle(it, getString(R.string.settings_high_contrast), settings.highContrast) { on -> settings.copy(highContrast = on) }
        }
        header(column, getString(R.string.header_size_layout))
        group(column) {
            segmented(it, getString(R.string.settings_size), listOf(
                getString(R.string.settings_size_small) to Size.SMALL,
                getString(R.string.settings_size_medium) to Size.MEDIUM,
                getString(R.string.settings_size_large) to Size.LARGE,
            ), settings.size) { value -> settings.copy(size = value) }
            segmented(it, getString(R.string.settings_split), listOf(
                getString(R.string.settings_split_auto) to Split.AUTO,
                getString(R.string.settings_split_always) to Split.ALWAYS,
                getString(R.string.settings_split_never) to Split.NEVER,
            ), settings.split) { value -> settings.copy(split = value) }
            segmented(it, getString(R.string.settings_one_handed), listOf(
                getString(R.string.settings_one_handed_off) to OneHanded.OFF,
                getString(R.string.settings_one_handed_left) to OneHanded.LEFT,
                getString(R.string.settings_one_handed_right) to OneHanded.RIGHT,
            ), settings.oneHanded) { value -> settings.copy(oneHanded = value) }
        }
        footer(column, getString(R.string.footer_layout))
    }

    /** The keyboard itself, drawn with the settings on this page and redrawn as they change. Look, not touch. */
    private var preview: KeyboardView? = null

    private fun keyboardPreview(column: LinearLayout) {
        if (index != null) return
        val board = KeyboardView(this).apply {
            settings = this@SettingsActivity.settings
            rules = FieldRules()
            rows = Layouts.rows(Layer.LETTERS, false, rules, numberRow = this@SettingsActivity.settings.numberRow)
        }
        preview = board
        column.addView(object : android.widget.FrameLayout(this) {
            // A picture of the keys, so a tap on it types nothing and opens nothing.
            override fun onInterceptTouchEvent(ev: android.view.MotionEvent?) = true
            @android.annotation.SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: android.view.MotionEvent?) = true
        }.apply {
            val fill = colors.card
            background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(12f).toFloat() }
            clipToOutline = true
            contentDescription = getString(R.string.keyboard_preview)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(board, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8f) }
        })
    }

    private fun feel(column: LinearLayout) {
        header(column, getString(R.string.header_sound))
        group(column) {
            toggle(it, getString(R.string.settings_sound), settings.sound) { on -> settings.copy(sound = on) }
            switchRow(
                it, getString(R.string.settings_mute_bluetooth), settings.muteWithBluetooth,
                subtitle = getString(R.string.settings_mute_bluetooth_note),
            ) { on -> change(settings.copy(muteWithBluetooth = on)) }
        }
        header(column, getString(R.string.header_vibration))
        group(column) {
            segmented(it, getString(R.string.segment_strength), listOf(
                getString(R.string.vibration_off) to Vibration.OFF,
                getString(R.string.vibration_light) to Vibration.LIGHT,
                getString(R.string.vibration_medium) to Vibration.MEDIUM,
                getString(R.string.vibration_strong) to Vibration.STRONG,
            ), settings.vibration) { value -> settings.copy(vibration = value) }
        }
        header(column, getString(R.string.header_key_press))
        group(column) {
            toggle(it, getString(R.string.settings_preview), settings.keyPreview) { on -> settings.copy(keyPreview = on) }
        }
        footer(column, getString(R.string.settings_system_note))
    }

    private fun clipboard(column: LinearLayout) {
        group(column) {
            toggle(it, getString(R.string.settings_clipboard), settings.clipboardHistory) { on ->
                // Turning it off is also a request to forget: leaving the list on disk would be the opposite of
                // what the switch says.
                if (!on) Clipboard.clear(prefs)
                settings.copy(clipboardHistory = on)
            }
        }
        footer(column, getString(R.string.settings_clipboard_note))
    }

    private fun privacy(column: LinearLayout) {
        group(column) {
            value(it, getString(R.string.row_internet), getString(R.string.value_none))
            value(it, getString(R.string.row_permissions), getString(R.string.value_none))
        }
        footer(column, getString(R.string.footer_privacy))
        header(column, getString(R.string.header_what_it_knows))
        val words = Learned.decode(prefs.getString(LEARNED, null)).size
        val counted = Insights.decode(prefs.getString(INSIGHTS, null)).size
        group(column) {
            value(it, getString(R.string.row_learned), words.toString())
            action(it, getString(R.string.row_forget), enabled = words > 0 || counted > 0) { confirmForget(words) }
        }
        // The counts have their own Forget beside the lists they fill; Forget above takes them too, since the fixes
        // are words someone typed just as much as the learned ones are.
        group(column) {
            value(it, getString(R.string.row_fixes_counted), counted.toString())
        }
        header(column, getString(R.string.header_move))
        group(column) {
            link(it, getString(R.string.row_export)) { startExport() }
            link(it, getString(R.string.row_import)) { startImport() }
        }
        footer(column, getString(R.string.footer_move))
        header(column, getString(R.string.header_diagnostics))
        group(column) {
            switchRow(it, getString(R.string.settings_diagnostic_log), DevLog.loggingOn(this)) { on -> DevLog.setLogging(this, on) }
        }
        footer(column, getString(R.string.footer_diagnostics))
        group(column) { action(it, getString(R.string.settings_reset), enabled = true) { confirmReset() } }
    }

    // ---- Moving to a new phone -----------------------------------------------------------------------------------

    // Android's own file picker, both ways: the person chooses where the file goes and which one comes back, so Keyd
    // needs no storage permission and never sees any other file.
    private fun startExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, Backup.fileName(java.time.LocalDate.now()))
        runCatching { startActivityForResult(intent, REQUEST_EXPORT) }
    }

    private fun startImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            // Some file managers call a .json file plain text or just bytes; all three are offered.
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        runCatching { startActivityForResult(intent, REQUEST_IMPORT) }
    }

    @Deprecated("Activity's own result callback; Keyd takes no AndroidX activity library for the newer one.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_EXPORT -> exportTo(uri)
            REQUEST_IMPORT -> importFrom(uri)
        }
    }

    private fun exportTo(uri: android.net.Uri) {
        val text = Backup.export(
            Learned.decode(prefs.getString(LEARNED, null)), Shortcuts.decode(prefs.getString(SHORTCUTS, null)),
        )
        val saved = runCatching {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        toast(getString(if (saved) R.string.export_done else R.string.export_failed))
    }

    internal fun importFrom(uri: android.net.Uri) {
        val text = runCatching {
            contentResolver.openInputStream(uri)!!.use { stream ->
                // Far more than 1200 words and 200 shortcuts need; anything bigger isn't one of these files.
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    check(out.size() <= MAX_BACKUP_BYTES)
                }
                out.toString(Charsets.UTF_8.name())
            }
        }.getOrNull() ?: return toast(getString(R.string.import_failed))
        val result = Backup.merge(
            text, Learned.decode(prefs.getString(LEARNED, null)), Shortcuts.decode(prefs.getString(SHORTCUTS, null)),
        )
        when (result) {
            is Backup.Result.Rejected -> toast(getString(when (result.reason) {
                Backup.Reason.NOT_A_BACKUP -> R.string.import_not_backup
                Backup.Reason.NEWER -> R.string.import_newer
            }))
            is Backup.Result.Added -> {
                // The keyboard reads both again when the next field opens, so there is nothing to tell it.
                prefs.edit()
                    .putString(LEARNED, result.learned.encode())
                    .putString(SHORTCUTS, result.shortcuts.encode())
                    .apply()
                toast(getString(
                    R.string.import_added,
                    resources.getQuantityString(R.plurals.import_words, result.words, result.words),
                    resources.getQuantityString(R.plurals.import_shortcuts, result.shortcutsAdded, result.shortcutsAdded),
                ))
                render(keepScroll = true)
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // ---- Languages -----------------------------------------------------------------------------------------------

    private fun ourMethod() = getSystemService(InputMethodManager::class.java)?.inputMethodList
        ?.firstOrNull { it.component == ComponentName(this, KeysService::class.java) }

    /** The languages turned on for Keyd in Android's settings, by their own names: "English, Français". */
    private fun languages(): String? {
        val manager = getSystemService(InputMethodManager::class.java) ?: return null
        val method = ourMethod() ?: return null
        val tags = manager.getEnabledInputMethodSubtypeList(method, true).map { it.languageTag.ifEmpty { it.locale } }
        return tags.map { Language.of(it).ownName }.distinct().joinToString(", ").ifEmpty { null }
    }

    /**
     * The languages turned on for Keyd, the one being typed in marked, then the rest Keyd has. Which are on is
     * Android's to keep, so the page lists them and sends anyone who wants a change to Android's own list.
     */
    private fun languagePage(column: LinearLayout) {
        footer(column, getString(R.string.languages_note))
        val on = turnedOn()
        val typing = typingIn()
        if (on.isNotEmpty()) {
            header(column, getString(R.string.header_turned_on))
            group(column) { card ->
                for (language in on) {
                    if (language == typing) value(card, language.ownName, getString(R.string.value_typing_now))
                    else plain(card, language.ownName)
                }
            }
        }
        val rest = Language.entries.filter { it !in on }
        if (rest.isNotEmpty()) {
            header(column, getString(R.string.header_also_in_keyd))
            group(column) { card -> rest.forEach { plain(card, it.ownName) } }
        }
        group(column) { link(it, getString(R.string.languages_turn_on)) { openLanguages() } }
        footer(column, getString(R.string.languages_turn_on_note))
    }

    /** Keyd's languages turned on in Android, in Android's order, each once. */
    private fun turnedOn(): List<Language> {
        val manager = getSystemService(InputMethodManager::class.java) ?: return emptyList()
        val method = ourMethod() ?: return emptyList()
        return runCatching {
            manager.getEnabledInputMethodSubtypeList(method, true).map { Language.of(it.languageTag.ifEmpty { it.locale }) }
        }.getOrDefault(emptyList()).distinct()
    }

    /** The language Keyd is typing in, or null while another keyboard is the one in use. */
    private fun typingIn(): Language? {
        val method = ourMethod() ?: return null
        val current = runCatching {
            AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull()
        if (current != method.id) return null
        val subtype = runCatching { getSystemService(InputMethodManager::class.java)?.currentInputMethodSubtype }.getOrNull()
            ?: return null
        return Language.of(subtype.languageTag.ifEmpty { subtype.locale })
    }

    /** Which languages are on is Android's to keep, per keyboard; this opens that list for Keyd directly. */
    private fun openLanguages() {
        val method = ourMethod()
        val intent = Intent(AndroidSettings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS)
            .apply { method?.let { putExtra(AndroidSettings.EXTRA_INPUT_METHOD_ID, it.id) } }
        runCatching { startActivity(intent) }
            .onFailure { runCatching { startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS)) } }
    }

    // ---- Things that can't be undone ask first -------------------------------------------------------------------

    private fun confirmForget(words: Int) {
        AlertDialog.Builder(this)
            .setTitle(
                if (words > 0) resources.getQuantityString(R.plurals.confirm_forget, words, words)
                else getString(R.string.confirm_forget_counts),
            )
            .setMessage(R.string.confirm_forget_detail)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_forget) { _, _ ->
                prefs.edit().remove(LEARNED).remove(INSIGHTS).remove(SEEN).apply()
                render(keepScroll = true)
            }
            .show()
    }

    private fun confirmForgetCounts() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_forget_counts)
            .setMessage(R.string.confirm_forget_counts_detail)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_forget) { _, _ ->
                // The keyboard reads the counts again when the next field opens, so there is nothing to tell it.
                prefs.edit().remove(INSIGHTS).apply()
                render(keepScroll = true)
            }
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_reset)
            .setMessage(R.string.confirm_reset_detail)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_reset) { _, _ ->
                Settings.reset(prefs)
                settings = Settings.load(prefs)
                render(keepScroll = true)
            }
            .show()
    }

    // ---- The grouped list ----------------------------------------------------------------------------------------

    private fun header(column: LinearLayout, text: String) {
        if (index != null) indexHeader = text
        column.addView(TextView(this).apply {
            this.text = text
            setTextColor(colors.secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(16f), dp(18f), dp(16f), dp(6f))
            isAccessibilityHeading = true
        })
    }

    private fun footer(column: LinearLayout, text: String) {
        // For search, a note belongs to the rows of the group it sits under.
        index?.let { list ->
            for (i in indexGroupStart until list.size) {
                if (list[i].footer == null) list[i] = list[i].copy(footer = text)
            }
        }
        footerView(column, text)
    }

    private fun footerView(column: LinearLayout, text: String) = column.addView(TextView(this).apply {
        this.text = text
        setTextColor(colors.secondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setLineSpacing(0f, 1.15f)
        setPadding(dp(16f), dp(6f), dp(16f), dp(4f))
    })

    /** The one filled button a page may have, for the step that moves it on. */
    private fun primary(column: LinearLayout, text: String, run: () -> Unit) = column.addView(TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = dp(52f)
        val fill = colors.link
        background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(14f).toFloat() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(20f) }
        isClickable = true
        isFocusable = true
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        }
        setOnClickListener { run() }
    })

    /** One rounded card of rows, with hairlines between them that stop short of the leading edge, as iOS draws them. */
    private fun group(column: LinearLayout, rows: (LinearLayout) -> Unit) {
        // Read before the apply: inside GradientDrawable.apply, `colors` is the drawable's own gradient.
        val fill = colors.card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(12f).toFloat() }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12f) }
        }
        if (index != null) indexGroupStart = index!!.size
        rows(card)
        column.addView(card)
        // A group with no header of its own is not under the one before it.
        if (index != null) indexHeader = null
    }

    private fun row(card: LinearLayout, iconSpace: Boolean): LinearLayout {
        divider(card, iconSpace)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52f)
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            card.addView(this)
        }
    }

    private fun divider(card: LinearLayout, iconSpace: Boolean) {
        if (card.childCount > 0) {
            card.addView(View(this).apply {
                setBackgroundColor(colors.divider)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    .apply { marginStart = dp(if (iconSpace) 58f else 16f) }
            })
        }
    }

    private fun label(text: String, color: Int = colors.text) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        // A long title wraps rather than being cut short: at 200% text a sentence-length label needs two lines.
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun trailing(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(colors.secondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        // Never more than about half the row, so the title always has room to say what the row is.
        maxWidth = (resources.displayMetrics.widthPixels * .45f).toInt()
        setPadding(dp(8f), 0, dp(4f), 0)
    }

    private fun chevron() = TextView(this).apply {
        text = "›"
        setTextColor(colors.divider)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * A row that leads somewhere, with its colored icon (none for an app row, which draws its own) and, where there is
     * one, what it is set to now and a line under the title saying what it holds.
     */
    private fun nav(
        card: LinearLayout, glyph: SettingsIcon.Glyph?, tile: String?, title: String, value: String?,
        subtitle: String? = null, open: () -> Unit,
    ) {
        if (indexed(title, subtitle)) return
        row(card, iconSpace = glyph != null).apply {
            tag = rowTag(title)
            if (glyph != null && tile != null) {
                addView(SettingsIcon(context, glyph, Color.parseColor(tile)), LinearLayout.LayoutParams(dp(29f), dp(29f))
                    .apply { marginEnd = dp(13f) })
            }
            if (subtitle == null) addView(label(title)) else addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                addView(TextView(context).apply {
                    text = subtitle
                    setTextColor(colors.secondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            value?.let { addView(trailing(it)) }
            addView(chevron())
            isClickable = true
            background = selectable()
            contentDescription = listOfNotNull(title, value, subtitle).joinToString(", ")
            setOnClickListener { open() }
        }
    }

    private fun toggle(card: LinearLayout, title: String, on: Boolean, enabled: Boolean = true, update: (Boolean) -> Settings) {
        if (indexed(title)) return
        row(card, iconSpace = false).apply {
            tag = rowTag(title)
            addView(label(title, if (enabled) colors.text else colors.secondary))
            val switch = Switch(context).apply {
                isChecked = on
                isEnabled = enabled
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(colors.on, colors.off),
                )
                // The row says what it is; the switch is only the state, so TalkBack reads one thing, not two.
                contentDescription = title
                setOnCheckedChangeListener { _, checked -> change(update(checked)) }
            }
            addView(switch)
            // The whole row is the target, not just the 40-pixel switch at its end. It asks the switch itself, so a
            // row that is greyed out at the moment does nothing, and one that comes back to life works again.
            setOnClickListener { if (switch.isEnabled) switch.toggle() }
            background = selectable()
            isEnabled = enabled
            isClickable = enabled
            if (title == getString(R.string.settings_autocorrect)) {
                autocorrect = switch
                autocorrectLabel = getChildAt(0) as? TextView
                autocorrectRow = this
            }
        }
    }

    /**
     * A switch for something outside [Settings], like logging: same look, its own storage. A [subtitle] sits under the
     * title in grey, for what the switch covers; a row that can't be used right now is greyed like autocorrect's.
     */
    private fun switchRow(
        card: LinearLayout, title: String, on: Boolean, subtitle: String? = null, enabled: Boolean = true,
        changed: (Boolean) -> Unit,
    ) {
        if (indexed(title, subtitle)) return
        row(card, iconSpace = false).apply {
            tag = rowTag(title)
            val name = label(title, if (enabled) colors.text else colors.secondary)
            if (subtitle == null) addView(name) else addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(name.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
                addView(TextView(context).apply {
                    text = subtitle
                    setTextColor(colors.secondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val switch = Switch(context).apply {
                isChecked = on
                isEnabled = enabled
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(colors.on, colors.off),
                )
                contentDescription = listOfNotNull(title, subtitle).joinToString(", ")
                setOnCheckedChangeListener { _, checked -> changed(checked) }
            }
            addView(switch)
            setOnClickListener { if (switch.isEnabled) switch.toggle() }
            background = selectable()
            isEnabled = enabled
            isClickable = enabled
            if (title == getString(R.string.settings_suggest_emoji)) emojiSwitch = switch to name
        }
    }

    /** A row that does something ordinary, in the link colour: red is kept for rows that throw something away. */
    private fun link(card: LinearLayout, title: String, run: () -> Unit) {
        if (indexed(title)) return
        row(card, iconSpace = false).apply {
            tag = rowTag(title)
            addView(label(title, colors.link))
            isClickable = true
            background = selectable()
            setOnClickListener { run() }
        }
    }

    /** One choice out of a few, as a list with a tick - clearer than a row of buttons that only dim when unchosen. */
    private fun <T> pick(card: LinearLayout, options: List<Pair<String, T>>, current: T, update: (T) -> Settings) {
        val ticks = mutableListOf<Pair<T, Pair<View, TextView>>>()
        var chosen = current
        fun show() = ticks.forEach { (value, parts) ->
            val (row, tick) = parts
            tick.visibility = if (value == chosen) View.VISIBLE else View.INVISIBLE
            row.isSelected = value == chosen
        }
        if (index != null) return options.forEach { indexed(it.first) }
        for ((text, value) in options) {
            row(card, iconSpace = false).apply {
                tag = rowTag(text)
                addView(label(text))
                val tick = TextView(context).apply {
                    this.text = "✓"
                    setTextColor(colors.link)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                addView(tick)
                ticks += value to (this to tick)
                isClickable = true
                background = selectable()
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.isCheckable = true
                        info.isChecked = value == chosen
                    }
                }
                setOnClickListener {
                    if (value == chosen) return@setOnClickListener
                    chosen = value
                    change(update(value))
                    show()
                    // The tick moved without the page being rebuilt; say so, as a tapped radio button does.
                    sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
            }
        }
        show()
    }

    /**
     * One choice out of a few fixed ones, all in one row: [Segmented], under its label. Saved at once, like [pick],
     * and nothing else on the page moves.
     */
    private fun <T> segmented(card: LinearLayout, title: String, options: List<Pair<String, T>>, current: T, update: (T) -> Settings) {
        if (indexed(title, options.joinToString(", ") { it.first })) return
        divider(card, iconSpace = false)
        card.addView(LinearLayout(this).apply {
            tag = rowTag(title)
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(10f), dp(16f), dp(12f))
            addView(TextView(context).apply {
                text = title
                setTextColor(colors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, 0, 0, dp(8f))
                // The group below carries the name, so TalkBack reads it once, with the options.
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(Segmented(
                context, title, options.map { it.first }, options.indexOfFirst { it.second == current }.coerceAtLeast(0),
                Segmented.Colors(colors.track, colors.raised, colors.text),
            ) { chosen -> change(update(options[chosen].second)) })
        })
    }

    /** A row that only says something: read, not pressed. */
    private fun plain(card: LinearLayout, title: String) {
        if (indexed(title)) return
        row(card, iconSpace = false).apply {
            tag = rowTag(title)
            addView(label(title))
            isFocusable = true
        }
    }

    private fun value(card: LinearLayout, title: String, value: String) {
        if (indexed(title)) return
        row(card, iconSpace = false).apply {
            tag = rowTag(title)
            addView(label(title))
            addView(trailing(value))
            isFocusable = true
            contentDescription = "$title, $value"
        }
    }

    private fun action(card: LinearLayout, title: String, enabled: Boolean, run: () -> Unit) {
        if (indexed(title)) return
        row(card, iconSpace = false).apply {
            tag = rowTag(title)
            addView(label(title, if (enabled) colors.destructive else colors.secondary))
            isEnabled = enabled
            isClickable = enabled
            if (enabled) {
                background = selectable()
                setOnClickListener { run() }
            }
        }
    }

    private fun selectable() = TypedValue().let { value ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        getDrawable(value.resourceId)
    }

    private companion object {
        const val LEARNED = "learnedWords"
        const val SEEN = "seenWords"
        const val SHORTCUTS = "shortcuts"
        const val INSIGHTS = "typingInsights"
        const val PAGE = "page"
        const val QUERY = "query"

        /** The pages search reads. Not the ones that are about one app, one release or one report. */
        val SEARCHED = listOf(
            Page.MAIN, Page.TYPING, Page.WHAT_IT_FIXES, Page.KEYS, Page.TOOLBAR, Page.PERIOD, Page.LANGUAGES,
            Page.LOOK, Page.FEEL, Page.CLIPBOARD, Page.PRIVACY,
        )
        const val APP = "app"

        /** Marks a switch being set to show a state, so its listener knows nobody tapped it. */
        val SHOWING = Any()
        const val ANSWER_APP = "answerApp"
        const val ANSWER_DID = "answerDid"
        const val ANSWER_SAW = "answerSaw"
        const val INCLUDE = "include"
        const val REQUEST_EXPORT = 20
        const val REQUEST_IMPORT = 21
        const val MAX_BACKUP_BYTES = 1 shl 20

        /** Each list on What it fixes shows this many, most counted first. */
        const val SHOWN_PAIRS = 10

        /** What's New shows this many new features in full; the rest are one row away. */
        const val SHOWN_FEATURES = 5
    }
}

/** Which page Settings opens on, when something outside it asks for one. Only Languages is honoured. */
private const val EXTRA_PAGE = "com.mccal.folio.keys.PAGE"

/** Keyd's Settings, open on its Languages page, from outside the app: the globe's list is in the keyboard's window. */
internal fun languageSettings(context: Context): Intent =
    Intent(context, SettingsActivity::class.java)
        .putExtra(EXTRA_PAGE, "LANGUAGES")
        // A task of its own, as Keyd's settings are when opened from Android's list, started over on this page.
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
