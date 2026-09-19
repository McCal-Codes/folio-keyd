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
 * The emoji grid, laid out and tapped.
 *
 * The grid is arithmetic like the keys are - how many fit across, how far down the list a tap lands - and it has the
 * same way of going wrong: cells too small to hit, a tap landing on the emoji next to the one under the finger, or a
 * scroll that runs past the end of the list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class EmojiPanelTest {

    private lateinit var panel: EmojiPanel
    private val picked = mutableListOf<String>()
    private var backspaces = 0
    private var letters = 0

    private val density get() = panel.resources.displayMetrics.density

    @Before
    fun setUp() {
        panel = EmojiPanel(ApplicationProvider.getApplicationContext<Context>())
        panel.listener = object : EmojiPanel.Listener {
            override fun onEmoji(emoji: String) { picked += emoji }
            override fun onBackspace() { backspaces++ }
            override fun onLetters() { letters++ }
        }
        panel.selectCategory(1)   // the recents start empty, so show a category with something in it
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

    @Test
    fun `the grid is as wide as the window allows and no cell is too small to hit`() {
        for (widthDp in listOf(320, 360, 411, 475, 600, 932)) {
            lay(widthDp)
            assertTrue("only ${panel.visibleColumns} columns at $widthDp dp", panel.visibleColumns >= 6)
            assertTrue(
                "cells are ${panel.cellSizeDp} dp at $widthDp dp",
                panel.cellSizeDp >= 24f,
            )
        }
    }

    /** A wider window should get more emoji across, not bigger ones: that is the whole point of measuring. */
    @Test
    fun `an unfolded screen fits more across`() {
        lay(411)
        val phone = panel.visibleColumns
        lay(932)
        assertTrue("a Fold fits no more than a phone ($phone)", panel.visibleColumns > phone)
    }

    @Test
    fun `tapping an emoji picks that emoji`() {
        val first = panel.showing().first()
        val cell = panel.cellSizeDp * density
        tap(cell * 0.5f + 8 * density, cell * 0.5f + 20 * density)
        assertEquals(listOf(first), picked)
    }

    @Test
    fun `the tab bar has a letters key, a tab for every category and a backspace`() {
        assertEquals(Emoji.CATEGORIES.size + 3, panel.tabCount)
    }

    @Test
    fun `the letters key goes back to the keyboard`() {
        val y = panel.height - panel.resources.displayMetrics.density * 40
        tap(20 * density, y)
        assertEquals(1, letters)
        assertEquals("that is not an emoji", emptyList<String>(), picked)
    }

    @Test
    fun `the backspace in the tab bar deletes`() {
        val y = panel.height - panel.resources.displayMetrics.density * 40
        tap(panel.width - 20 * density, y)
        assertEquals(1, backspaces)
    }

    /** Dragging is scrolling, not picking: letting go after a drag must not type whatever is under the finger. */
    @Test
    fun `a drag scrolls instead of picking`() {
        val x = 60 * density
        send(MotionEvent.ACTION_DOWN, x, 80 * density)
        send(MotionEvent.ACTION_MOVE, x, 40 * density)
        send(MotionEvent.ACTION_UP, x, 40 * density)
        assertEquals(emptyList<String>(), picked)
    }

    @Test
    fun `every category can be shown, and shows its own emoji`() {
        for (which in Emoji.CATEGORIES.indices) {
            panel.selectCategory(which + 1)
            lay(411)
            assertEquals(Emoji.CATEGORIES[which].items, panel.showing())
        }
    }

    /** Nothing used yet is a normal state on a new install, and must not be an empty screen with no explanation. */
    @Test
    fun `the recents start empty without breaking`() {
        panel.selectCategory(0)
        lay(411)
        assertEquals(emptyList<String>(), panel.showing())
        tap(60 * density, 80 * density)
        assertEquals(emptyList<String>(), picked)
    }

    @Test
    fun `recents show what was remembered`() {
        panel.recents = listOf("😀", "😁")
        panel.selectCategory(0)
        lay(411)
        assertEquals(listOf("😀", "😁"), panel.showing())
    }
}
