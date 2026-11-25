package com.mccal.folio.keys

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where the globe key is, when it shows, and what the space bar does with and without it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlobeKeyTest {
    private fun bottom(globeKey: Boolean = true, layer: Layer = Layer.LETTERS) =
        Layouts.rows(layer, false, FieldRules(), globeKey = globeKey).last()

    @Test fun `123 is in the corner and the globe is right beside it`() {
        for (layer in Layer.values()) {
            val row = bottom(true, layer)
            assertEquals(KeyKind.LAYER, row.first().kind)
            assertEquals(KeyKind.GLOBE, row[1].kind)
        }
    }

    @Test fun `with the globe off there is none on any layer, and the space bar is wider`() {
        for (layer in Layer.values()) assertFalse(bottom(false, layer).any { it.kind == KeyKind.GLOBE })
        assertTrue(bottom(false).first { it.kind == KeyKind.SPACE }.weight > bottom(true).first { it.kind == KeyKind.SPACE }.weight)
        assertEquals(KeyKind.LAYER, bottom(false).first().kind)
    }

    @Test fun `the setting is on by default and survives saving`() {
        assertTrue(Settings().globeKey)
        val prefs = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("globe-test", android.content.Context.MODE_PRIVATE)
        Settings().copy(globeKey = false).save(prefs)
        assertFalse(Settings.load(prefs).globeKey)
    }

    @Test fun `holding the space bar lists the languages, with the globe or without`() {
        for (globe in listOf(true, false)) {
            val t = Touches()
            t.show(settings = Settings(globeKey = globe))
            val (x, y) = t.hold(t.kind(KeyKind.SPACE).box)
            assertEquals(listOf("English", "Español", "Français", "Other keyboards…", "Language settings…"), t.view.languageMenuItems.map { it.key.label })
            t.send(MotionEvent.ACTION_UP, x, y)
            assertEquals("holding is not also a space", "", t.typed.toString())
        }
    }

    @Test fun `a tap on the space bar still types a space, with or without the globe`() {
        for (globe in listOf(true, false)) {
            val t = Touches()
            t.show(settings = Settings(globeKey = globe))
            t.tap(t.kind(KeyKind.SPACE).box)
            assertEquals(" ", t.typed.toString())
            assertTrue(t.view.languageMenuItems.isEmpty())
        }
    }

    @Test fun `a screen reader can hold the space bar for the language list, with the globe or without`() {
        for (globe in listOf(true, false)) {
            val t = Touches()
            t.show(settings = Settings(globeKey = globe))
            val provider = t.view.accessibilityNodeProvider!!
            val id = t.view.placements.indexOfFirst { it.key.kind == KeyKind.SPACE }
            val node = provider.createAccessibilityNodeInfo(id)!!
            assertTrue(node.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK })
            assertTrue(provider.performAction(id, android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
            assertEquals(5, t.view.languageMenuItems.size)
        }
    }

    @Test fun `a swipe along the space bar moves the cursor and does not open the list`() {
        val t = Touches()
        t.show(settings = Settings(globeKey = true))
        val box = t.kind(KeyKind.SPACE).box
        val y = (box.top + box.bottom) / 2
        t.send(MotionEvent.ACTION_DOWN, box.left + 40f, y)
        t.send(MotionEvent.ACTION_MOVE, box.left + 140f, y)
        t.send(MotionEvent.ACTION_UP, box.left + 140f, y)
        assertTrue(t.view.languageMenuItems.isEmpty())
    }
}
