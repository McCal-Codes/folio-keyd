package com.mccal.folio.keys

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.ScrollView
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The 0.4.0 Settings: the regrouped pages, the segmented choices, search, and the What's New sheet.
 *
 * Like SettingsScreenTest it drives the screen the way someone would. It also draws three pages into build/renders,
 * so they can be put beside the Mockup Lab's pictures.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsRedesignTest {

    private fun open(): SettingsActivity {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("keys", Context.MODE_PRIVATE).edit().clear().commit()
        DevLog.clear(context)
        return Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
    }

    private fun SettingsActivity.all(): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) { out += v; if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(window.decorView)
        return out
    }

    private fun SettingsActivity.texts() = all().filterIsInstance<TextView>().map { it.text.toString() }

    private fun SettingsActivity.text(label: String): TextView? =
        all().filterIsInstance<TextView>().firstOrNull { it.text.toString() == label }

    private fun SettingsActivity.tap(label: String) {
        var target: View = text(label) ?: throw AssertionError("no \"$label\" on screen")
        while (!target.isClickable && target.parent is View) target = target.parent as View
        target.performClick()
    }

    private fun SettingsActivity.stored() = Settings.load(getSharedPreferences("keys", Context.MODE_PRIVATE))

    /** Every row title Keyd's settings had before the redesign, and the pages under them. None may go missing. */
    private val everyRow = listOf(
        R.string.row_languages, R.string.row_shortcuts, R.string.page_typing, R.string.page_keys, R.string.page_look,
        R.string.page_feel, R.string.page_clipboard, R.string.page_privacy, R.string.row_whats_new, R.string.row_report,
        R.string.row_version,
        // Smart typing
        R.string.settings_suggestions, R.string.settings_suggest_emoji, R.string.settings_autocorrect,
        R.string.settings_spell_check, R.string.settings_learn, R.string.page_what_it_fixes,
        R.string.settings_capitals, R.string.settings_double_space, R.string.row_per_app,
        // Keys and gestures
        R.string.settings_number_row, R.string.settings_accents, R.string.row_period, R.string.row_toolbar,
        R.string.settings_cursor_swipe, R.string.settings_delete_word, R.string.settings_edit_swipes,
        R.string.settings_shift_select, R.string.settings_selection_tools, R.string.settings_two_finger,
        R.string.settings_swipe_hide, R.string.settings_flick_down, R.string.settings_flick_up,
        R.string.segment_hold_delay, R.string.segment_backspace_speed, R.string.settings_gesture_tips,
        R.string.row_tips_again,
        // Style and layout
        R.string.settings_key_style_folio, R.string.settings_key_style_material, R.string.settings_key_style_samsung,
        R.string.settings_appearance, R.string.settings_pure_black, R.string.settings_high_contrast,
        R.string.settings_size, R.string.settings_split, R.string.settings_one_handed,
        // Sound and vibration
        R.string.settings_sound, R.string.settings_mute_bluetooth, R.string.segment_strength, R.string.settings_preview,
        // Clipboard and Privacy
        R.string.settings_clipboard, R.string.row_internet, R.string.row_permissions, R.string.row_learned,
        R.string.row_forget, R.string.row_fixes_counted, R.string.row_export, R.string.row_import,
        R.string.settings_diagnostic_log, R.string.settings_reset,
        // A level further down
        R.string.settings_offer_rules, R.string.row_forget_counts, R.string.period_reset, R.string.toolbar_reset,
    )

    @Test
    fun `every row Keyd has can be reached from the first page`() {
        val a = open()
        val found = a.texts().toMutableSet()
        for ((page, under) in listOf(
            "Smart typing" to listOf("What it fixes"),
            "Keys and gestures" to listOf("Hold the period for", "Toolbar"),
            "Style and layout" to emptyList(),
            "Sound and vibration" to emptyList(),
            "Clipboard" to emptyList(),
            "Privacy" to emptyList(),
        )) {
            a.tap(page)
            found += a.texts()
            for (sub in under) {
                a.tap(sub)
                found += a.texts()
                a.tap("‹ $page")
            }
            a.tap("‹ Keyd")
        }
        assertEquals(emptyList<String>(), everyRow.map { a.getString(it) }.filterNot { it in found })
    }

    @Test
    fun `every row is searchable, with the page it is on`() {
        val a = open()
        val index = a.searchIndex()
        val titles = index.map { it.title }.toSet()
        assertEquals(emptyList<String>(), everyRow.map { a.getString(it) }.filterNot { it in titles })
        val strength = index.single { it.title == "Strength" }
        assertEquals("Sound and vibration", strength.where)
        assertEquals("Keyd", index.first { it.title == "Smart typing" }.where)
        // A note is searched as part of the rows it sits under.
        assertTrue(SettingsSearch.find(index, "Android’s own Touch").any { it.title == "Touch and hold delay" })
    }

    @Test
    fun `the search field opens search, and a result opens its page`() {
        val a = open()
        a.tap("Search settings")
        val field = a.all().filterIsInstance<EditText>().single()
        field.setText("VIB")
        val header = a.texts().firstOrNull { it.endsWith("RESULTS") || it.endsWith("RESULT") }
        assertNotNull(header)
        assertNotNull(a.text("Strength"))
        assertTrue(a.texts().contains("Sound and vibration"))
        a.tap("Strength")
        assertNotNull(a.text("VIBRATION"))
        // Back from a result is back to the results, with what was typed still there.
        assertNotNull(a.text("‹ Search"))
        a.tap("‹ Search")
        assertEquals("VIB", a.all().filterIsInstance<EditText>().single().text.toString())
        assertNotNull(a.text("Strength"))
        assertNotNull(a.text("‹ Keyd"))
    }

    @Test
    fun `back from a result, by the system's back, is back to the results`() {
        val a = open()
        a.tap("Search settings")
        a.all().filterIsInstance<EditText>().single().setText("split")
        a.tap("Split keyboard")
        assertNotNull(a.text("SIZE AND LAYOUT"))
        @Suppress("DEPRECATION") a.onBackPressed()
        assertEquals("split", a.all().filterIsInstance<EditText>().single().text.toString())
        @Suppress("DEPRECATION") a.onBackPressed()
        assertNotNull(a.text("Search settings"))
        // Opened the ordinary way, the same page goes back to the page it is under.
        a.tap("Style and layout")
        assertNotNull(a.text("‹ Keyd"))
    }

    @Test
    fun `search finds a setting by what it does, not only by its name`() {
        val a = open()
        val index = a.searchIndex()
        fun finds(query: String, title: String) =
            assertTrue("\"$query\" should find $title", SettingsSearch.find(index, query).any { it.title == title })
        finds("password", a.getString(R.string.settings_spell_check))
        finds("password", a.getString(R.string.settings_learn))
        finds("squiggle", a.getString(R.string.settings_spell_check))
        finds("fold", a.getString(R.string.settings_split))
        finds("unfolded", a.getString(R.string.settings_split))
        finds("saves power", a.getString(R.string.settings_pure_black))
        finds("costs a row", a.getString(R.string.settings_number_row))
        finds("Gboard", a.getString(R.string.settings_key_style_material))
        finds("instead of typing a capital", a.getString(R.string.settings_edit_swipes))
        finds("keeps deleting", a.getString(R.string.segment_backspace_speed))
        finds("you put back", a.getString(R.string.page_what_it_fixes))
        finds("drift", a.getString(R.string.settings_cursor_swipe))
        // Both notes under Corrections belong to its rows, not only the first.
        val learn = index.single { it.title == a.getString(R.string.settings_learn) }
        assertTrue(learn.footer!!.contains(a.getString(R.string.footer_corrections)))
        assertTrue(learn.footer!!.contains(a.getString(R.string.settings_learn_note)))
        // The words are for search only: the old notes stay off the page.
        a.tap("Style and layout")
        assertNull(a.text(a.getString(R.string.settings_split_note)))
    }

    @Test
    fun `spell check says on screen that it never runs in a password field`() {
        val a = open()
        a.tap("Smart typing")
        assertNotNull(a.text("Never in a password field."))
        a.tap(a.getString(R.string.settings_spell_check))
        assertFalse(a.stored().spellCheck)
    }

    @Test
    fun `buttons on the toolbar and the period's field are found by search`() {
        val a = open()
        val index = a.searchIndex()
        assertEquals("Toolbar", index.single { it.title == "Copy" }.where)
        assertTrue(index.any { it.title == a.getString(R.string.period_field) })
    }

    @Test
    fun `the search field reads what is typed, not its own name`() {
        val a = open()
        a.tap("Search settings")
        val field = a.all().filterIsInstance<EditText>().single()
        assertNull(field.contentDescription)
        assertEquals("Search settings", field.hint.toString())
    }

    @Test
    fun `the index is read once, when something is searched, and again only after a change`() {
        val a = open()
        assertEquals(0, a.indexBuilds)
        a.tap("Search settings")
        assertEquals("nothing typed, nothing read", 0, a.indexBuilds)
        val field = a.all().filterIsInstance<EditText>().single()
        field.setText("v")
        field.setText("vi")
        field.setText("vib")
        assertEquals(1, a.indexBuilds)
        a.tap("‹ Keyd")
        a.tap("Search settings")
        a.all().filterIsInstance<EditText>().single().setText("vib")
        assertEquals("drawing a page again reads nothing new", 1, a.indexBuilds)
        a.tap("‹ Keyd")
        a.tap("Keys and gestures")
        a.tap(a.getString(R.string.settings_number_row))
        a.tap("‹ Keyd")
        a.tap("Search settings")
        a.all().filterIsInstance<EditText>().single().setText("vib")
        assertEquals("a change is read again", 2, a.indexBuilds)
    }

    @Test
    fun `the result count is one view, changed only when the count changes`() {
        val a = open()
        a.tap("Search settings")
        val field = a.all().filterIsInstance<EditText>().single()
        field.setText("vibr")
        val count = a.all().filterIsInstance<TextView>().single { it.text.endsWith("RESULT") || it.text.endsWith("RESULTS") }
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, count.accessibilityLiveRegion)
        val said = count.text
        field.setText("vibra")
        val again = a.all().filterIsInstance<TextView>().single { it.text.endsWith("RESULT") || it.text.endsWith("RESULTS") }
        assertTrue("the same view", count === again)
        assertTrue("the same text, not set again", said === again.text)
        field.setText("zzqx")
        assertEquals(View.GONE, count.visibility)
        assertNotNull(a.text("No settings match “zzqx”"))
    }

    @Test
    fun `a search with no match says so`() {
        val a = open()
        a.tap("Search settings")
        a.all().filterIsInstance<EditText>().single().setText("zzqx")
        assertNotNull(a.text("No settings match “zzqx”"))
    }

    @Test
    fun `a segmented choice saves at once and says which it is`() {
        val a = open()
        a.tap("Sound and vibration")
        a.tap("Light")
        assertEquals(Vibration.LIGHT, a.stored().vibration)
        val light = a.text("Light")!!
        val info = AccessibilityNodeInfo.obtain()
        light.onInitializeAccessibilityNodeInfo(info)
        assertTrue(info.isChecked)
        assertTrue(info.isSelected)
        assertEquals("Selected, 2 of 4", info.stateDescription)
        assertEquals(android.widget.Button::class.java.name, info.className)
        val group = light.parent as Segmented
        assertEquals("Strength", group.contentDescription)
        val medium = AccessibilityNodeInfo.obtain().also { a.text("Medium")!!.onInitializeAccessibilityNodeInfo(it) }
        assertFalse(medium.isChecked)
        assertTrue(a.text("Medium")!!.minHeight >= (48 * a.resources.displayMetrics.density).toInt())
    }

    /** WCAG's contrast ratio between two colors. */
    private fun contrast(a: Int, b: Int): Double {
        fun lum(c: Int): Double {
            fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(android.graphics.Color.red(c)) + 0.7152 * ch(android.graphics.Color.green(c)) +
                0.0722 * ch(android.graphics.Color.blue(c))
        }
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun chosenOutlineClears3to1() {
        val a = open()
        a.tap("Sound and vibration")
        val group = a.text("Light")!!.parent as Segmented
        val colors = group.palette
        assertTrue("outline ${contrast(colors.outline, colors.track)}:1", contrast(colors.outline, colors.track) >= 3.0)
    }

    @Test
    fun `the chosen option is outlined clearly enough to see, in light mode`() = chosenOutlineClears3to1()

    @Test
    @Config(qualifiers = "night")
    fun `the chosen option is outlined clearly enough to see, in dark mode`() = chosenOutlineClears3to1()

    @Test
    fun `the style page has a picture of the keyboard and the theme choices`() {
        val a = open()
        a.tap("Style and layout")
        assertTrue(a.all().any { it is KeyboardView })
        for (label in listOf("KEY STYLE", "THEME", "Light or dark", "SIZE AND LAYOUT", "Keyboard height", "Split keyboard", "One-handed")) {
            assertNotNull("missing $label", a.text(label))
        }
        a.tap("Tall")
        assertEquals(Size.LARGE, a.stored().size)
        a.tap("Dark")
        assertEquals(Appearance.DARK, a.stored().appearance)
    }

    @Test
    @Config(qualifiers = "w330dp-h700dp-xhdpi")
    fun `options wrap onto another line rather than being cut short`() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val control = Segmented(context, "Touch and hold delay", listOf("Shorter", "Phone", "Longer"), 1,
            Segmented.Colors(0, 0, 0, 0)) {}
        val width = ((330 - 64) * context.resources.displayMetrics.density).toInt()
        control.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        control.layout(0, 0, control.measuredWidth, control.measuredHeight)
        assertTrue("one line at 200%", control.lineCount > 1)
        for (i in 0 until 3) {
            val option = control.option(i)
            val natural = option.paint.measureText(option.text.toString()) + option.paddingLeft + option.paddingRight
            assertTrue("${option.text} cut short", option.measuredWidth >= natural)
            assertTrue(option.right <= width)
        }
    }

    @Test
    fun `smart typing groups the switches, and double-space makes a period`() {
        val a = open()
        a.tap("Smart typing")
        for (label in listOf("SUGGESTIONS", "As you type, and the next word", "CORRECTIONS", "CAPITALS AND SPACING",
            "Double-space for a period", "0 apps")) {
            assertNotNull("missing $label", a.text(label))
        }
        a.tap("Suggestions")
        assertFalse(a.stored().suggestions)
        // Suggest an emoji follows the strip, greyed in place like fixing typos.
        val row = a.text("Suggest an emoji")!!.parent.parent as ViewGroup
        val switch = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<android.widget.Switch>().single()
        assertFalse(switch.isEnabled)
        assertFalse(row.isEnabled)
    }

    @Test
    fun `what's new shows five features, then the rest a row away`() {
        val a = open()
        a.openWhatsNew("0.3.1")
        assertNull("a sheet has no back link", a.text("‹ Keyd"))
        for (label in listOf("What's New in Keyd", "Version 0.3.1", "Languages", "Period symbols", "Two fingers to undo",
            "Emoji in the strip", "Gesture tips", "2 more new features", "Fixes and improvements", "3",
            "Earlier versions", "Continue")) {
            assertNotNull("missing $label", a.text(label))
        }
        assertNull(a.text("Delete forward"))
        // The rest are named as the changelog writes them, not lowercased after the first.
        assertNotNull(a.text("Hold delay and backspace speed, Delete forward"))
        // Continue is at the end of the page, not floating over it.
        val page = a.all().filterIsInstance<ScrollView>().first()
        val texts = mutableListOf<String>()
        fun walk(v: View) { if (v is TextView) texts += v.text.toString(); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(page)
        assertEquals("Continue", texts.last { it.isNotBlank() })
        a.tap("2 more new features")
        for (label in listOf("Version 0.3.1", "MORE NEW FEATURES", "CHANGED", "FIXED")) {
            assertNotNull("missing $label", a.text(label))
        }
        assertTrue(a.texts().any { it.startsWith("Delete forward: ") })
        a.tap("Earlier versions")
        assertNotNull(a.text("Version 0.3.0"))
        assertNull(a.text("Version 0.3.1"))
    }

    @Test
    fun `this build's what's new opens from About, and continue goes back to the first page`() {
        val a = open()
        a.tap("What's new")
        assertNotNull(a.text("What's New in Keyd"))
        a.tap("Continue")
        assertNotNull(a.text("Search settings"))
    }

    @Test
    fun `in light mode the bar icons are dark, drawn for the light page`() {
        val a = open()
        val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        assertEquals(light, a.window.insetsController!!.systemBarsAppearance and light)
    }

    @Test
    @Config(qualifiers = "night")
    fun `in dark mode the bar icons stay light`() {
        val a = open()
        val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        assertEquals(0, a.window.insetsController!!.systemBarsAppearance and light)
    }

    @Test
    fun `the page keeps clear of the bars and a camera cutout`() {
        val a = open()
        val page = a.all().filterIsInstance<ScrollView>().first()
        val insets = android.view.WindowInsets.Builder()
            .setInsets(android.view.WindowInsets.Type.systemBars(), android.graphics.Insets.of(0, 60, 0, 40))
            .setInsets(android.view.WindowInsets.Type.displayCutout(), android.graphics.Insets.of(90, 60, 0, 0))
            .build()
        page.dispatchApplyWindowInsets(insets)
        assertEquals(90, page.paddingLeft)
        assertEquals(60, page.paddingTop)
        assertEquals(0, page.paddingRight)
        assertEquals(40, page.paddingBottom)
        assertFalse(page.fitsSystemWindows)
    }

    // ---- Pictures ------------------------------------------------------------------------------------------------

    /** The page as it is drawn, whole, however long it scrolls, into build/renders/[name].png. */
    private fun SettingsActivity.renderPage(name: String) {
        val scroll = all().filterIsInstance<ScrollView>().first()
        val column = scroll.getChildAt(0)
        val width = scroll.width.takeIf { it > 0 } ?: (411 * resources.displayMetrics.density).toInt()
        column.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        column.layout(0, 0, column.measuredWidth, column.measuredHeight)
        val bitmap = Bitmap.createBitmap(column.measuredWidth, column.measuredHeight, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).also { canvas ->
            window.decorView.background?.let { it.setBounds(0, 0, bitmap.width, bitmap.height); it.draw(canvas) }
            column.draw(canvas)
        }
        val out = File("build/renders").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("rendered $name -> ${out.absolutePath}")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun `settings, as a phone would draw them`() {
        val a = open()
        a.renderPage("settings-main-dark")
        a.tap("Style and layout")
        a.renderPage("settings-style-dark")
        a.tap("‹ Keyd")
        a.tap("Sound and vibration")
        a.renderPage("settings-feel-dark")
        a.tap("‹ Keyd")
        a.tap("Keys and gestures")
        a.renderPage("settings-keys-dark")
        a.tap("‹ Keyd")
        a.tap("Smart typing")
        a.renderPage("settings-typing-dark")
        a.tap("‹ Keyd")
        a.tap("Search settings")
        a.all().filterIsInstance<EditText>().single().setText("vib")
        a.renderPage("settings-search-dark")
        a.openWhatsNew("0.3.1")
        a.renderPage("whats-new-dark")
        a.tap("2 more new features")
        a.renderPage("whats-new-changes-dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-notnight-xhdpi")
    fun `settings in light mode, as a phone would draw them`() {
        val a = open()
        a.renderPage("settings-main-light")
        a.tap("Style and layout")
        a.renderPage("settings-style-light")
        a.openWhatsNew("0.3.1")
        a.renderPage("whats-new-light")
    }
}
