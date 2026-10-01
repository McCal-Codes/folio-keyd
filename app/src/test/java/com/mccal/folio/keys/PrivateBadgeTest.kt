package com.mccal.folio.keys

import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Not learning here" at the left of the toolbar, or just the eye at the left of the strip, in a field Keyd keeps
 * nothing from: which fields get it, and that it never takes a suggestion's place.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // real text widths, since how much room the words need is the point
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class PrivateBadgeTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("keys", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    private fun badge(view: KeyboardView) = view.toolbarPlacements.singleOrNull { it.key.kind == KeyKind.PRIVATE }

    // ---- in the keyboard --------------------------------------------------------------------------------------------

    @Test
    fun `the toolbar says so at its start and keeps the buttons at its end`() {
        val t = Touches()
        val usual = t.view.toolbarPlacements.map { it.key.kind }
        t.view.notLearning = true
        val tools = t.view.toolbarPlacements
        assertEquals(KeyKind.PRIVATE, tools.first().key.kind)
        assertEquals("Not learning here", tools.first().key.label)
        // In place of Hide and the first button; the ones at the end stay where they were.
        assertEquals(usual.drop(2), tools.drop(1).map { it.key.kind })
        assertTrue(tools.drop(1).all { it.box.left >= tools.first().box.right })
        assertTrue("room for the words: ${tools.first().box.width / t.density}", tools.first().box.width / t.density >= 140f)
        assertTrue("the buttons stay a fingertip wide", tools.drop(1).all { it.box.width / t.density >= 48f })
    }

    @Test
    fun `with words in the strip it is only the eye, and every word keeps its place`() {
        val t = Touches()
        t.view.voiceAvailable = true
        t.view.suggestions = listOf("Folo", "Folio", "Foloo")
        val before = t.view.toolbarPlacements.map { it.key.kind }
        val micBefore = t.view.toolbarPlacements.first { it.key.kind == KeyKind.VOICE }.box
        t.view.notLearning = true
        val strip = t.view.toolbarPlacements
        val eye = badge(t.view)!!
        assertEquals("the typed word is still first", "Folo", strip.first().key.label)
        assertEquals(before, strip.map { it.key.kind }.filter { it != KeyKind.PRIVATE })
        assertTrue(strip.filter { it.key.kind == KeyKind.SUGGESTION }.all { it.box.left >= eye.box.right })
        assertTrue("an icon's width, not a word's", eye.box.width / t.density <= 40f)
        val mic = strip.first { it.key.kind == KeyKind.VOICE }
        assertEquals("the mic keeps its place", micBefore, mic.box)
    }

    @Test
    fun `a screen reader reads it and cannot press it`() {
        val t = Touches()
        t.view.suggestions = listOf("Folo", "Folio")
        t.view.notLearning = true
        val provider = t.view.accessibilityNodeProvider!!
        val id = t.view.placements.size + t.view.toolbarPlacements.indexOfFirst { it.key.kind == KeyKind.PRIVATE }
        val node = provider.createAccessibilityNodeInfo(id)!!
        assertEquals("Not learning here", node.contentDescription)
        assertEquals("android.widget.TextView", node.className)
        assertFalse(node.isClickable)
    }

    @Test
    fun `a screen reader reads the strip left to right, the eye first`() {
        val t = Touches()
        t.view.suggestions = listOf("Folo", "Folio")
        t.view.notLearning = true
        val ids = mutableListOf<Int>()
        val helper = org.robolectric.util.ReflectionHelpers.getField<androidx.customview.widget.ExploreByTouchHelper>(t.view, "keyNodes")
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(
            helper, "getVisibleVirtualViews",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(MutableList::class.java, ids),
        )
        val keys = t.view.placements.size
        val tools = t.view.toolbarPlacements
        val read = ids.filter { it in keys until keys + tools.size }.map { tools[it - keys] }
        assertEquals(KeyKind.PRIVATE, read.first().key.kind)
        assertEquals("Folo", read[1].key.label)
        assertEquals(read.sortedBy { it.box.left }, read)
        assertEquals("the typed word keeps its place in the list", "Folo", tools.first().key.label)
    }

    @Test
    fun `an ordinary keyboard has none`() {
        val t = Touches()
        assertEquals(null, badge(t.view))
        t.view.suggestions = listOf("Folo", "Folio")
        assertEquals(null, badge(t.view))
    }

    // ---- which fields ------------------------------------------------------------------------------------------------

    private fun KeysService.settle() = repeat(3) {
        shadowOf(suggestionLooper).idle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun opened(app: String, inputType: Int = InputType.TYPE_CLASS_TEXT, imeOptions: Int = 0): KeyboardView {
        val service = Robolectric.buildService(KeysService::class.java).create().get()
        val root = service.onCreateInputView() as ViewGroup
        val keys = (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<KeyboardView>().single()
        service.onStartInputView(
            EditorInfo().also {
                it.packageName = app
                it.inputType = inputType
                it.imeOptions = imeOptions
            },
            false,
        )
        service.settle()
        return keys
    }

    @Test
    fun `a field that asks not to be learned from says so`() {
        assertTrue(opened("com.browser", imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING).notLearning)
    }

    @Test
    fun `a password field says so`() {
        assertTrue(opened("com.bank", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).notLearning)
    }

    @Test
    fun `an ordinary field does not`() {
        assertFalse(opened("com.chat").notLearning)
    }

    @Test
    fun `an app with learning turned off says so, and only that app`() {
        val apps = AppProfiles.load(prefs)
        apps.set("com.diary", AppProfiles.Profile(useUsual = false, learn = false))
        apps.save(prefs)
        assertTrue(opened("com.diary").notLearning)
        assertFalse(opened("com.chat").notLearning)
    }

    @Test
    fun `learning off everywhere is not news in any one field`() {
        Settings(learn = false).save(prefs)
        assertFalse(opened("com.chat").notLearning)
    }
}
