package com.mccal.folio.keys

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The keyboard being typed on, without a phone.
 *
 * Everything here is a touch the way Android delivers one - down, move, up, second finger and all - so the parts that
 * only exist while a finger is down (the repeat on backspace, a swipe that cancels the press, two thumbs at once) are
 * checked rather than reasoned about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class KeyboardViewTest {

    private lateinit var view: KeyboardView
    private val typed = StringBuilder()
    private var backspaces = 0
    private var wordDeletes = 0
    private var cursorSteps = 0
    private var actions = 0
    private var switches = 0
    private var layers = mutableListOf<Layer>()
    private var shifts = 0

    private val density get() = view.resources.displayMetrics.density

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        view = KeyboardView(context)
        view.listener = object : KeyboardView.Listener {
            override fun onText(text: String) { typed.append(text) }
            override fun onBackspace() { backspaces++ }
            override fun onDeleteWord() { wordDeletes++ }
            override fun onShift() { shifts++ }
            override fun onLayer(layer: Layer) { layers += layer }
            override fun onAction() { actions++ }
            override fun onSwitchKeyboard() { switches++ }
            override fun onCursor(steps: Int) { cursorSteps += steps }
        }
        show(FieldRules())
    }

    private fun show(rules: FieldRules, layer: Layer = Layer.LETTERS, shift: Shift = Shift.OFF) {
        view.rules = rules
        view.shift = shift
        view.rows = Layouts.rows(layer, shift != Shift.OFF, rules)
        val width = (411 * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    /** Where a key sits, worked out the same way the view works it out. */
    private fun centre(label: String): Pair<Float, Float> {
        val placement = Geometry.place(view.rows, view.width, view.height, density)
            .firstOrNull { it.key.label == label }
        assertNotNull("no key labelled $label", placement)
        val box = placement!!.box
        return (box.left + box.right) / 2 to (box.top + box.bottom) / 2
    }

    private fun send(action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun tap(label: String) {
        val (x, y) = centre(label)
        send(MotionEvent.ACTION_DOWN, x, y)
        send(MotionEvent.ACTION_UP, x, y)
    }

    // ---- typing -------------------------------------------------------------------------------------------------

    @Test
    fun `tapping letters types them`() {
        "hello".forEach { tap(it.toString()) }
        assertEquals("hello", typed.toString())
    }

    @Test
    fun `the space bar types a space, not the word on it`() {
        tap("h")
        tap("space")
        tap("i")
        assertEquals("h i", typed.toString())
    }

    @Test
    fun `sliding off a key types nothing`() {
        val (x, y) = centre("q")
        val (farX, farY) = centre("p")
        send(MotionEvent.ACTION_DOWN, x, y)
        send(MotionEvent.ACTION_MOVE, farX, farY)
        send(MotionEvent.ACTION_UP, farX, farY)
        assertEquals("", typed.toString())
    }

    @Test
    fun `a press outside every key does nothing`() {
        send(MotionEvent.ACTION_DOWN, 1f, (view.height - 1).toFloat())
        send(MotionEvent.ACTION_UP, 1f, (view.height - 1).toFloat())
        assertEquals("", typed.toString())
    }

    /** Two thumbs, overlapping: the reason a keyboard cannot track one pointer. */
    @Test
    fun `a second finger is not dropped`() {
        val (ax, ay) = centre("a")
        val (lx, ly) = centre("l")
        val properties = arrayOf(pointer(0), pointer(1))
        val coords = arrayOf(at(ax, ay), at(lx, ly))

        MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            .let { view.onTouchEvent(it); it.recycle() }
        MotionEvent.obtain(
            0, 1, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0,
        ).let { view.onTouchEvent(it); it.recycle() }
        MotionEvent.obtain(
            0, 2, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0,
        ).let { view.onTouchEvent(it); it.recycle() }
        MotionEvent.obtain(0, 3, MotionEvent.ACTION_UP, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            .let { view.onTouchEvent(it); it.recycle() }

        assertEquals("la", typed.toString())   // the second finger lifts first, so its letter lands first
    }

    private fun pointer(id: Int) = MotionEvent.PointerProperties().apply {
        this.id = id
        toolType = MotionEvent.TOOL_TYPE_FINGER
    }

    private fun at(x: Float, y: Float) = MotionEvent.PointerCoords().apply {
        this.x = x
        this.y = y
        pressure = 1f
        size = 1f
    }

    // ---- the keys that aren't letters ---------------------------------------------------------------------------

    @Test
    fun `backspace deletes once, and holding it repeats`() {
        tap("⌫")
        assertEquals(1, backspaces)

        val (x, y) = centre("⌫")
        send(MotionEvent.ACTION_DOWN, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        val whileHeld = backspaces
        assertTrue("a held backspace should repeat, got $whileHeld", whileHeld > 2)
        send(MotionEvent.ACTION_UP, x, y)
        assertEquals("lifting must not delete once more", whileHeld, backspaces)
    }

    @Test
    fun `a held backspace stops when the keyboard goes away`() {
        val (x, y) = centre("⌫")
        send(MotionEvent.ACTION_DOWN, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        val whenHidden = backspaces
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Void>(view, "onDetachedFromWindow")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(whenHidden, backspaces)
    }

    @Test
    fun `swiping the backspace takes a word instead`() {
        val (x, y) = centre("⌫")
        send(MotionEvent.ACTION_DOWN, x, y)
        send(MotionEvent.ACTION_MOVE, x - 40 * density, y)
        send(MotionEvent.ACTION_UP, x - 40 * density, y)
        assertEquals(1, wordDeletes)
        assertEquals("the swipe replaces the press", 0, backspaces)
    }

    @Test
    fun `swiping the space bar moves the cursor and types nothing`() {
        val (x, y) = centre("space")
        send(MotionEvent.ACTION_DOWN, x, y)
        send(MotionEvent.ACTION_MOVE, x + 40 * density, y)
        send(MotionEvent.ACTION_UP, x + 40 * density, y)
        assertTrue("cursor should have moved right, got $cursorSteps", cursorSteps >= 2)
        assertEquals("", typed.toString())
    }

    @Test
    fun `the other keys report themselves`() {
        tap("⇧")
        tap("123")
        tap("🌐")
        tap("return")
        assertEquals(1, shifts)
        assertEquals(listOf(Layer.NUMBERS), layers)
        assertEquals(1, switches)
        assertEquals(1, actions)
    }

    @Test
    fun `the field decides what the keys are`() {
        show(FieldRules(kind = FieldKind.EMAIL, actionLabel = "Go"))
        tap("@")
        tap(".com")
        tap("Go")
        assertEquals("@.com", typed.toString())
        assertEquals(1, actions)
    }

    // ---- screen readers ------------------------------------------------------------------------------------------

    @Test
    fun `every key is offered to a screen reader, and can be pressed by one`() {
        val provider = view.accessibilityNodeProvider
        assertNotNull("no accessibility node provider", provider)
        val keys = Geometry.place(view.rows, view.width, view.height, density)
        val letterQ = keys.indexOfFirst { it.key.label == "q" }

        val node = provider!!.createAccessibilityNodeInfo(letterQ)
        assertEquals("q", node?.contentDescription)

        provider.performAction(letterQ, AccessibilityNodeInfo.ACTION_CLICK, null)
        assertEquals("q", typed.toString())
    }

    @Test
    fun `a screen reader hears names, not glyphs`() {
        val provider = view.accessibilityNodeProvider!!
        val keys = Geometry.place(view.rows, view.width, view.height, density)
        val backspace = keys.indexOfFirst { it.key.kind == KeyKind.BACKSPACE }
        val description = provider.createAccessibilityNodeInfo(backspace)?.contentDescription.toString()
        assertTrue("backspace reads as $description", description.startsWith("Backspace"))
    }
}
