package com.mccal.folio.keys

import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Holding the globe for a list of Keyd's languages, with a tap on it still going to the next keyboard. Real touches,
 * as in [KeyboardViewTest], and the same list through a screen reader.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class GlobeLanguagesTest {

    private val t = Touches()
    private val view get() = t.view
    private fun globe() = t.kind(KeyKind.GLOBE)
    private fun item(label: String) = view.languageMenuItems.first { it.key.label == label }
    private fun labels() = view.languageMenuItems.map { it.key.label }

    @Test
    fun `a tap on the globe still goes to the next keyboard`() {
        t.tap(globe().box)
        assertEquals(listOf("switch"), t.heard)
        assertTrue(view.languageMenuItems.isEmpty())
    }

    @Test
    fun `holding it lists the languages turned on, then the two ways out`() {
        t.hold(globe().box)
        assertEquals(listOf("English", "Español", "Français", "Other keyboards…", "Language settings…"), labels())
        val (x, y) = t.centre(globe().box)
        t.send(MotionEvent.ACTION_UP, x, y)
        assertFalse("holding it is not also a tap", "switch" in t.heard)
        assertEquals("letting go on the globe leaves the list open to tap", 5, labels().size)
    }

    @Test
    fun `the list sits above the globe, inside the keyboard`() {
        t.hold(globe().box)
        for (placement in view.languageMenuItems) {
            assertTrue(placement.box.bottom <= globe().box.top)
            assertTrue(placement.box.top >= 0f)
            assertTrue(placement.box.height / t.density >= 24f)
        }
    }

    @Test
    fun `sliding up to a language and letting go switches to it`() {
        t.hold(globe().box)
        val (x, y) = t.centre(item("Español").box)
        t.send(MotionEvent.ACTION_MOVE, x, y)
        t.send(MotionEvent.ACTION_UP, x, y)
        assertEquals(listOf("language:SPANISH"), t.heard)
        assertTrue("the list goes once something is picked", view.languageMenuItems.isEmpty())
    }

    @Test
    fun `a list left open is tapped like keys`() {
        t.hold(globe().box)
        val (gx, gy) = t.centre(globe().box)
        t.send(MotionEvent.ACTION_UP, gx, gy)
        t.tap(item("Other keyboards…").box)
        assertEquals(listOf("otherKeyboards"), t.heard)
        t.hold(globe().box)
        t.send(MotionEvent.ACTION_UP, gx, gy)
        t.tap(item("Language settings…").box)
        assertEquals(listOf("otherKeyboards", "languageSettings"), t.heard)
    }

    @Test
    fun `a touch outside the list only closes it`() {
        t.hold(globe().box)
        val (gx, gy) = t.centre(globe().box)
        t.send(MotionEvent.ACTION_UP, gx, gy)
        t.tap(t.key("p").box)
        assertTrue(view.languageMenuItems.isEmpty())
        assertEquals("the key under it is not typed", "", t.typed.toString())
        t.tap(t.key("p").box)
        assertEquals("p", t.typed.toString())
    }

    @Test
    fun `a new field closes it`() {
        t.hold(globe().box)
        view.forgetTouches()
        assertTrue(view.languageMenuItems.isEmpty())
    }

    @Test
    fun `with nothing from Android it still offers the language in use`() {
        t.languages = emptyList()
        view.language = Language.GERMAN
        t.hold(globe().box)
        assertEquals(listOf("Deutsch", "Other keyboards…", "Language settings…"), labels())
    }

    @Test
    fun `the hold delay decides when it opens`() {
        t.show(settings = Settings(holdDelay = HoldDelay.SHORTER))
        t.hold(globe().box, ms = 300)
        assertEquals(5, labels().size)
    }

    // ---- screen readers ------------------------------------------------------------------------------------------

    @Test
    fun `a screen reader holds the globe and picks from the list`() {
        val provider = view.accessibilityNodeProvider!!
        val globeId = view.placements.indexOfFirst { it.key.kind == KeyKind.GLOBE }
        val node = provider.createAccessibilityNodeInfo(globeId)!!
        assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK })
        assertTrue(provider.performAction(globeId, AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
        assertEquals(5, labels().size)

        val first = view.placements.size + view.toolbarPlacements.size
        val english = provider.createAccessibilityNodeInfo(first)!!
        assertEquals("English", english.contentDescription)
        assertTrue(english.isCheckable)
        assertTrue("the language in use is ticked", english.isChecked)
        assertFalse(provider.createAccessibilityNodeInfo(first + 1)!!.isChecked)
        assertEquals("Other keyboards", provider.createAccessibilityNodeInfo(first + 3)!!.contentDescription)

        provider.performAction(first + 1, AccessibilityNodeInfo.ACTION_CLICK, null)
        assertEquals(listOf("language:SPANISH"), t.heard)
    }

    @Test
    fun `the keys under the open list are hidden from a screen reader`() {
        t.hold(globe().box)
        val covered = view.placements.indexOfFirst { it.key.label == "c" }
        val ids = mutableListOf<Int>()
        val helper = org.robolectric.util.ReflectionHelpers.getField<androidx.customview.widget.ExploreByTouchHelper>(view, "keyNodes")
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(
            helper, "getVisibleVirtualViews",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(MutableList::class.java, ids),
        )
        assertFalse("c is under the list", covered in ids)
        val first = view.placements.size + view.toolbarPlacements.size
        assertTrue((first until first + 5).all { it in ids })
    }

    @Test
    fun `the globe says it can be held`() {
        assertEquals("Switch keyboard, hold for languages", Spoken.name(Key("🌐", KeyKind.GLOBE), Shift.OFF))
    }
}
