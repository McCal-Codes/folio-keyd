package com.mccal.folio.keys

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
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

/** The Editing group on Keys and gestures, and the Toolbar page it leads to, driven by taps as in [SettingsScreenTest]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ToolbarSettingsTest {

    private fun open(): SettingsActivity {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("keys", Context.MODE_PRIVATE).edit().clear().commit()
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

    private fun SettingsActivity.tap(label: String) {
        var target: View = text(label) ?: throw AssertionError("no \"$label\" on screen")
        while (!target.isClickable && target.parent is View) target = target.parent as View
        target.performClick()
    }

    /** A button found by the name TalkBack reads for it. */
    private fun SettingsActivity.named(name: String): View =
        all().firstOrNull { it.contentDescription?.toString() == name } ?: throw AssertionError("nothing named \"$name\"")

    private fun SettingsActivity.switchIn(label: String): Switch {
        val row = text(label)!!.parent as ViewGroup
        return (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<Switch>().single()
    }

    private fun SettingsActivity.stored() = Settings.load(getSharedPreferences("keys", Context.MODE_PRIVATE))

    private fun openToolbar() = open().apply {
        tap("Keys and gestures")
        tap("Toolbar")
    }

    @Test
    fun `keys and gestures has an editing group, and its switches save`() {
        val a = open()
        a.tap("Keys and gestures")
        assertNotNull(a.text("EDITING"))
        assertNotNull(a.text("Undo, cut, copy, paste, select all"))
        assertNotNull(a.text("7 buttons"))
        assertNotNull(a.text("On those five keys a swipe up edits instead of typing a capital. Turn it off to get the capital back."))
        a.tap("Swipe up on Z X C V A to edit")
        a.tap("Slide from shift to select")
        a.tap("Word count and styles when text is selected")
        val stored = a.stored()
        assertFalse(stored.editSwipes)
        assertFalse(stored.shiftSelect)
        assertFalse(stored.selectionTools)
        assertFalse(a.switchIn("Slide from shift to select").isChecked)
    }

    @Test
    fun `the toolbar page lists the buttons in order, Hide first, and the rest below`() {
        val a = openToolbar()
        assertNotNull(a.text("ON THE TOOLBAR"))
        assertNotNull(a.text("Always first"))
        assertNotNull(a.text("MORE BUTTONS"))
        assertNotNull(a.text("Back to the usual buttons"))
        val order = a.all().filterIsInstance<TextView>().map { it.text.toString() }
            .filter { it in listOf("Emoji", "Undo", "Cursor pad", "Copy", "Paste", "Clipboard", "Voice", "Redo", "Cut", "Select all") }
        assertEquals(listOf("Emoji", "Undo", "Cursor pad", "Copy", "Paste", "Clipboard", "Voice", "Redo", "Select all", "Cut").sorted(), order.sorted())
        // On the toolbar first, in their order; then the ones that could be added.
        assertEquals(listOf("Emoji", "Undo", "Cursor pad", "Copy", "Paste", "Clipboard", "Voice"), order.take(7))
        assertTrue(a.switchIn("Copy").isChecked)
        assertFalse(a.switchIn("Redo").isChecked)
        assertTrue(a.named("Toolbar preview: Hide, Emoji, Undo, Cursor pad, Copy, Paste, Clipboard, Voice").isShown)
    }

    @Test
    fun `move up and move down reorder, and the ends can't be passed`() {
        val a = openToolbar()
        a.named("Move Copy up").performClick()
        assertEquals(
            listOf(ToolKey.EMOJI, ToolKey.UNDO, ToolKey.COPY, ToolKey.CURSOR_PAD, ToolKey.PASTE, ToolKey.CLIPBOARD, ToolKey.VOICE),
            a.stored().toolbar,
        )
        a.named("Move Emoji down").performClick()
        assertEquals(ToolKey.UNDO, a.stored().toolbar.first())
        assertFalse(a.named("Move Undo up").isEnabled)
        assertFalse(a.named("Move Voice down").isEnabled)
    }

    @Test
    fun `a button comes off with its switch and goes back on at the end, up to seven`() {
        val a = openToolbar()
        // Full at seven: the others can't be added until one comes off.
        assertFalse(a.switchIn("Redo").isEnabled)
        a.tap("Undo")
        assertFalse(ToolKey.UNDO in a.stored().toolbar)
        assertTrue(a.switchIn("Redo").isEnabled)
        a.tap("Redo")
        assertEquals(ToolKey.REDO, a.stored().toolbar.last())
        assertEquals(7, a.stored().toolbar.size)
    }

    @Test
    fun `back to the usual buttons puts the default back`() {
        val a = openToolbar()
        a.tap("Voice")
        a.named("Move Copy up").performClick()
        a.tap("Back to the usual buttons")
        assertEquals(Settings.DEFAULT_TOOLBAR, a.stored().toolbar)
    }

    @Test
    fun `the keys page counts the buttons`() {
        val a = openToolbar()
        a.tap("Voice")
        a.tap("Clipboard")
        a.tap("‹ Keys and gestures")
        assertNotNull(a.text("5 buttons"))
    }
}
