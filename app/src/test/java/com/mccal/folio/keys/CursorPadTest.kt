package com.mccal.folio.keys

import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The cursor pad, laid out and pressed.
 *
 * What matters is that each key does the one thing it shows, that Select really latches (and really lets go when a
 * field opens), and that holding an arrow keeps going until the finger lifts and not a step after.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class CursorPadTest {

    private lateinit var pad: CursorPad
    private val calls = mutableListOf<String>()

    @Before
    fun setUp() {
        pad = CursorPad(ApplicationProvider.getApplicationContext<Context>())
        pad.listener = object : CursorPad.Listener {
            override fun onMove(move: CursorPad.Move, selecting: Boolean) { calls += "$move:$selecting" }
            override fun onSelectAll() { calls += "selectAll" }
            override fun onCut() { calls += "cut" }
            override fun onBackspace() { calls += "backspace" }
            override fun onLetters() { calls += "letters" }
        }
        val width = (411 * pad.resources.displayMetrics.density).toInt()
        pad.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        pad.layout(0, 0, pad.measuredWidth, pad.measuredHeight)
    }

    private fun send(action: Int, index: Int) {
        val box = pad.keyBounds(index)
        val event = MotionEvent.obtain(0, 0, action, box.centerX(), box.centerY(), 0)
        pad.onTouchEvent(event)
        event.recycle()
    }

    private fun tap(index: Int) {
        send(MotionEvent.ACTION_DOWN, index)
        send(MotionEvent.ACTION_UP, index)
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun `every move maps to the key a hardware keyboard would send`() {
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val expected = mapOf(
            CursorPad.Move.LEFT to (KeyEvent.KEYCODE_DPAD_LEFT to 0),
            CursorPad.Move.RIGHT to (KeyEvent.KEYCODE_DPAD_RIGHT to 0),
            CursorPad.Move.UP to (KeyEvent.KEYCODE_DPAD_UP to 0),
            CursorPad.Move.DOWN to (KeyEvent.KEYCODE_DPAD_DOWN to 0),
            CursorPad.Move.WORD_LEFT to (KeyEvent.KEYCODE_DPAD_LEFT to ctrl),
            CursorPad.Move.WORD_RIGHT to (KeyEvent.KEYCODE_DPAD_RIGHT to ctrl),
            CursorPad.Move.LINE_START to (KeyEvent.KEYCODE_MOVE_HOME to 0),
            CursorPad.Move.LINE_END to (KeyEvent.KEYCODE_MOVE_END to 0),
        )
        assertEquals(CursorPad.Move.entries.toSet(), expected.keys)
        for ((move, pair) in expected) {
            assertEquals("$move", pair, CursorPad.keyEvents(move, selecting = false))
            assertEquals("$move selecting", pair.first to (pair.second or shift), CursorPad.keyEvents(move, true))
        }
    }

    @Test fun `each key does what it shows`() {
        val expected = listOf(
            "LINE_START:false", "UP:false", "LINE_END:false",
            "LEFT:false", null, "RIGHT:false",
            "WORD_LEFT:false", "DOWN:false", "WORD_RIGHT:false",
            "letters", "selectAll", "cut", "backspace",
        )
        assertEquals(expected.size, pad.keyCount)
        for ((index, call) in expected.withIndex()) {
            if (call == null) continue   // Select is a latch; it has its own test
            calls.clear()
            tap(index)
            assertEquals("key $index", listOf(call), calls)
        }
    }

    @Test fun `Select latches, and the next move selects`() {
        tap(SELECT)
        assertTrue(pad.selecting)
        assertTrue(calls.isEmpty())
        tap(RIGHT)
        tap(WORD_LEFT)
        assertEquals(listOf("RIGHT:true", "WORD_LEFT:true"), calls)
        tap(SELECT)
        assertFalse(pad.selecting)
        tap(RIGHT)
        assertEquals("RIGHT:false", calls.last())
    }

    @Test fun `opening the pad lets go of Select`() {
        tap(SELECT)
        assertTrue(pad.selecting)
        pad.opened()
        assertFalse(pad.selecting)
        tap(LEFT)
        assertEquals(listOf("LEFT:false"), calls)
    }

    @Test fun `holding an arrow repeats until the finger lifts`() {
        send(MotionEvent.ACTION_DOWN, RIGHT)
        idle(300)
        assertTrue("nothing before the first repeat", calls.isEmpty())
        idle(500)
        val held = calls.size
        assertTrue("repeated while held, got $held", held > 3)
        assertTrue(calls.all { it == "RIGHT:false" })
        send(MotionEvent.ACTION_UP, RIGHT)
        assertEquals("lifting adds no extra step", held, calls.size)
        idle(500)
        assertEquals("and stops", held, calls.size)
    }

    @Test fun `holding backspace repeats too, and Cut does not`() {
        send(MotionEvent.ACTION_DOWN, BACKSPACE)
        idle(800)
        send(MotionEvent.ACTION_UP, BACKSPACE)
        assertTrue(calls.size > 3 && calls.all { it == "backspace" })
        calls.clear()
        send(MotionEvent.ACTION_DOWN, CUT)
        idle(800)
        send(MotionEvent.ACTION_UP, CUT)
        assertEquals(listOf("cut"), calls)
    }

    @Test fun `every key has a spoken name, and Select says whether it is on`() {
        val provider = pad.accessibilityNodeProvider!!
        val names = (0 until pad.keyCount).map {
            provider.createAccessibilityNodeInfo(it)!!.contentDescription.toString()
        }
        assertEquals(
            listOf(
                "Move to line start", "Up", "Move to line end",
                "Left", "Select as you move", "Right",
                "Previous word", "Down", "Next word",
                "Back to letters", "Select all", "Cut", "Backspace",
            ),
            names,
        )
        val off = provider.createAccessibilityNodeInfo(SELECT)!!
        assertTrue(off.isCheckable)
        assertFalse(off.isChecked)
        assertEquals("Off", off.stateDescription)

        provider.performAction(SELECT, AccessibilityNodeInfo.ACTION_CLICK, null)
        val on = provider.createAccessibilityNodeInfo(SELECT)!!
        assertTrue(on.isChecked)
        assertEquals("On", on.stateDescription)

        provider.performAction(UP, AccessibilityNodeInfo.ACTION_CLICK, null)
        assertEquals(listOf("UP:true"), calls)
    }

    @Test fun `labels fit on a phone`() {
        assertTrue(pad.labelsShown)
    }

    @Test fun `it draws in either appearance`() {
        val bitmap = android.graphics.Bitmap.createBitmap(pad.width, pad.height, android.graphics.Bitmap.Config.ARGB_8888)
        pad.draw(android.graphics.Canvas(bitmap))
        pad.selecting = true
        pad.appearance = Appearance.entries.last()
        pad.highContrast = true
        pad.draw(android.graphics.Canvas(bitmap))
    }

    private companion object {
        const val UP = 1
        const val LEFT = 3
        const val SELECT = 4
        const val RIGHT = 5
        const val WORD_LEFT = 6
        const val CUT = 11
        const val BACKSPACE = 12
    }
}
