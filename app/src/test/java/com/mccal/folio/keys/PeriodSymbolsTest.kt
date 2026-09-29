package com.mccal.folio.keys

import android.content.Context
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Holding the period for a row of symbols, the way a letter's accents open, and the setting that says which.
 * Also how long any hold takes, and how fast a held backspace goes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class PeriodSymbolsTest {

    private val t = Touches()
    private val view get() = t.view
    private fun period() = view.placements.first { it.key.holdsSymbols }

    @Test
    fun `holding the period opens the usual symbols, and letting go takes the first`() {
        val (x, y) = t.hold(period().box)
        assertEquals(listOf(",", "?", "!", "'", "\"", ":", ";", "-"), view.popupItems)
        t.send(MotionEvent.ACTION_UP, x, y)
        assertEquals(",", t.typed.toString())
    }

    @Test
    fun `sliding along the row picks another`() {
        val (x, y) = t.hold(period().box)
        val question = view.popupBoxes[1]
        t.send(MotionEvent.ACTION_MOVE, (question.left + question.right) / 2, y)
        t.send(MotionEvent.ACTION_UP, (question.left + question.right) / 2, y)
        assertEquals("?", t.typed.toString())
    }

    @Test
    fun `a tap is still a period`() {
        t.tap(period().box)
        t.idle(800)
        assertEquals(".", t.typed.toString())
    }

    @Test
    fun `the row is whatever was chosen, and nothing when nothing was`() {
        t.show(settings = Settings(periodSymbols = "!?😀"))
        t.hold(period().box)
        assertEquals(listOf("!", "?", "😀"), view.popupItems)
        view.forgetTouches()

        t.show(settings = Settings(periodSymbols = ""))
        val (x, y) = t.hold(period().box)
        assertTrue(view.popupItems.isEmpty())
        t.send(MotionEvent.ACTION_UP, x, y)
        assertEquals("held with nothing chosen, it is a period", ".", t.typed.toString())
    }

    @Test
    fun `one symbol is typed straight away, as one accent is`() {
        t.show(settings = Settings(periodSymbols = "!"))
        val (x, y) = t.hold(period().box)
        t.send(MotionEvent.ACTION_UP, x, y)
        assertEquals("!", t.typed.toString())
    }

    @Test
    fun `only the period beside the space bar holds symbols`() {
        assertEquals(1, Layouts.rows(Layer.LETTERS, false, FieldRules()).flatten().count { it.holdsSymbols })
        for (layer in listOf(Layer.NUMBERS, Layer.SYMBOLS)) {
            assertTrue(Layouts.rows(layer, false, FieldRules()).flatten().none { it.holdsSymbols })
        }
        t.show(layer = Layer.NUMBERS)
        val (x, y) = t.hold(t.key(".").box)
        assertTrue(view.popupItems.isEmpty())
        t.send(MotionEvent.ACTION_UP, x, y)
        assertEquals(".", t.typed.toString())
    }

    @Test
    fun `a screen reader holds the period and picks from the row`() {
        val provider = view.accessibilityNodeProvider!!
        val id = view.placements.indexOfFirst { it.key.holdsSymbols }
        assertTrue(provider.createAccessibilityNodeInfo(id)!!.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK })
        provider.performAction(id, AccessibilityNodeInfo.ACTION_LONG_CLICK, null)
        assertEquals(8, view.popupItems.size)
        val first = view.placements.size + view.toolbarPlacements.size
        assertEquals("?", provider.createAccessibilityNodeInfo(first + 1)!!.contentDescription)
        provider.performAction(first + 1, AccessibilityNodeInfo.ACTION_CLICK, null)
        assertEquals("?", t.typed.toString())
        assertTrue("the row goes once one is picked", view.popupItems.isEmpty())
    }

    @Test
    fun `a letter's accents can be reached by a screen reader too`() {
        val provider = view.accessibilityNodeProvider!!
        val e = view.placements.indexOfFirst { it.key.label == "e" }
        provider.performAction(e, AccessibilityNodeInfo.ACTION_LONG_CLICK, null)
        assertEquals("3", view.popupItems.first())
    }

    // ---- the setting ---------------------------------------------------------------------------------------------

    @Test
    fun `what is typed is kept tidy`() {
        assertEquals(",?!", Settings.periodSymbols(" , ? ,! ?"))
        assertEquals("12345678", Settings.periodSymbols("1234567890"))
        assertEquals("😀!", Settings.periodSymbols("😀 😀!"))
        assertEquals("", Settings.periodSymbols("   "))
        assertEquals(listOf("😀", "!"), Settings.symbolList("😀!"))
    }

    @Test
    fun `the symbols are kept, empty included`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("period", Context.MODE_PRIVATE)
        assertEquals(",?!'\":;-", Settings.load(prefs).periodSymbols)
        Settings(periodSymbols = "@#").save(prefs)
        assertEquals("@#", Settings.load(prefs).periodSymbols)
        Settings(periodSymbols = "").save(prefs)
        assertEquals("empty means none, not the usual ones", "", Settings.load(prefs).periodSymbols)
    }

    // ---- hold delay and backspace speed --------------------------------------------------------------------------

    @Test
    fun `a shorter hold opens the accents sooner, and a longer one later`() {
        t.show(settings = Settings(holdDelay = HoldDelay.SHORTER))
        t.hold(t.key("e").box, ms = 300)
        assertTrue(view.popupItems.isNotEmpty())
        view.forgetTouches()

        t.show(settings = Settings(holdDelay = HoldDelay.LONGER))
        t.hold(t.key("e").box, ms = 550)
        assertTrue("not yet", view.popupItems.isEmpty())
        t.idle(100)
        assertTrue(view.popupItems.isNotEmpty())
    }

    @Test
    fun `follow phone is Android's own touch and hold delay`() {
        assertEquals(
            android.view.ViewConfiguration.getLongPressTimeout().toLong(), HoldDelay.FOLLOW_PHONE.millis,
        )
        assertEquals(HoldDelay.FOLLOW_PHONE, Settings().holdDelay)
    }

    private fun repeatsIn(speed: BackspaceSpeed, ms: Long): Int {
        t.heard.clear()
        t.show(settings = Settings(backspaceSpeed = speed))
        val (x, y) = t.hold(t.kind(KeyKind.BACKSPACE).box, ms)
        t.send(MotionEvent.ACTION_UP, x, y)
        return t.heard.count { it == "repeat" }
    }

    @Test
    fun `a held backspace goes at the speed chosen, after the same wait`() {
        // 400 ms before the first, then one every 90, 55 or 35.
        assertEquals(0, repeatsIn(BackspaceSpeed.FASTER, 390))
        assertEquals(1 + 900 / 90, repeatsIn(BackspaceSpeed.SLOWER, 1300))
        assertEquals(1 + 900 / 55, repeatsIn(BackspaceSpeed.NORMAL, 1300))
        assertEquals(1 + 900 / 35, repeatsIn(BackspaceSpeed.FASTER, 1300))
    }

    @Test
    fun `both are kept`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("timing", Context.MODE_PRIVATE)
        Settings(holdDelay = HoldDelay.LONGER, backspaceSpeed = BackspaceSpeed.SLOWER).save(prefs)
        val back = Settings.load(prefs)
        assertEquals(HoldDelay.LONGER, back.holdDelay)
        assertEquals(BackspaceSpeed.SLOWER, back.backspaceSpeed)
    }

    @Test
    fun `an emoji with its variation, skin tone or flag is one symbol`() {
        assertEquals(listOf("❤️", "👍🏽", "🇫🇷", "?"), Settings.symbolList(Settings.periodSymbols("❤️👍🏽🇫🇷?")))
        assertEquals("a second flag is not taken apart by the repeats rule", listOf("🇫🇷", "🇩🇪"),
            Settings.symbolList(Settings.periodSymbols("🇫🇷🇩🇪🇫🇷")))
    }
}
