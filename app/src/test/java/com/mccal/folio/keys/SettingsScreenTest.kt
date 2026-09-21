package com.mccal.folio.keys

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
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

    private fun open(): SettingsActivity {
        androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("keys", Context.MODE_PRIVATE).edit().clear().commit()
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
            R.string.settings_vibrate, R.string.settings_high_contrast, R.string.settings_clipboard,
            R.string.settings_size_small, R.string.settings_appearance_dark, R.string.settings_split_never,
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
}
