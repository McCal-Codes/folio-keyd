package com.mccal.folio.keys

import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The clipboard panel, laid out and tapped.
 *
 * A row has three targets in it — the clip, its pin, its cross — and the one that matters is the difference between
 * them: a tap meant to put a clip back must never be the tap that forgets it. So the tests aim at the boundaries
 * rather than the middles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ClipboardPanelTest {

    private lateinit var panel: ClipboardPanel
    private val inserted = mutableListOf<String>()
    private val pinned = mutableListOf<Pair<String, Boolean>>()
    private val forgotten = mutableListOf<String>()
    private var cleared = 0
    private var backspaces = 0
    private var letters = 0

    private val density get() = panel.resources.displayMetrics.density
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        panel = ClipboardPanel(ApplicationProvider.getApplicationContext<Context>())
        panel.listener = object : ClipboardPanel.Listener {
            override fun onClip(text: String) { inserted += text }
            override fun onPinClip(text: String, pin: Boolean) { pinned += text to pin }
            override fun onForgetClip(text: String) { forgotten += text }
            override fun onClearClips() { cleared++ }
            override fun onBackspace() { backspaces++ }
            override fun onLetters() { letters++ }
        }
        panel.clips = listOf(
            Clipboard.Clip("first thing", now),
            Clipboard.Clip("second thing", now - 1),
        )
        lay(411)
    }

    private fun lay(widthDp: Int) {
        val width = (widthDp * density).toInt()
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
    }

    private fun send(action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        panel.onTouchEvent(event)
        event.recycle()
    }

    private fun tap(x: Float, y: Float) {
        send(MotionEvent.ACTION_DOWN, x, y)
        send(MotionEvent.ACTION_UP, x, y)
    }

    /** The middle of the first row's text, well clear of either button. */
    private fun firstRowY() = (8 + 26) * density

    @Test fun `pinned clips come first, then the newest`() {
        panel.clips = listOf(
            Clipboard.Clip("old and loose", now - 100),
            Clipboard.Clip("new and loose", now),
            Clipboard.Clip("old and pinned", now - 200, pinned = true),
        )
        assertEquals(listOf("old and pinned", "new and loose", "old and loose"), panel.showing())
    }

    @Test fun `tapping a clip puts it back`() {
        tap(80 * density, firstRowY())
        assertEquals(listOf("first thing"), inserted)
        assertTrue("pinning or forgetting must not have happened", pinned.isEmpty() && forgotten.isEmpty())
    }

    @Test fun `the pin and the cross are their own targets, and neither is the clip`() {
        val width = panel.width.toFloat()
        // The cross is the last 48 dp, the pin the 48 before it: aim at the middle of each.
        tap(width - 24 * density, firstRowY())
        assertEquals(listOf("first thing"), forgotten)
        tap(width - 72 * density, firstRowY())
        assertEquals(listOf("first thing" to true), pinned)
        assertTrue("neither should have inserted anything", inserted.isEmpty())
    }

    @Test fun `tapping the pin on a pinned clip unpins it`() {
        panel.clips = listOf(Clipboard.Clip("kept", now, pinned = true))
        tap(panel.width - 72 * density, firstRowY())
        assertEquals(listOf("kept" to false), pinned)
    }

    @Test fun `Clear says it leaves pinned clips when there are any`() {
        assertEquals(R.string.clipboard_clear, panel.clearLabel)
        panel.clips = listOf(Clipboard.Clip("kept", now, pinned = true), Clipboard.Clip("loose", now - 1))
        assertEquals(R.string.clipboard_clear_unpinned, panel.clearLabel)
    }

    @Test fun `the tabs are letters, clear all and backspace`() {
        assertEquals(3, panel.tabCount)
        val y = panel.height - 30 * density
        val third = panel.width / 3f
        tap(third * 0.5f, y)
        tap(third * 1.5f, y)
        tap(third * 2.5f, y)
        assertEquals(1, letters)
        assertEquals(1, cleared)
        assertEquals(1, backspaces)
    }

    @Test fun `an empty history says so and swallows taps`() {
        panel.clips = emptyList()
        tap(80 * density, firstRowY())
        assertTrue("nothing to insert from an empty list", inserted.isEmpty())
        assertEquals(0f, panel.scrollRange)
    }

    @Test fun `a long list scrolls, a short one does not`() {
        panel.clips = (1..20).map { Clipboard.Clip("clip $it", now - it) }
        assertTrue("twenty rows should not fit in four keyboard rows", panel.scrollRange > 0f)

        panel.clips = listOf(Clipboard.Clip("only one", now))
        assertEquals(0f, panel.scrollRange)
    }

    /** A drag is a scroll, not a tap: letting go after moving must not paste whatever was under the finger. */
    @Test fun `dragging scrolls instead of choosing`() {
        panel.clips = (1..20).map { Clipboard.Clip("clip $it", now - it) }
        send(MotionEvent.ACTION_DOWN, 80 * density, firstRowY())
        send(MotionEvent.ACTION_MOVE, 80 * density, firstRowY() - 60 * density)
        send(MotionEvent.ACTION_UP, 80 * density, firstRowY() - 60 * density)
        assertTrue("a drag must not insert a clip", inserted.isEmpty())
    }

    @Test fun `every row is tall enough to hit, on any width`() {
        for (widthDp in listOf(320, 360, 411, 475, 600, 932)) {
            lay(widthDp)
            panel.clips = listOf(Clipboard.Clip("something", now))
            // Row 0 has to answer to a tap at its top and near its bottom alike.
            assertEquals("$widthDp dp: the top of the row", 0, panel.rowAtForTest(firstRowY()))
            assertEquals(
                "$widthDp dp: 40 dp lower is still the same row",
                0, panel.rowAtForTest(firstRowY() + 20 * density),
            )
        }
    }

    @Test fun `the buttons keep their place on a narrow window`() {
        lay(320)
        val width = panel.width.toFloat()
        assertEquals("FORGET", panel.partAtForTest(width - 10 * density))
        assertEquals("PIN", panel.partAtForTest(width - 60 * density))
        assertEquals("TEXT", panel.partAtForTest(40 * density))
    }
}
