package com.mccal.folio.keys

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What 0.3.1 adds to Settings: the period's symbols, the hold delay and backspace speed, two fingers to undo, and a
 * Languages page. Driven by taps, as in [SettingsScreenTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class KeysSettingsTest {

    private fun open(intent: android.content.Intent? = null): SettingsActivity {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("keys", Context.MODE_PRIVATE).edit().clear().commit()
        val controller = if (intent == null) Robolectric.buildActivity(SettingsActivity::class.java)
            else Robolectric.buildActivity(SettingsActivity::class.java, intent)
        return controller.setup().get()
    }

    private fun SettingsActivity.all(): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) { out += v; if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(window.decorView)
        return out
    }

    private fun SettingsActivity.text(label: String): TextView? =
        all().filterIsInstance<TextView>().firstOrNull { it.text.toString() == label }

    private fun SettingsActivity.tap(label: String) {
        var target: View = text(label) ?: throw AssertionError("no \"$label\" on screen")
        while (!target.isClickable && target.parent is View) target = target.parent as View
        target.performClick()
    }

    private fun SettingsActivity.stored() = Settings.load(getSharedPreferences("keys", Context.MODE_PRIVATE))

    private fun keys() = open().apply { tap("Keys and gestures") }

    @Test
    fun `keys and gestures shows the new rows in their groups`() {
        val a = keys()
        for (label in listOf(
            "Hold the period for", ", ? ! ' \" : ; -", "HOLD DELAY", "Follow phone", "Shorter", "Longer",
            "BACKSPACE SPEED", "Slower", "Normal", "Faster", "Two fingers to undo", "Swipe left to undo, right to redo",
            "Holding shift while you press backspace deletes the letter after the cursor instead.",
            "How fast a held backspace keeps deleting.",
        )) assertNotNull("missing $label", a.text(label))
    }

    @Test
    fun `the hold delay and backspace speed save`() {
        val a = keys()
        a.tap("Shorter")
        a.tap("Faster")
        assertEquals(HoldDelay.SHORTER, a.stored().holdDelay)
        assertEquals(BackspaceSpeed.FASTER, a.stored().backspaceSpeed)
        a.tap("Follow phone")
        assertEquals(HoldDelay.FOLLOW_PHONE, a.stored().holdDelay)
    }

    @Test
    fun `two fingers to undo can be turned off`() {
        val a = keys()
        assertTrue(a.stored().twoFingerUndo)
        a.tap("Two fingers to undo")
        assertFalse(a.stored().twoFingerUndo)
    }

    @Test
    fun `the period's symbols are typed, tidied and saved, and can go back`() {
        val a = keys()
        a.tap("Hold the period for")
        assertNotNull(a.text("Period symbols"))
        assertNotNull(a.text("Up to 8, in the order you type them here. Leave it empty and holding the period does nothing."))
        val field = a.all().filterIsInstance<EditText>().single()
        assertEquals(",?!'\":;-", field.text.toString())
        field.setText("! ? !  @")
        assertEquals("!?@", a.stored().periodSymbols)
        val preview = a.all().filterIsInstance<TextView>().first { it.contentDescription?.startsWith("Holding the period") == true }
        assertEquals("Holding the period shows ! ? @", preview.contentDescription)

        field.setText("")
        assertEquals("", a.stored().periodSymbols)
        a.tap("Back to the usual symbols")
        assertEquals(Settings.DEFAULT_PERIOD_SYMBOLS, a.stored().periodSymbols)

        a.tap("‹ Keys and gestures")
        assertNotNull("the row shows what is chosen", a.text(", ? ! ' \" : ; -"))
    }

    @Test
    fun `nothing chosen shows as off`() {
        val a = keys()
        a.tap("Hold the period for")
        a.all().filterIsInstance<EditText>().single().setText("")
        a.tap("‹ Keys and gestures")
        val row = a.text("Hold the period for")!!
        assertTrue(generateSequence(row as View) { it.parent as? View }.any { it.contentDescription == "Hold the period for, Off" })
    }

    @Test
    fun `languages is a page of its own`() {
        val a = open()
        a.tap("Languages")
        assertNotNull(a.text("Hold the globe to pick one of these. Tapping it still goes to your next keyboard."))
        assertNotNull(a.text("Turn languages on or off…"))
        assertNotNull(a.text("Opens Android’s settings for Keyd, where languages are turned on."))
        // Nothing is turned on in a test, so every language is one Keyd also has.
        assertNotNull(a.text("ALSO IN KEYD"))
        for (language in Language.entries) assertNotNull(language.ownName, a.text(language.ownName))
    }

    @Test
    fun `the keyboard's Language settings opens straight on the page`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val a = open(languageSettings(context))
        assertNotNull(a.text("Turn languages on or off…"))
        assertNotNull("and back goes to the first page", a.text("‹ Keyd"))
    }

    @Test
    fun `no other page can be asked for from outside`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val intent = android.content.Intent(context, SettingsActivity::class.java)
            .putExtra("com.mccal.folio.keys.PAGE", "DEVELOPER")
        val a = open(intent)
        assertNotNull(a.text("Keys and gestures"))
    }

    @Test
    fun `the Developer page has a field to try Keyd in, that never teaches it a word`() {
        val a = open()
        a.tap("Developer")
        val field = a.all().filterIsInstance<android.widget.EditText>().first { it.hint == "Type to test Keyd" }
        assertTrue(field.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        a.tap("Web address")
        assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_URI, field.inputType and android.text.InputType.TYPE_MASK_VARIATION)
        assertEquals(android.view.inputmethod.EditorInfo.IME_ACTION_GO, field.imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION)
        assertTrue("still no learning", field.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }
}
