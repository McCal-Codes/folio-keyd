package com.mccal.folio.keys

import android.content.Context
import android.graphics.Insets
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The one-handed keyboard: narrower, against one edge, with a rail beside it.
 *
 * Only on a window that would otherwise be filled edge to edge, so most of this is about where it does not happen as
 * much as where it does: an unfolded Fold splits whatever the setting says, and a window too narrow for a rail keeps
 * the ordinary keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class OneHandedTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val sides = mutableListOf<OneHanded>()

    private fun keyboard(widthDp: Int, heightDp: Int, side: OneHanded, cutoutLeft: Int = 0): KeyboardView {
        val land = if (widthDp > heightDp) "-land" else ""
        RuntimeEnvironment.setQualifiers("w${widthDp}dp-h${heightDp}dp$land-xhdpi")
        val view = KeyboardView(context)
        view.listener = object : KeyboardView.Listener {
            override fun onText(text: String) = Unit
            override fun onBackspace() = Unit
            override fun onDeleteWord() = Unit
            override fun onShift() = Unit
            override fun onLayer(layer: Layer) = Unit
            override fun onAction() = Unit
            override fun onSwitchKeyboard() = Unit
            override fun onCursor(steps: Int) = Unit
            override fun onSelectAll() = Unit
            override fun onCopy() = Unit
            override fun onPaste() = Unit
            override fun onEmojiPanel() = Unit
            override fun onClipboardPanel() = Unit
            override fun onSuggestion(word: String) = Unit
            override fun onHide() = Unit
            override fun onOneHanded(side: OneHanded) { sides += side }
        }
        view.settings = Settings(oneHanded = side)
        view.rules = FieldRules()
        view.rows = Layouts.rows(Layer.LETTERS, false, FieldRules())
        if (cutoutLeft > 0) {
            view.onApplyWindowInsets(
                WindowInsets.Builder().setInsets(WindowInsets.Type.displayCutout(), Insets.of(cutoutLeft, 0, 0, 0)).build(),
            )
        }
        val width = (widthDp * view.resources.displayMetrics.density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    private val KeyboardView.density get() = resources.displayMetrics.density

    private fun KeyboardView.tap(box: Box) {
        val x = (box.left + box.right) / 2
        val y = (box.top + box.bottom) / 2
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0, 0, action, x, y, 0)
            onTouchEvent(event)
            event.recycle()
        }
    }

    private fun KeyboardView.rail(kind: KeyKind) = railPlacements.single { it.key.kind == kind }.box

    // ---- where it happens ---------------------------------------------------------------------------------------

    @Test
    fun `a phone and the Fold's cover screen get it`() {
        assertEquals("ONE_HANDED", keyboard(411, 891, OneHanded.LEFT).shapeName)
        assertEquals("ONE_HANDED", keyboard(475, 751, OneHanded.RIGHT).shapeName)
    }

    @Test
    fun `a screen that splits or centres the keys ignores it`() {
        for ((w, h) in listOf(932 to 701, 600 to 960, 540 to 860, 800 to 1280)) {
            val off = keyboard(w, h, OneHanded.OFF).shapeName
            val on = keyboard(w, h, OneHanded.LEFT)
            assertEquals("${w}x$h", off, on.shapeName)
            assertNotEquals("FULL", off)
            assertTrue(on.railPlacements.isEmpty())
            assertEquals(0 to on.width, on.boardSpan(on.width))
        }
        assertEquals("SPLIT", keyboard(932, 701, OneHanded.RIGHT).shapeName)
    }

    @Test
    fun `off is the ordinary keyboard`() {
        val view = keyboard(411, 891, OneHanded.OFF)
        assertEquals("FULL", view.shapeName)
        assertTrue(view.railPlacements.isEmpty())
    }

    @Test
    fun `a window too narrow for a rail keeps the full keyboard`() {
        assertEquals("FULL", keyboard(320, 640, OneHanded.LEFT).shapeName)
        assertEquals("FULL", keyboard(327, 640, OneHanded.RIGHT).shapeName)
        // The narrowest that fits: 280 dp of keys and a 48 dp rail.
        val tight = keyboard(330, 640, OneHanded.LEFT)
        assertEquals("ONE_HANDED", tight.shapeName)
        val (left, right) = tight.boardSpan(tight.width)
        assertEquals(280f, (right - left) / tight.density, 1f)
    }

    @Test
    fun `the emoji search letters are never one-handed`() {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-xhdpi")
        val view = KeyboardView(context)
        view.searchKeys = true
        view.settings = Settings(oneHanded = OneHanded.LEFT)
        view.rows = Layouts.searchRows(Language.ENGLISH)
        val width = (411 * view.density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((200 * view.density).toInt(), View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertEquals("FULL", view.shapeName)
    }

    // ---- how wide, and which side -------------------------------------------------------------------------------

    @Test
    fun `left sits against the left edge and right against the right`() {
        val left = keyboard(411, 891, OneHanded.LEFT)
        val (l1, r1) = left.boardSpan(left.width)
        assertEquals(0, l1)
        assertEquals(355f, r1 / left.density, 1f)   // 411 less the 56 dp rail
        val right = keyboard(411, 891, OneHanded.RIGHT)
        val (l2, r2) = right.boardSpan(right.width)
        assertEquals(right.width, r2)
        assertEquals(355f, (r2 - l2) / right.density, 1f)
        // Room to spare, and it stops at a thumb's reach.
        val cover = keyboard(475, 751, OneHanded.LEFT)
        val (l3, r3) = cover.boardSpan(cover.width)
        assertEquals(360f, (r3 - l3) / cover.density, 1f)
    }

    @Test
    fun `the keys, the toolbar and the strip stay inside the narrow board`() {
        for (side in listOf(OneHanded.LEFT, OneHanded.RIGHT)) {
            val view = keyboard(411, 891, side)
            val (left, right) = view.boardSpan(view.width)
            val boxes = (view.placements + view.toolbarPlacements).map { it.box }
            assertTrue(boxes.isNotEmpty())
            for (box in boxes) assertTrue("$side: $box outside $left..$right", box.left >= left && box.right <= right)
            view.suggestions = listOf("helo", "hello", "help")
            for (box in view.toolbarPlacements.map { it.box }) assertTrue(box.left >= left && box.right <= right)
            view.offer = Insights.Offer("teh", "the")
            for (box in view.toolbarPlacements.map { it.box }) assertTrue(box.left >= left && box.right <= right)
            // And the rail is beside it, clear of every key.
            for (rail in view.railPlacements.map { it.box }) {
                assertTrue(rail.right <= left || rail.left >= right)
                assertTrue(rail.left >= 0f && rail.right <= view.width)
            }
        }
    }

    @Test
    fun `the letters are no smaller than a finger`() {
        val view = keyboard(330, 640, OneHanded.LEFT)
        val letters = view.placements.filter { it.key.kind == KeyKind.CHAR }
        assertTrue(letters.all { it.box.width / view.density >= 22f })
    }

    // ---- the rail -----------------------------------------------------------------------------------------------

    @Test
    fun `the rail's buttons are at least 48 dp each way and in the middle of its height`() {
        for (width in listOf(330, 411, 475)) {
            val view = keyboard(width, if (width == 475) 751 else 891, OneHanded.RIGHT)
            assertEquals(2, view.railPlacements.size)
            for (button in view.railPlacements.map { it.box }) {
                assertTrue("$width: ${button.width / view.density} dp wide", button.width / view.density >= 48f)
                assertTrue(button.height / view.density >= 48f)
            }
            val top = view.railPlacements.first().box.top
            val bottom = view.railPlacements.last().box.bottom
            val keysTop = view.placements.minOf { it.box.top }
            val keysBottom = view.placements.maxOf { it.box.bottom }
            assertTrue(top > 0f && bottom < view.height)
            // Centred on the keys' band rather than the whole view, which has the gesture strip at the bottom.
            assertEquals((keysTop + keysBottom) / 2, (top + bottom) / 2, 24 * view.density)
        }
    }

    @Test
    fun `full width turns it off at once`() {
        val view = keyboard(411, 891, OneHanded.LEFT)
        view.tap(view.rail(KeyKind.FULL_WIDTH))
        assertEquals(listOf(OneHanded.OFF), sides)
        assertEquals("FULL", view.shapeName)
        assertTrue(view.railPlacements.isEmpty())
        assertEquals(OneHanded.OFF, view.settings.oneHanded)
        assertEquals(view.width.toFloat(), view.placements.maxOf { it.box.right } + 9 * view.density, 2f)
    }

    @Test
    fun `the other side swaps it, and back again`() {
        val view = keyboard(411, 891, OneHanded.LEFT)
        view.tap(view.rail(KeyKind.OTHER_SIDE))
        assertEquals(OneHanded.RIGHT, view.settings.oneHanded)
        assertEquals(view.width, view.boardSpan(view.width).second)
        assertTrue(view.placements.minOf { it.box.left } > 50 * view.density)
        assertTrue(view.rail(KeyKind.OTHER_SIDE).right <= view.placements.minOf { it.box.left })
        view.tap(view.rail(KeyKind.OTHER_SIDE))
        assertEquals(listOf(OneHanded.RIGHT, OneHanded.LEFT), sides)
        assertEquals(0, view.boardSpan(view.width).first)
    }

    @Test
    fun `a screen reader finds the rail after the keys, by name, and can press it`() {
        val view = keyboard(411, 891, OneHanded.LEFT)
        val provider = view.accessibilityNodeProvider!!
        val first = view.placements.size + view.toolbarPlacements.size
        assertEquals("Full width", provider.createAccessibilityNodeInfo(first)!!.contentDescription)
        assertEquals("Move to the other side", provider.createAccessibilityNodeInfo(first + 1)!!.contentDescription)
        provider.performAction(first + 1, AccessibilityNodeInfo.ACTION_CLICK, null)
        assertEquals(listOf(OneHanded.RIGHT), sides)
    }

    @Test
    fun `the rail keeps off a camera cutout`() {
        // Kept clear on both edges, as the full keyboard keeps it, so a big one leaves too little room for a rail.
        assertEquals("FULL", keyboard(411, 891, OneHanded.RIGHT, cutoutLeft = 90).shapeName)
        val cutout = 40   // px, on the left
        val view = keyboard(411, 891, OneHanded.RIGHT, cutoutLeft = cutout)
        assertEquals("ONE_HANDED", view.shapeName)
        for (button in view.railPlacements.map { it.box }) assertTrue(button.left >= cutout)
        val left = keyboard(411, 891, OneHanded.LEFT, cutoutLeft = cutout)
        assertTrue(left.placements.minOf { it.box.left } >= cutout)
    }

    // ---- the panels ---------------------------------------------------------------------------------------------

    @Test
    fun `the panels take the letters' board, against the same edge`() {
        for (side in listOf(OneHanded.LEFT, OneHanded.RIGHT)) {
            RuntimeEnvironment.setQualifiers("w411dp-h891dp-xhdpi")
            val keys = KeyboardView(context)
            keys.settings = Settings(oneHanded = side)
            keys.rows = Layouts.rows(Layer.LETTERS, false, FieldRules())
            val panels = listOf(EmojiPanel(context), ClipboardPanel(context), CursorPad(context), EmojiSearchPanel(context))
            val frame = KeyboardFrame(context, keys)
            frame.addView(keys)
            panels.forEach { frame.addView(it) }
            val width = (411 * keys.resources.displayMetrics.density).toInt()
            for (panel in panels) {
                keys.visibility = View.GONE
                panels.forEach { it.visibility = if (it === panel) View.VISIBLE else View.GONE }
                frame.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
                val (left, right) = keys.boardSpan(width)
                assertEquals("${panel.javaClass.simpleName} $side", left, panel.left)
                assertEquals("${panel.javaClass.simpleName} $side", right, panel.right)
            }
            // The search's own letters fill the narrow panel rather than going one-handed inside it.
            val search = panels.last() as EmojiSearchPanel
            search.opened(Settings(oneHanded = side), Language.ENGLISH)
            search.keys.measure(
                View.MeasureSpec.makeMeasureSpec(search.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((200 * keys.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
            )
            search.keys.layout(0, 0, search.keys.measuredWidth, search.keys.measuredHeight)
            assertEquals("FULL", search.keys.shapeName)
        }
    }

    @Test
    fun `off, the panels fill the width`() {
        val keys = KeyboardView(context)
        keys.rows = Layouts.rows(Layer.LETTERS, false, FieldRules())
        val grid = EmojiPanel(context)
        val frame = KeyboardFrame(context, keys)
        frame.addView(keys)
        frame.addView(grid)
        val width = (411 * keys.resources.displayMetrics.density).toInt()
        frame.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
        assertEquals(0, grid.left)
        assertEquals(width, grid.right)
    }

    // ---- the setting --------------------------------------------------------------------------------------------

    @Test
    fun `the setting is saved, and read back`() {
        val prefs = context.getSharedPreferences("one-handed-test", Context.MODE_PRIVATE)
        assertEquals(OneHanded.OFF, Settings.load(prefs).oneHanded)
        Settings(oneHanded = OneHanded.RIGHT).save(prefs)
        assertEquals(OneHanded.RIGHT, Settings.load(prefs).oneHanded)
        prefs.edit().putString(Settings.ONE_HANDED, "SIDEWAYS").commit()
        assertEquals(OneHanded.OFF, Settings.load(prefs).oneHanded)
    }

    @Test
    fun `the rail's choice is kept by the service and shown`() {
        val prefs = context.getSharedPreferences("keys", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        Settings(oneHanded = OneHanded.LEFT, numberRow = true).save(prefs)
        val service = Robolectric.buildService(KeysService::class.java).create().get()
        service.onCreateInputView()
        service.onStartInputView(EditorInfo(), false)
        service.oneHanded(OneHanded.RIGHT)
        val stored = Settings.load(prefs)
        assertEquals(OneHanded.RIGHT, stored.oneHanded)
        assertTrue("the rest is left alone", stored.numberRow)
        service.oneHanded(OneHanded.OFF)
        assertEquals(OneHanded.OFF, Settings.load(prefs).oneHanded)
    }
}
