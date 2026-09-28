package com.mccal.folio.keys

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The grouped settings screen, driven the way someone would drive it: open a page, flip a switch, go back.
 *
 * What it checks is behaviour rather than looks - that every one of Keyd's settings can still be reached, that a
 * change is saved and shown as saved, and that nothing that can't be undone happens without asking first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsScreenTest {

    private fun open(crashed: Boolean = false): SettingsActivity {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("keys", Context.MODE_PRIVATE).edit().clear()
            .putBoolean(DevLog.CRASHED_KEY, crashed).commit()
        DevLog.clear(context)
        return Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
    }

    private fun SettingsActivity.all(): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) { out += v; if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(window.decorView)
        return out
    }

    private fun SettingsActivity.text(label: String): TextView? =
        all().filterIsInstance<TextView>().firstOrNull { it.text.toString() == label }

    /** The row a label sits in: what a finger actually taps. */
    private fun SettingsActivity.tap(label: String) {
        var target: View = text(label) ?: throw AssertionError("no \"$label\" on screen")
        while (!target.isClickable && target.parent is View) target = target.parent as View
        target.performClick()
    }

    private fun SettingsActivity.switchIn(label: String): Switch {
        val row = text(label)!!.parent as ViewGroup
        return (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<Switch>().single()
    }

    private fun SettingsActivity.stored() = Settings.load(getSharedPreferences("keys", Context.MODE_PRIVATE))

    @Test
    fun `the first page is short and leads to the rest`() {
        val a = open()
        for (label in listOf("Languages", "Text shortcuts", "Suggestions", "Fix clear typos", "Typing",
            "Keys and gestures", "Look and size", "Sound and vibration", "Clipboard", "Privacy")) {
            assertNotNull("missing $label", a.text(label))
        }
        // The detail lives a level down now, not in a paragraph under every switch.
        assertNull(a.text("Number row"))
        assertNull(a.text("Flick a letter up for a capital"))
    }

    @Test
    fun `every setting Keyd has is on some page`() {
        val a = open()
        val found = mutableSetOf<String>()
        for (page in listOf("Typing", "Keys and gestures", "Look and size", "Sound and vibration", "Clipboard")) {
            a.tap(page)
            found += a.all().filterIsInstance<TextView>().map { it.text.toString() }
            a.tap("‹ Keyd")
        }
        found += a.all().filterIsInstance<TextView>().map { it.text.toString() }
        val every = listOf(
            R.string.settings_suggestions, R.string.settings_autocorrect, R.string.settings_spell_check,
            R.string.settings_learn, R.string.settings_capitals, R.string.settings_double_space,
            R.string.settings_number_row, R.string.settings_accents, R.string.settings_preview,
            R.string.settings_flick_down, R.string.settings_flick_up, R.string.settings_cursor_swipe,
            R.string.settings_delete_word, R.string.settings_swipe_hide, R.string.settings_sound,
            R.string.settings_high_contrast, R.string.settings_clipboard,
            R.string.settings_size_small, R.string.settings_appearance_dark, R.string.settings_split_never,
            R.string.settings_edit_swipes, R.string.settings_shift_select, R.string.settings_selection_tools,
            R.string.row_toolbar, R.string.settings_key_style_samsung,
            R.string.settings_mute_bluetooth, R.string.vibration_strong, R.string.settings_pure_black,
            R.string.row_per_app,
        ).map { a.getString(it) }
        assertEquals(emptyList<String>(), every.filterNot { it in found })
    }

    @Test
    fun `a switch saves, and the whole row is the target`() {
        val a = open()
        a.tap("Keys and gestures")
        assertFalse(a.switchIn("Number row").isChecked)
        a.tap("Number row")
        assertTrue(a.stored().numberRow)
        assertTrue(a.switchIn("Number row").isChecked)
    }

    @Test
    fun `a change leaves the page where it is, so TalkBack stays on the row`() {
        val a = open()
        a.tap("Keys and gestures")
        val before = a.text("Swipe left on backspace for a whole word")
        a.tap("Swipe left on backspace for a whole word")
        a.tap(a.getString(R.string.settings_flick_up))
        // The same views, not a rebuilt page: rebuilding sent TalkBack's focus back to the top after every switch.
        assertTrue(before === a.text("Swipe left on backspace for a whole word"))
        assertFalse(a.stored().deleteWordSwipe)
    }

    @Test
    fun `turning suggestions off shows autocorrect going off with it`() {
        val a = open()
        a.tap("Suggestions")
        assertFalse(a.stored().suggestions)
        // Autocorrect is off in effect, and the switch says so - off and greyed - rather than showing on while
        // nothing is being corrected. The choice itself is kept for when the strip comes back.
        val autocorrect = a.switchIn("Fix clear typos")
        assertFalse(autocorrect.isChecked)
        assertFalse(autocorrect.isEnabled)
        assertTrue(a.stored().autocorrect)
        a.tap("Suggestions")
        assertTrue(a.switchIn("Fix clear typos").isChecked)
    }

    @Test
    fun `a choice is a tick, and picking one saves it`() {
        val a = open()
        a.tap("Look and size")
        a.tap(a.getString(R.string.settings_split_never))
        assertEquals(Split.NEVER, a.stored().split)
    }

    @Test
    fun `back steps out a page at a time`() {
        val a = open()
        a.tap("Privacy")
        assertNotNull(a.text("Internet access"))
        a.tap("‹ Keyd")
        assertNotNull(a.text("Languages"))
    }

    @Test
    fun `resetting asks first, and changes nothing until it is confirmed`() {
        val a = open()
        a.tap("Keys and gestures")
        a.tap("Number row")
        a.tap("‹ Keyd")
        a.tap("Privacy")
        a.tap(a.getString(R.string.settings_reset))
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertTrue("reset before being confirmed", a.stored().numberRow)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        // A dialog button's click arrives through the main thread's queue.
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertFalse(a.stored().numberRow)
    }

    @Test
    fun `forgetting learned words can't be tapped when there is nothing to forget`() {
        val a = open()
        a.tap("Privacy")
        val forget = a.text(a.getString(R.string.row_forget))!!
        assertFalse((forget.parent as View).isEnabled)
    }

    // ---- Keyd 0.2.0 --------------------------------------------------------------------------------------------

    @Test
    fun `the toolbar keys and key style are there and save`() {
        val a = open()
        a.tap("Keys and gestures")
        a.tap("Toolbar")
        a.tap("Voice")
        a.tap("Cursor pad")
        assertEquals(Settings.DEFAULT_TOOLBAR - ToolKey.VOICE - ToolKey.CURSOR_PAD, a.stored().toolbar)
        a.tap("‹ Keys and gestures")
        a.tap("‹ Keyd")
        a.tap("Look and size")
        assertNotNull(a.text("KEY STYLE"))
        a.tap("Samsung")
        assertEquals(KeyStyle.SAMSUNG, a.stored().keyStyle)
    }

    @Test
    fun `privacy has moving phones and diagnostics, with reset still last`() {
        val a = open()
        a.tap("Privacy")
        for (label in listOf("MOVE TO A NEW PHONE", "Export words and shortcuts…", "Import from a file…",
            "DIAGNOSTICS", "Keep a diagnostic log")) {
            assertNotNull("missing $label", a.text(label))
        }
        // The page's own text, not the window's title bar.
        val page = a.all().filterIsInstance<android.widget.ScrollView>().first()
        val texts = a.all().filter { v -> generateSequence(v.parent) { it.parent }.any { it === page } }
            .filterIsInstance<TextView>().map { it.text.toString() }.filter { it.isNotBlank() }
        assertEquals(a.getString(R.string.settings_reset), texts.last())
        // Off until someone turns it on, and turning it on is the same switch the keyboard checks.
        assertFalse(a.switchIn("Keep a diagnostic log").isChecked)
        a.tap("Keep a diagnostic log")
        assertTrue(DevLog.loggingOn(a))
    }

    @Test
    fun `export and import go through Android's file picker`() {
        val a = open()
        a.tap("Privacy")
        a.tap("Export words and shortcuts…")
        val export = org.robolectric.Shadows.shadowOf(a).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, export.action)
        assertEquals("application/json", export.type)
        assertTrue(export.getStringExtra(android.content.Intent.EXTRA_TITLE)!!.matches(Regex("keyd-backup-\\d{4}-\\d{2}-\\d{2}\\.json")))
        a.tap("Import from a file…")
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, org.robolectric.Shadows.shadowOf(a).nextStartedActivity.action)
    }

    @Test
    fun `a report is written, previewed, and shared only from the preview`() {
        val a = open()
        a.tap("Report a problem")
        assertNotNull(a.text("WHAT HAPPENED"))
        assertNotNull(a.text("INCLUDE"))
        val fields = a.all().filterIsInstance<EditText>()
        assertEquals(3, fields.size)
        // Each field is named by its question, for TalkBack.
        val labels = a.all().filterIsInstance<TextView>().filter { it.labelFor != View.NO_ID }.map { it.text.toString() }
        assertEquals(listOf("Which app were you typing in?", "What did you do?", "What happened?"), labels)
        fields[0].setText("Messages")
        fields[1].setText("Pinned a clip")
        fields[2].setText("It closed")
        // With nothing logged, the log can't be included.
        assertFalse(a.all().filterIsInstance<Switch>().last().isEnabled)
        a.tap("Preview report")
        val report = a.all().filterIsInstance<TextView>().first { it.text.startsWith("Keyd report") }.text.toString()
        assertTrue(report.contains("App: Messages\nDid: Pinned a clip\nSaw: It closed"))
        assertTrue(report.contains("\nSettings\n"))
        assertFalse(report.contains("\nLog\n"))
        // Back to the form: what was written is still there.
        a.tap("‹ Report a problem")
        assertEquals("Messages", a.all().filterIsInstance<EditText>()[0].text.toString())
        a.tap("Preview report")
        a.tap("Share…")
        val chooser = org.robolectric.Shadows.shadowOf(a).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        assertEquals("Keyd report", send.getStringExtra(android.content.Intent.EXTRA_SUBJECT))
        assertEquals(report, send.getStringExtra(android.content.Intent.EXTRA_TEXT))
    }

    @Test
    fun `the report form keeps its answers when the phone turns`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("keys", Context.MODE_PRIVATE).edit().clear().commit()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        controller.get().tap("Report a problem")
        controller.get().all().filterIsInstance<EditText>()[1].setText("Swiped the space bar")
        controller.recreate()
        assertEquals("Swiped the space bar", controller.get().all().filterIsInstance<EditText>()[1].text.toString())
    }

    @Test
    fun `after a crash the first page offers a report, and not now puts it away`() {
        val a = open(crashed = true)
        assertNotNull(a.text("Keyd stopped unexpectedly"))
        assertNotNull(a.text(a.getString(R.string.footer_crash)))
        a.tap("Not now")
        assertNull(a.text("Keyd stopped unexpectedly"))
        assertFalse(DevLog.crashedSinceLooked(a))
    }

    @Test
    fun `no crash, no card, and sending a report clears the mark`() {
        assertNull(open().text("Keyd stopped unexpectedly"))
        val a = open(crashed = true)
        a.tap("Send a report")
        assertNotNull(a.text("WHAT HAPPENED"))
        a.tap("Preview report")
        a.tap("Share…")
        assertFalse(DevLog.crashedSinceLooked(a))
    }

    // ---- Keyd 0.3.0: what it fixes ------------------------------------------------------------------------------

    /** Settings opened with some counts already on the phone: "teh" fixed 41 times, "Folio" put back 3. */
    private fun openWithCounts(): SettingsActivity {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val store = Insights()
        repeat(41) { store.fixStood("teh", "the", offering = false) }
        repeat(3) { store.undone("Folio", offering = false) }
        context.getSharedPreferences("keys", Context.MODE_PRIVATE).edit().clear()
            .putString("typingInsights", store.encode()).commit()
        DevLog.clear(context)
        return Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
    }

    private fun SettingsActivity.prefs() = getSharedPreferences("keys", Context.MODE_PRIVATE)

    @Test
    fun `typing leads to what it fixes, which starts empty`() {
        val a = open()
        a.tap("Typing")
        assertNotNull(a.text("The words it corrects for you, and the ones you put back"))
        a.tap("What it fixes")
        for (label in listOf(
            "Keyd keeps count of the fixes it makes and the ones you undo, on this phone only, and never from a password field.",
            "FIXED FOR YOU", "YOU PUT BACK", "Offer these on the keyboard",
        )) assertNotNull("missing $label", a.text(label))
        assertEquals(2, a.all().filterIsInstance<TextView>().count { it.text.toString() == "Nothing yet" })
        assertTrue(a.switchIn("Offer these on the keyboard").isChecked)
        // Nothing to forget, so the red row can't be tapped.
        assertFalse((a.text("Forget these counts")!!.parent as View).isEnabled)
        a.tap("‹ Typing")
        assertNotNull(a.text("What it fixes"))
    }

    @Test
    fun `a fix shows its count, and a tap makes it a rule`() {
        val a = openWithCounts()
        a.tap("Typing")
        a.tap("What it fixes")
        assertNotNull(a.text("teh → the"))
        assertNotNull(a.text("41 times"))
        assertNotNull(a.text("Tap for a rule that always fixes it"))
        a.tap("teh → the")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals("Added a rule", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertEquals("the", Shortcuts.decode(a.prefs().getString("shortcuts", null)).expand("teh"))
        assertNotNull(a.text("Rule added"))
        // Answered here is answered: the strip will not ask about it again.
        assertTrue(Insights.decode(a.prefs().getString("typingInsights", null)).settled(Insights.Offer("teh", "the")))
    }

    @Test
    fun `a word put back can be kept with a tap`() {
        val a = openWithCounts()
        a.tap("Typing")
        a.tap("What it fixes")
        assertNotNull(a.text("3 times"))
        a.tap("Folio")
        assertTrue(Learned.decode(a.prefs().getString("learnedWords", null)).count("folio") >= Learned.MEANT_IT)
        assertNotNull(a.text("Kept"))
        assertNull(a.text("Tap to keep it as a word"))
    }

    @Test
    fun `offers on the keyboard can be turned off`() {
        val a = open()
        a.tap("Typing")
        a.tap("What it fixes")
        a.tap("Offer these on the keyboard")
        assertFalse(a.stored().offerRules)
    }

    @Test
    fun `forgetting the counts asks first`() {
        val a = openWithCounts()
        a.tap("Typing")
        a.tap("What it fixes")
        a.tap("Forget these counts")
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertNotNull(a.prefs().getString("typingInsights", null))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertNull(a.prefs().getString("typingInsights", null))
        assertEquals(2, a.all().filterIsInstance<TextView>().count { it.text.toString() == "Nothing yet" })
    }

    @Test
    fun `privacy counts the fixes, and a reset leaves them alone`() {
        val a = openWithCounts()
        a.tap("Privacy")
        val row = a.text("Fixes it has counted")!!.parent as ViewGroup
        assertEquals("2", (row.getChildAt(1) as TextView).text.toString())
        a.tap(a.getString(R.string.settings_reset))
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(2, Insights.decode(a.prefs().getString("typingInsights", null)).size)
    }

    // ---- Keyd 0.3.0 --------------------------------------------------------------------------------------------

    /** A switch in a row that has a line under its title, where the title sits one level further in. */
    private fun SettingsActivity.subtitledSwitch(label: String): Switch {
        var row: View = text(label)!!
        while (row.parent is View && (row as? ViewGroup)?.let { g -> (0 until g.childCount).any { g.getChildAt(it) is Switch } } != true) {
            row = row.parent as View
        }
        val group = row as ViewGroup
        return (0 until group.childCount).map { group.getChildAt(it) }.filterIsInstance<Switch>().single()
    }

    private fun openWithApps(vararg apps: String, set: AppProfiles.() -> Unit = {}): SettingsActivity {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("keys", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        DevLog.clear(context)
        AppProfiles().apply {
            apps.reversed().forEach { typedIn(it, context.packageName) }
            set()
        }.save(prefs)
        return Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
    }

    private fun SettingsActivity.apps() = AppProfiles.load(getSharedPreferences("keys", Context.MODE_PRIVATE))

    @Test
    fun `typing leads to per-app settings, which says how many apps have changes`() {
        val a = openWithApps("com.termux", "com.whatsapp") {
            set("com.termux", AppProfiles.Profile(useUsual = false, autocorrect = false))
        }
        a.tap("Typing")
        assertNotNull(a.text("1 app"))
        a.tap("Per-app settings")
        assertNotNull(a.text(a.getString(R.string.per_app_note)))
        assertNotNull(a.text("CHANGED"))
        assertNotNull(a.text("AS USUAL"))
        assertNotNull(a.text("No fixing"))
        assertNotNull(a.text("Only the last 30 apps you typed in are listed, newest first."))
        // No app of that name is installed here, so the package name stands in for it.
        assertNotNull(a.text("com.termux"))
        assertNotNull(a.text("com.whatsapp"))
        a.tap("‹ Typing")
        assertNotNull(a.text("Per-app settings"))
    }

    @Test
    fun `with no apps yet the list says so`() {
        val a = openWithApps()
        a.tap("Typing")
        assertNotNull(a.text("0 apps"))
        a.tap("Per-app settings")
        assertNotNull(a.text(a.getString(R.string.per_app_empty)))
        assertNull(a.text("CHANGED"))
        assertNull(a.text("AS USUAL"))
    }

    @Test
    fun `an app's own switches are greyed while it uses the usual settings`() {
        val a = openWithApps("com.termux")
        a.tap("Typing")
        a.tap("Per-app settings")
        a.tap("com.termux")
        assertNotNull(a.text("IN COM.TERMUX"))
        assertNotNull(a.text("Off means the switches below are used in com.termux instead of Keyd’s own."))
        assertTrue(a.switchIn("Use the usual settings").isChecked)
        for (label in listOf("Suggestions", "Fix clear typos", "Learn new words", "Number row")) {
            assertFalse("$label should be greyed", a.switchIn(label).isEnabled)
        }
        // Showing the usual answers: suggestions on, number row off.
        assertTrue(a.switchIn("Suggestions").isChecked)
        assertFalse(a.switchIn("Number row").isChecked)
        a.tap("Use the usual settings")
        assertTrue(a.switchIn("Number row").isEnabled)
        a.tap("Fix clear typos")
        a.tap("Number row")
        val profile = a.apps().profile("com.termux")
        assertEquals(AppProfiles.Profile(useUsual = false, autocorrect = false, numberRow = true), profile)
        assertEquals(listOf("com.termux"), a.apps().changed)
        // Nothing else moved: the usual settings are as they were.
        assertTrue(a.stored().autocorrect)
        assertFalse(a.stored().numberRow)
        a.tap("‹ Per-app settings")
        assertNotNull(a.text("2 changes"))
    }

    @Test
    fun `fixing typos goes with suggestions on an app's page too`() {
        val a = openWithApps("com.termux") { set("com.termux", AppProfiles.Profile(useUsual = false)) }
        a.tap("Typing")
        a.tap("Per-app settings")
        a.tap("com.termux")
        a.tap("Suggestions")
        assertFalse(a.switchIn("Fix clear typos").isChecked)
        assertFalse(a.switchIn("Fix clear typos").isEnabled)
        assertEquals(AppProfiles.Profile(useUsual = false, suggestions = false), a.apps().profile("com.termux"))
    }

    @Test
    fun `forgetting an app takes it off the list with its settings`() {
        val a = openWithApps("com.termux", "com.whatsapp") {
            set("com.termux", AppProfiles.Profile(useUsual = false, learn = false))
        }
        a.tap("Typing")
        a.tap("Per-app settings")
        a.tap("com.termux")
        a.tap("Forget this app")
        assertNotNull(a.text("AS USUAL"))
        assertNull(a.text("com.termux"))
        assertEquals(listOf("com.whatsapp"), a.apps().recentApps)
        assertEquals(AppProfiles.Profile(), a.apps().profile("com.termux"))
    }

    @Test
    fun `an app's page is still open after the phone turns`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("keys", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        AppProfiles().apply { typedIn("com.termux", context.packageName) }.save(prefs)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        controller.get().tap("Typing")
        controller.get().tap("Per-app settings")
        controller.get().tap("com.termux")
        controller.recreate()
        assertNotNull(controller.get().text("IN COM.TERMUX"))
    }

    @Test
    fun `vibration is a strength, and the Bluetooth switch saves`() {
        val a = open()
        a.tap("Sound and vibration")
        for (label in listOf("VIBRATION", "Off", "Light", "Medium", "Strong", "No clicks in your headphones")) {
            assertNotNull("missing $label", a.text(label))
        }
        a.tap("Strong")
        assertEquals(Vibration.STRONG, a.stored().vibration)
        assertTrue(a.subtitledSwitch("Mute with Bluetooth audio").isChecked)
        a.tap("Mute with Bluetooth audio")
        assertFalse(a.stored().muteWithBluetooth)
    }

    @Test
    fun `pure black is under the light and dark choice, and saves`() {
        val a = open()
        a.tap("Look and size")
        assertNotNull(a.text("Saves power on this screen"))
        val all = a.all().filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(all.indexOf(a.getString(R.string.settings_appearance_dark)) < all.indexOf("Pure black when dark"))
        assertFalse(a.subtitledSwitch("Pure black when dark").isChecked)
        a.tap("Pure black when dark")
        assertTrue(a.stored().pureBlack)
    }
}
