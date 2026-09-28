package com.mccal.folio.keys

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings that follow the app being typed in: what is kept, what it changes, and what it can never change.
 *
 * The list holds package names and nothing else, so most of what is worth checking is that it stays that way - the
 * cap, the order, Keyd's own settings screen never listed - and that a password field still wins over all of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppProfilesTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("keys", Context.MODE_PRIVATE)

    @Before
    fun clean() {
        prefs.edit().clear().commit()
    }

    private val terminal = AppProfiles.Profile(useUsual = false, autocorrect = false, learn = false)

    // ---- the list -----------------------------------------------------------------------------------------------

    @Test
    fun `apps are listed newest first, and typing in one again moves it to the top`() {
        val apps = AppProfiles()
        for (app in listOf("com.a", "com.b", "com.c")) apps.typedIn(app, OWN)
        assertEquals(listOf("com.c", "com.b", "com.a"), apps.recentApps)
        assertTrue(apps.typedIn("com.a", OWN))
        assertEquals(listOf("com.a", "com.c", "com.b"), apps.recentApps)
        // The same app again changes nothing, so nothing needs writing.
        assertFalse(apps.typedIn("com.a", OWN))
    }

    @Test
    fun `only the last 30 apps are kept`() {
        val apps = AppProfiles()
        repeat(40) { apps.typedIn("com.app$it", OWN) }
        assertEquals(AppProfiles.LIMIT, apps.recentApps.size)
        assertEquals("com.app39", apps.recentApps.first())
        assertEquals("com.app10", apps.recentApps.last())
    }

    @Test
    fun `an app with its own settings is never pushed off the list`() {
        val apps = AppProfiles()
        apps.typedIn("com.termux", OWN)
        apps.set("com.termux", terminal)
        repeat(40) { apps.typedIn("com.app$it", OWN) }
        assertEquals(AppProfiles.LIMIT, apps.recentApps.size)
        assertTrue("com.termux" in apps.recentApps)
        assertEquals(listOf("com.termux"), apps.changed)
    }

    @Test
    fun `Keyd's own settings, an empty package and anything that is not a package name are never listed`() {
        val apps = AppProfiles()
        assertFalse(apps.typedIn(OWN, OWN))
        assertFalse(apps.typedIn(null, OWN))
        assertFalse(apps.typedIn("", OWN))
        assertFalse(apps.typedIn("hello world\nsecret", OWN))
        assertTrue(apps.recentApps.isEmpty())
    }

    @Test
    fun `the list and the settings survive being stored`() {
        val apps = AppProfiles()
        apps.typedIn("com.whatsapp", OWN)
        apps.typedIn("com.termux", OWN)
        apps.set("com.termux", terminal)
        apps.set("com.sheets", AppProfiles.Profile(useUsual = false, numberRow = true))
        apps.save(prefs)
        val back = AppProfiles.load(prefs)
        assertEquals(apps.recentApps, back.recentApps)
        assertEquals(terminal, back.profile("com.termux"))
        assertEquals(AppProfiles.Profile(useUsual = false, numberRow = true), back.profile("com.sheets"))
        assertEquals(AppProfiles.Profile(), back.profile("com.whatsapp"))
    }

    @Test
    fun `a damaged store gives what it can and ignores the rest`() {
        val back = AppProfiles.decode("com.ok\n\nnot a package\ncom.ok", "com.x|0|1|-|-|-\nbroken\ncom.y|0|1")
        assertEquals(listOf("com.ok", "com.x"), back.recentApps)
        assertEquals(AppProfiles.Profile(useUsual = false, suggestions = true), back.profile("com.x"))
    }

    @Test
    fun `forgetting an app takes its settings with it`() {
        val apps = AppProfiles()
        apps.typedIn("com.termux", OWN)
        apps.set("com.termux", terminal)
        apps.forget("com.termux")
        assertTrue(apps.recentApps.isEmpty())
        assertEquals(AppProfiles.Profile(), apps.profile("com.termux"))
    }

    // ---- what it changes ----------------------------------------------------------------------------------------

    @Test
    fun `an app's own answers are laid over the usual settings, and only in that app`() {
        val apps = AppProfiles()
        apps.set("com.termux", terminal)
        val usual = Settings(numberRow = true)
        val there = apps.apply(usual, "com.termux")
        assertFalse(there.autocorrect)
        assertFalse(there.learn)
        // What it didn't answer, it takes from the usual settings.
        assertTrue(there.suggestions)
        assertTrue(there.numberRow)
        assertEquals(usual, apps.apply(usual, "com.whatsapp"))
        assertEquals(usual, apps.apply(usual, null))
    }

    @Test
    fun `using the usual settings means none of its own apply, but they are kept for later`() {
        val apps = AppProfiles()
        apps.set("com.termux", terminal.copy(useUsual = true))
        assertEquals(Settings(), apps.apply(Settings(), "com.termux"))
        assertTrue(apps.changed.isEmpty())
        assertEquals(false, apps.profile("com.termux").learn)
    }

    @Test
    fun `the summary says what is different from the usual settings`() {
        val apps = AppProfiles()
        apps.set("com.termux", AppProfiles.Profile(useUsual = false, autocorrect = false))
        assertEquals(listOf(AppProfiles.Change.FIXING_OFF), apps.changes("com.termux", Settings()))
        apps.set("com.sheets", AppProfiles.Profile(useUsual = false, numberRow = true, learn = false))
        assertEquals(2, apps.changes("com.sheets", Settings()).size)
        // An answer that matches the usual settings is not a change.
        apps.set("com.same", AppProfiles.Profile(useUsual = false, suggestions = true))
        assertTrue(apps.changes("com.same", Settings()).isEmpty())
    }

    @Test
    fun `putting every setting back also puts every app back, and keeps the list`() {
        val apps = AppProfiles()
        apps.typedIn("com.termux", OWN)
        apps.set("com.termux", terminal)
        apps.save(prefs)
        Settings.reset(prefs)
        val back = AppProfiles.load(prefs)
        assertEquals(listOf("com.termux"), back.recentApps)
        assertTrue(back.changed.isEmpty())
        assertEquals(Settings(), back.apply(Settings.load(prefs), "com.termux"))
    }

    @Test
    fun `a report's settings never name an app`() {
        val apps = AppProfiles()
        apps.typedIn("com.example.secretdiary", OWN)
        apps.set("com.example.secretdiary", terminal)
        apps.save(prefs)
        val report = DevLog.report(context, "build")
        assertTrue(report.contains("\nSettings\n"))
        assertFalse(report.contains("secretdiary"))
        assertFalse(report.contains("com.example"))
    }

    // ---- in the keyboard ----------------------------------------------------------------------------------------

    @Test
    fun `a field opening in an app notes the app and types with that app's settings`() {
        AppProfiles().apply { set("com.termux", terminal) }.save(prefs)
        val service = Robolectric.buildService(KeysService::class.java).create().get()
        service.onStartInputView(EditorInfo().also { it.packageName = "com.termux" }, false)
        assertEquals("com.termux", AppProfiles.load(prefs).recentApps.first())
        val there = service.settingsFor("com.termux")
        assertFalse(there.autocorrect)
        assertFalse(there.learn)
        assertTrue(service.settingsFor("com.whatsapp").learn)
        // Keyd's own settings screen is never listed.
        service.onStartInputView(EditorInfo().also { it.packageName = context.packageName }, false)
        assertFalse(context.packageName in AppProfiles.load(prefs).recentApps)
    }

    private class Ime(override val connection: InputConnection?) : com.mccal.folio.keys.Ime {
        override var editorInfo: EditorInfo? = null
        val asked = mutableListOf<String>()
        val taught = mutableListOf<String>()
        var rows: List<Row> = emptyList()
        override fun switchKeyboard() = Unit
        override fun hideKeyboard() = Unit
        override fun show(rows: List<Row>, shift: Shift) { this.rows = rows }
        override fun suggest(word: String) { asked += word }
        override fun learn(word: String) { taught += word }
        override fun showEmoji(showing: Boolean) = Unit
        override fun showClipboard(showing: Boolean) = Unit
    }

    private fun typeIn(app: String, inputType: Int, word: String): Ime {
        val ime = Ime(BaseInputConnection(View(context), true))
        val actions = TextActions(ime)
        val apps = AppProfiles.load(prefs)
        actions.settings = apps.apply(Settings.load(prefs), app)
        ime.editorInfo = EditorInfo().also { it.inputType = inputType; it.packageName = app }
        actions.startInput(ime.editorInfo)
        word.forEach { actions.onText(it.toString()) }
        return ime
    }

    @Test
    fun `an app that learns nothing learns nothing, and one that asks for a number row gets one`() {
        AppProfiles().apply {
            set("com.termux", terminal)
            set("com.sheets", AppProfiles.Profile(useUsual = false, numberRow = true))
        }.save(prefs)
        assertTrue(typeIn("com.termux", InputType.TYPE_CLASS_TEXT, "grepx ").taught.isEmpty())
        assertEquals(listOf("grepx"), typeIn("com.whatsapp", InputType.TYPE_CLASS_TEXT, "grepx ").taught)
        val digits = { ime: Ime -> ime.rows.firstOrNull()?.map { it.label } == (1..9).map { "$it" } + "0" }
        assertTrue(digits(typeIn("com.sheets", InputType.TYPE_CLASS_TEXT, "")))
        assertFalse(digits(typeIn("com.whatsapp", InputType.TYPE_CLASS_TEXT, "")))
    }

    @Test
    fun `a password field still wins over an app's own settings`() {
        Settings(suggestions = false, learn = false).save(prefs)
        AppProfiles().apply {
            set("com.bank", AppProfiles.Profile(useUsual = false, suggestions = true, learn = true))
        }.save(prefs)
        val ime = typeIn("com.bank", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "hunter2 ")
        assertTrue(ime.taught.isEmpty())
        assertTrue("asked about ${ime.asked}", ime.asked.all { it.isEmpty() })
        // The same app's ordinary field does get what it asked for.
        val plain = typeIn("com.bank", InputType.TYPE_CLASS_TEXT, "payee ")
        assertEquals(listOf("payee"), plain.taught)
        assertTrue(plain.asked.contains("payee"))
    }

    private companion object {
        const val OWN = "com.mccal.keyd"
    }
}
