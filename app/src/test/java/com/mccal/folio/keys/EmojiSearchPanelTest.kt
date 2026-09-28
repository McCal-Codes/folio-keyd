package com.mccal.folio.keys

import android.content.Context
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Emoji search, typed on and tapped.
 *
 * The promise that matters most here is the one that is easiest to break without noticing: while searching, the
 * letters type into the search and not into the app. So these type with real touches on the real keys, against a
 * real field, and read what ended up in it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class EmojiSearchPanelTest {

    /** Just enough of an input method to hand [TextActions] a field, which is what the service does. */
    private class FakeIme(override val connection: InputConnection?) : Ime {
        override val editorInfo: EditorInfo? = null
        override fun switchKeyboard() = Unit
        override fun hideKeyboard() = Unit
        override fun show(rows: List<Row>, shift: Shift) = Unit
        override fun suggest(word: String) = Unit
        override fun learn(word: String) = Unit
        override fun showEmoji(showing: Boolean) = Unit
        override fun showClipboard(showing: Boolean) = Unit
    }

    private lateinit var panel: EmojiSearchPanel
    private lateinit var field: BaseInputConnection
    private lateinit var actions: TextActions
    private val picked = mutableListOf<String>()
    private var backs = 0

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val density get() = panel.resources.displayMetrics.density
    private val text get() = field.editable.toString()

    @Before
    fun setUp() {
        field = BaseInputConnection(View(context), true)
        actions = TextActions(FakeIme(field))
        actions.startInput(EditorInfo().also { it.inputType = InputType.TYPE_CLASS_TEXT })
        panel = EmojiSearchPanel(context)
        panel.listener = object : EmojiSearchPanel.Listener {
            // What the service does with a result.
            override fun onEmoji(emoji: String) {
                picked += emoji
                actions.onEmoji(emoji)
            }

            override fun onBack() { backs++ }
        }
        panel.search = EmojiSearch.load(context, Language.ENGLISH)
        panel.opened(Settings(), Language.ENGLISH)
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

    private fun tap(view: View, x: Float, y: Float) {
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0, 0, action, x, y, 0)
            view.onTouchEvent(event)
            event.recycle()
        }
    }

    /** Presses a key on the search letters by what it says, the way a finger would. */
    private fun press(label: String) {
        val key = panel.keys.placements.first { it.key.label == label || it.key.output == label }
        tap(panel.keys, (key.box.left + key.box.right) / 2, (key.box.top + key.box.bottom) / 2)
    }

    private fun typeOnKeys(word: String) = word.forEach { press(if (it == ' ') " " else it.toString()) }

    @Test
    fun `it opens empty, with a hint instead of results`() {
        assertEquals("", panel.query)
        assertEquals(emptyList<String>(), panel.results)
        assertTrue(panel.bar.spoken().contains("Search emoji"))
    }

    @Test
    fun `typing heart searches, and sends nothing to the app`() {
        typeOnKeys("heart")
        assertEquals("heart", panel.query)
        assertEquals("❤️", panel.results.first())
        assertEquals("the letters went to the app", "", text)
    }

    @Test
    fun `backspace and space edit the search, not the app`() {
        field.commitText("hello", 1)
        typeOnKeys("red hearx")
        press("⌫")
        press("t")
        assertEquals("red heart", panel.query)
        assertEquals("hello", text)
    }

    @Test
    fun `tapping a result puts it in the field and stays in search`() {
        typeOnKeys("heart")
        panel.tapResultForTest(0)
        assertEquals("❤️", text)
        assertEquals(listOf("❤️"), picked)
        // Still searching, so a second one can follow.
        assertEquals("heart", panel.query)
        panel.tapResultForTest(1)
        assertEquals(2, picked.size)
        assertEquals(picked.joinToString(""), text)
    }

    @Test
    fun `the back arrow goes back to the grid`() {
        tap(panel.bar, 20 * density, 30 * density)
        assertEquals(1, backs)
    }

    @Test
    fun `the emoji key under the letters goes back to the grid`() {
        press(Layouts.BACK_TO_EMOJI)
        assertEquals(1, backs)
        assertEquals("", text)
    }

    @Test
    fun `the cross clears the search`() {
        typeOnKeys("cat")
        tap(panel.bar, panel.width - 30 * density, 30 * density)
        assertEquals("", panel.query)
        assertEquals(emptyList<String>(), panel.results)
    }

    @Test
    fun `nothing found is said, not left blank`() {
        typeOnKeys("qxqx")
        assertEquals(emptyList<String>(), panel.results)
        assertEquals("qxqx", panel.query)
        assertEquals("", text)
    }

    @Test
    fun `TalkBack reads the field and each result by name`() {
        typeOnKeys("heart")
        val spoken = panel.bar.spoken()
        assertTrue(spoken.toString(), spoken.contains("Search emoji, heart"))
        assertTrue(spoken.toString(), spoken.contains("red heart"))
        assertTrue(spoken.toString(), spoken.contains("Back to emoji"))
        assertTrue(spoken.toString(), spoken.contains("Clear search"))
        assertFalse("a glyph read out as itself", spoken.contains("❤️"))
    }

    @Test
    fun `opening again starts from nothing`() {
        typeOnKeys("cat")
        panel.opened(Settings(), Language.ENGLISH)
        assertEquals("", panel.query)
    }

    /** Only whole cells: a result cut off at the edge is one that cannot be read or hit. */
    @Test
    fun `the results row has no partial cells`() {
        for (widthDp in listOf(411, 280)) {
            lay(widthDp)
            panel.opened(Settings(), Language.ENGLISH)
            typeOnKeys("face")
            val boxes = panel.results.indices.map { panel.bar.resultBox(it) }
            assertTrue("no results at $widthDp dp", boxes.isNotEmpty())
            val right = panel.width - (6 + Geometry.SIDE_PAD_DP) * density
            for (box in boxes) {
                assertTrue("a cell ${box.width / density} dp wide at $widthDp dp", box.width >= 48 * density - 0.5f)
                assertTrue("a cell runs off the edge at $widthDp dp", box.right <= right + 0.5f)
                assertTrue("a cell off the row at $widthDp dp", box.left >= (6 + Geometry.SIDE_PAD_DP) * density - 0.5f)
            }
            // As many as fit, and one more would not.
            val usable = panel.width - 2 * (6 + Geometry.SIDE_PAD_DP) * density
            assertEquals((usable / (48 * density)).toInt(), boxes.size)
        }
    }

    /** It opens in the emoji panel's place, so the app above must not move. */
    @Test
    fun `it is exactly as tall as the emoji panel`() {
        for (widthDp in listOf(411, 280)) {
            lay(widthDp)
            val grid = EmojiPanel(context)
            grid.measure(
                View.MeasureSpec.makeMeasureSpec(panel.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            assertEquals(grid.measuredHeight, panel.measuredHeight)
        }
    }

    @Test
    fun `the search letters keep inside their part of the panel`() {
        for (placement in panel.keys.placements) {
            val box = placement.box
            assertTrue(placement.key.label, box.top >= 0f && box.bottom <= panel.keys.height)
            assertTrue(placement.key.label, box.left >= 0f && box.right <= panel.keys.width)
        }
        assertEquals(panel.height, panel.bar.height + panel.keys.height)
    }
}

/**
 * The search as the service runs it: opened from the grid's search key, and closed by a new field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class EmojiSearchServiceTest {

    @Test
    fun `the search key opens search, and a new field leaves it`() {
        val service = Robolectric.buildService(KeysService::class.java).create().get()
        val root = service.onCreateInputView() as FrameLayout
        val grid = (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<EmojiPanel>().single()
        val finder = (0 until root.childCount).map { root.getChildAt(it) }
            .filterIsInstance<EmojiSearchPanel>().single()
        val keys = (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<KeyboardView>().single()

        service.showEmoji(true)
        grid.listener!!.onSearch()
        assertEquals(View.VISIBLE, finder.visibility)
        assertEquals(View.GONE, grid.visibility)
        assertEquals(View.GONE, keys.visibility)

        // Back returns to the grid.
        finder.listener!!.onBack()
        assertEquals(View.GONE, finder.visibility)
        assertEquals(View.VISIBLE, grid.visibility)

        grid.listener!!.onSearch()
        assertEquals(View.VISIBLE, finder.visibility)
        service.onStartInputView(EditorInfo().also { it.inputType = InputType.TYPE_CLASS_TEXT }, false)
        assertEquals(View.GONE, finder.visibility)
        assertEquals(View.VISIBLE, keys.visibility)
    }
}
