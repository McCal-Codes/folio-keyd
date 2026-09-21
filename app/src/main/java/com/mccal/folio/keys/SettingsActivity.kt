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
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
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
        KEYS(R.string.page_keys, MAIN),
        LOOK(R.string.page_look, MAIN),
        FEEL(R.string.page_feel, MAIN),
        CLIPBOARD(R.string.page_clipboard, MAIN),
        PRIVACY(R.string.page_privacy, MAIN),
        DEVELOPER(R.string.page_developer, MAIN),
    }

    private lateinit var settings: Settings
    private val prefs by lazy { getSharedPreferences("keys", Context.MODE_PRIVATE) }
    private var page = Page.MAIN
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
    }

    private lateinit var colors: Palette

    // Android 16 no longer calls onBackPressed for an app that targets it; the system's back gesture goes through
    // this callback instead, and only while there is a page to step back to.
    private val back: Any? by lazy {
        if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { page.parent?.let { show(it) } } else null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DevLog.catchCrashes(this)
        settings = Settings.load(prefs)
        page = savedInstanceState?.getString(PAGE)?.let { runCatching { Page.valueOf(it) }.getOrNull() } ?: Page.MAIN
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

    private fun refreshDependents() {
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
        val y = if (keepScroll) scroll?.scrollY ?: 0 else 0
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(32f))
        }
        page.parent?.let { parent ->
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
        column.addView(TextView(this).apply {
            text = getString(page.title)
            setTextColor(colors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            isAccessibilityHeading = true
            setPadding(0, dp(if (page.parent == null) 24f else 4f), 0, dp(10f))
        })
        when (page) {
            Page.MAIN -> main(column)
            Page.TYPING -> typing(column)
            Page.KEYS -> keys(column)
            Page.LOOK -> look(column)
            Page.FEEL -> feel(column)
            Page.CLIPBOARD -> clipboard(column)
            Page.PRIVACY -> privacy(column)
            Page.DEVELOPER -> developer(column)
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column)
            fitsSystemWindows = true
        }
        setContentView(scroll)
        scroll?.post { scroll?.scrollTo(0, y) }
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = back as OnBackInvokedCallback
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
            if (page.parent != null) {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            }
        }
    }

    // ---- Pages ---------------------------------------------------------------------------------------------------

    private fun main(column: LinearLayout) {
        status(column)
        group(column) {
            nav(it, SettingsIcon.Glyph.LANGUAGES, "#0071E3", getString(R.string.row_languages), languages()) {
                openLanguages()
            }
            val count = Shortcuts.decode(prefs.getString(SHORTCUTS, null)).size
            nav(
                it, SettingsIcon.Glyph.SHORTCUTS, "#5E5CE6", getString(R.string.row_shortcuts),
                resources.getQuantityString(R.plurals.value_shortcuts, count, count),
            ) { startActivity(Intent(this, ShortcutsActivity::class.java)) }
        }
        group(column) {
            toggle(it, getString(R.string.settings_suggestions), settings.suggestions) { on -> settings.copy(suggestions = on) }
            // Autocorrect is the strip's top answer applied for you, so with the strip off it is off too, whatever it
            // is set to. The switch shows that - off, and greyed - and keeps the choice for when the strip is back.
            toggle(
                it, getString(R.string.settings_autocorrect), Settings.correcting(settings), enabled = settings.suggestions,
            ) { on -> settings.copy(autocorrect = on) }
        }
        footer(column, getString(R.string.footer_corrections))
        group(column) {
            nav(it, SettingsIcon.Glyph.TYPING, "#248A3D", getString(R.string.page_typing), null) { show(Page.TYPING) }
            nav(it, SettingsIcon.Glyph.KEYS, "#C93400", getString(R.string.page_keys), null) { show(Page.KEYS) }
            nav(it, SettingsIcon.Glyph.LOOK, "#8944AB", getString(R.string.page_look), null) { show(Page.LOOK) }
            nav(it, SettingsIcon.Glyph.SOUND, "#D70015", getString(R.string.page_feel), null) { show(Page.FEEL) }
            nav(
                it, SettingsIcon.Glyph.CLIPBOARD, "#636366", getString(R.string.page_clipboard),
                getString(if (settings.clipboardHistory) R.string.value_on else R.string.value_off),
            ) { show(Page.CLIPBOARD) }
        }
        group(column) {
            nav(it, SettingsIcon.Glyph.PRIVACY, "#1B7A33", getString(R.string.page_privacy), getString(R.string.value_no_internet)) {
                show(Page.PRIVACY)
            }
        }
        footer(column, getString(R.string.footer_main))
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
            value(it, getString(R.string.row_version), versionName())
            link(it, getString(R.string.row_whats_new)) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.whats_new_title, versionName()))
                    .setMessage(R.string.whats_new_text)
                    .setPositiveButton(R.string.action_done, null)
                    .show()
            }
            link(it, getString(R.string.row_report)) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ISSUES_URL))) }
            }
        }
        footer(column, getString(R.string.footer_about))
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
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                addView(android.widget.ImageView(context).apply {
                    setImageDrawable(applicationInfo.loadIcon(packageManager))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(60f), dp(60f)).apply { marginEnd = dp(14f) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = applicationInfo.loadLabel(packageManager)
                        setTextColor(colors.text)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    addView(TextView(context).apply {
                        text = getString(R.string.status_version, versionName())
                        setTextColor(colors.secondary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    })
                    addView(TextView(context).apply {
                        text = getString(when (state) {
                            SetupState.IN_USE -> R.string.status_in_use
                            SetupState.ADDED -> R.string.status_added
                            SetupState.NOT_ADDED -> R.string.status_not_added
                        })
                        setTextColor(if (state == SetupState.IN_USE) colors.on else colors.link)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                isFocusable = true
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

    /** Keyd Dev only: switch detailed logging on, see recent errors, and share or clear them. */
    private fun developer(column: LinearLayout) {
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
        group(column) { toggle(it, getString(R.string.settings_spell_check), settings.spellCheck) { on -> settings.copy(spellCheck = on) } }
        footer(column, getString(R.string.settings_spell_check_note))
        group(column) { toggle(it, getString(R.string.settings_learn), settings.learn) { on -> settings.copy(learn = on) } }
        footer(column, getString(R.string.settings_learn_note))
        group(column) {
            toggle(it, getString(R.string.settings_capitals), settings.autoCapitalise) { on -> settings.copy(autoCapitalise = on) }
            toggle(it, getString(R.string.settings_double_space), settings.doubleSpaceFullStop) { on -> settings.copy(doubleSpaceFullStop = on) }
        }
        footer(column, "${getString(R.string.settings_capitals_note)} ${getString(R.string.settings_double_space_note)}")
    }

    private fun keys(column: LinearLayout) {
        header(column, getString(R.string.header_keys))
        group(column) {
            toggle(it, getString(R.string.settings_number_row), settings.numberRow) { on -> settings.copy(numberRow = on) }
            toggle(it, getString(R.string.settings_accents), settings.accents) { on -> settings.copy(accents = on) }
            toggle(it, getString(R.string.settings_preview), settings.keyPreview) { on -> settings.copy(keyPreview = on) }
        }
        footer(column, "${getString(R.string.settings_number_row_note)} ${getString(R.string.settings_accents_note)}")
        header(column, getString(R.string.header_flicks))
        group(column) {
            toggle(it, getString(R.string.settings_flick_down), settings.flickForAlternate) { on -> settings.copy(flickForAlternate = on) }
            toggle(it, getString(R.string.settings_flick_up), settings.flickForCapital) { on -> settings.copy(flickForCapital = on) }
        }
        footer(column, "${getString(R.string.settings_flick_down_note)} ${getString(R.string.settings_flick_up_note)}")
        header(column, getString(R.string.header_space))
        group(column) {
            toggle(it, getString(R.string.settings_cursor_swipe), settings.cursorSwipe) { on -> settings.copy(cursorSwipe = on) }
            toggle(it, getString(R.string.settings_swipe_hide), settings.swipeDownToHide) { on -> settings.copy(swipeDownToHide = on) }
            toggle(it, getString(R.string.settings_delete_word), settings.deleteWordSwipe) { on -> settings.copy(deleteWordSwipe = on) }
        }
        footer(column, getString(R.string.settings_gestures_note))
    }

    private fun look(column: LinearLayout) {
        header(column, getString(R.string.settings_appearance).uppercase())
        group(column) {
            pick(it, listOf(
                getString(R.string.settings_appearance_system) to Appearance.SYSTEM,
                getString(R.string.settings_appearance_light) to Appearance.LIGHT,
                getString(R.string.settings_appearance_dark) to Appearance.DARK,
            ), settings.appearance) { value -> settings.copy(appearance = value) }
        }
        footer(column, getString(R.string.settings_appearance_note))
        group(column) { toggle(it, getString(R.string.settings_high_contrast), settings.highContrast) { on -> settings.copy(highContrast = on) } }
        footer(column, getString(R.string.settings_high_contrast_note))
        header(column, getString(R.string.settings_size).uppercase())
        group(column) {
            pick(it, listOf(
                getString(R.string.settings_size_small) to Size.SMALL,
                getString(R.string.settings_size_medium) to Size.MEDIUM,
                getString(R.string.settings_size_large) to Size.LARGE,
            ), settings.size) { value -> settings.copy(size = value) }
        }
        footer(column, getString(R.string.settings_size_note))
        header(column, getString(R.string.settings_split).uppercase())
        group(column) {
            pick(it, listOf(
                getString(R.string.settings_split_auto) to Split.AUTO,
                getString(R.string.settings_split_always) to Split.ALWAYS,
                getString(R.string.settings_split_never) to Split.NEVER,
            ), settings.split) { value -> settings.copy(split = value) }
        }
        footer(column, getString(R.string.settings_split_note))
    }

    private fun feel(column: LinearLayout) {
        group(column) {
            toggle(it, getString(R.string.settings_sound), settings.sound) { on -> settings.copy(sound = on) }
            toggle(it, getString(R.string.settings_vibrate), settings.vibrate) { on -> settings.copy(vibrate = on) }
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
        group(column) {
            value(it, getString(R.string.row_learned), words.toString())
            action(it, getString(R.string.row_forget), enabled = words > 0) { confirmForget(words) }
        }
        group(column) { action(it, getString(R.string.settings_reset), enabled = true) { confirmReset() } }
    }

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
            .setTitle(resources.getQuantityString(R.plurals.confirm_forget, words, words))
            .setMessage(R.string.confirm_forget_detail)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_forget) { _, _ ->
                prefs.edit().remove(LEARNED).apply()
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

    private fun header(column: LinearLayout, text: String) = column.addView(TextView(this).apply {
        this.text = text
        setTextColor(colors.secondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(16f), dp(18f), dp(16f), dp(6f))
    })

    private fun footer(column: LinearLayout, text: String) = column.addView(TextView(this).apply {
        this.text = text
        setTextColor(colors.secondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setLineSpacing(0f, 1.15f)
        setPadding(dp(16f), dp(6f), dp(16f), dp(4f))
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
        rows(card)
        column.addView(card)
    }

    private fun row(card: LinearLayout, iconSpace: Boolean): LinearLayout {
        if (card.childCount > 0) {
            card.addView(View(this).apply {
                setBackgroundColor(colors.divider)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    .apply { marginStart = dp(if (iconSpace) 58f else 16f) }
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52f)
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            card.addView(this)
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

    /** A row that leads somewhere, with its coloured icon and, where there is one, what it is set to now. */
    private fun nav(card: LinearLayout, glyph: SettingsIcon.Glyph, tile: String, title: String, value: String?, open: () -> Unit) {
        row(card, iconSpace = true).apply {
            addView(SettingsIcon(context, glyph, Color.parseColor(tile)), LinearLayout.LayoutParams(dp(29f), dp(29f))
                .apply { marginEnd = dp(13f) })
            addView(label(title))
            value?.let { addView(trailing(it)) }
            addView(chevron())
            isClickable = true
            background = selectable()
            contentDescription = listOfNotNull(title, value).joinToString(", ")
            setOnClickListener { open() }
        }
    }

    private fun toggle(card: LinearLayout, title: String, on: Boolean, enabled: Boolean = true, update: (Boolean) -> Settings) {
        row(card, iconSpace = false).apply {
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

    /** A switch for something outside [Settings], like logging: same look, its own storage. */
    private fun switchRow(card: LinearLayout, title: String, on: Boolean, changed: (Boolean) -> Unit) {
        row(card, iconSpace = false).apply {
            addView(label(title))
            val switch = Switch(context).apply {
                isChecked = on
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(colors.on, colors.off),
                )
                contentDescription = title
                setOnCheckedChangeListener { _, checked -> changed(checked) }
            }
            addView(switch)
            setOnClickListener { switch.toggle() }
            background = selectable()
        }
    }

    /** A row that does something ordinary, in the link colour: red is kept for rows that throw something away. */
    private fun link(card: LinearLayout, title: String, run: () -> Unit) {
        row(card, iconSpace = false).apply {
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
        for ((text, value) in options) {
            row(card, iconSpace = false).apply {
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

    private fun value(card: LinearLayout, title: String, value: String) {
        row(card, iconSpace = false).apply {
            addView(label(title))
            addView(trailing(value))
            isFocusable = true
            contentDescription = "$title, $value"
        }
    }

    private fun action(card: LinearLayout, title: String, enabled: Boolean, run: () -> Unit) {
        row(card, iconSpace = false).apply {
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
        const val SHORTCUTS = "shortcuts"
        const val PAGE = "page"
        const val ISSUES_URL = "https://github.com/McCal-Codes/folio-keyd/issues"
    }
}
