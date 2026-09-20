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
    private val toolbar = mutableListOf<String>()

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
            override fun onSelectAll() { toolbar += "selectAll" }
            override fun onCopy() { toolbar += "copy" }
            override fun onPaste() { toolbar += "paste" }
            override fun onHide() { toolbar += "hide" }
            override fun onEmojiPanel() { toolbar += "emoji" }
            override fun onSuggestion(word: String) { toolbar += "suggestion:$word" }
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

    /** Where a key sits, according to the view that placed it. */
    private fun centre(label: String): Pair<Float, Float> {
        val placement = view.placements.firstOrNull { it.key.label == label }
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

    // ---- the suggestion strip -------------------------------------------------------------------------------

    private fun stripLabels() = view.toolbarPlacements.map { it.key.label }

    @Test
    fun `with nothing to suggest the toolbar is what shows`() {
        assertEquals(listOf("Hide", "Emoji", "Select all", "Copy", "Paste"), stripLabels())
    }

    @Test
    fun `suggestions take the toolbar's place, with the literal first`() {
        view.suggestions = listOf("teh", "the", "ten", "tea")
        assertEquals(listOf("teh", "the", "ten", "tea"), stripLabels())
    }

    @Test
    fun `tapping a suggestion reports the word`() {
        view.suggestions = listOf("teh", "the", "ten")
        val box = view.toolbarPlacements[1].box
        send(MotionEvent.ACTION_DOWN, (box.left + box.right) / 2, (box.top + box.bottom) / 2)
        send(MotionEvent.ACTION_UP, (box.left + box.right) / 2, (box.top + box.bottom) / 2)
        assertEquals(listOf("suggestion:the"), toolbar)
    }

    @Test
    fun `the toolbar comes back when the word is finished`() {
        view.suggestions = listOf("teh", "the")
        view.suggestions = emptyList()
        assertEquals(listOf("Hide", "Emoji", "Select all", "Copy", "Paste"), stripLabels())
    }

    /** The strip is the same height as the toolbar, so nothing below it moves as words start and finish. */
    @Test
    fun `the strip does not change the keyboard's height`() {
        val before = view.height
        val keyBefore = centre("a")
        view.suggestions = listOf("teh", "the", "ten")
        assertEquals(before, view.height)
        assertEquals(keyBefore, centre("a"))
    }

    /** Nothing typed into a password field is offered back on screen. */
    @Test
    fun `a password field shows the toolbar, never suggestions`() {
        show(FieldRules(password = true))
        view.suggestions = listOf("hunter2", "hunter")
        assertEquals(listOf("Hide", "Emoji", "Select all", "Copy", "Paste"), stripLabels())
    }

    @Test
    fun `every suggestion is big enough to tap`() {
        view.suggestions = listOf("teh", "the", "ten", "tea")
        for (placement in view.toolbarPlacements) {
            assertTrue(
                "${placement.key.label} is ${placement.box.width / density} dp",
                placement.box.width / density >= 24f && placement.box.height / density >= 24f,
            )
        }
    }

    /** The keycap prints a number in its corner; holding the key has to actually produce it. */
    @Test
    fun `holding a key types the alternate printed on it`() {
        val (x, y) = centre("q")
        send(MotionEvent.ACTION_DOWN, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        assertEquals("1", typed.toString())
        send(MotionEvent.ACTION_UP, x, y)
        assertEquals("holding it should not also type the letter", "1", typed.toString())
    }

    @Test
    fun `a quick tap is still the letter, not the alternate`() {
        tap("q")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        assertEquals("q", typed.toString())
    }

    @Test
    fun `a finger that moves on is no longer holding`() {
        val (x, y) = centre("q")
        val (farX, farY) = centre("p")
        send(MotionEvent.ACTION_DOWN, x, y)
        send(MotionEvent.ACTION_MOVE, farX, farY)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        assertEquals("nothing should have been typed while still held", "", typed.toString())
        send(MotionEvent.ACTION_UP, farX, farY)
        assertEquals("p", typed.toString())
    }

    /** A letter with no alternates at all - no accents, no corner digit - does nothing when held. */
    @Test
    fun `holding a key with nothing behind it types nothing extra`() {
        val (x, y) = centre("x")
        send(MotionEvent.ACTION_DOWN, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        assertEquals("", typed.toString())
        assertEquals("no row of alternates should have opened", emptyList<String>(), view.popupItems)
        send(MotionEvent.ACTION_UP, x, y)
        assertEquals("x", typed.toString())
    }

    // ---- the row of alternates ------------------------------------------------------------------------------

    private fun hold(label: String): Pair<Float, Float> {
        val at = centre(label)
        send(MotionEvent.ACTION_DOWN, at.first, at.second)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        return at
    }

    @Test
    fun `holding a vowel opens its accents, commonest first`() {
        hold("e")
        assertEquals(listOf("3", "é", "è", "ê", "ë", "ę", "ė", "ē", "ě"), view.popupItems)
    }

    /** The top row prints a digit in the corner, and that promise is kept: the digit is what is offered first. */
    @Test
    fun `the corner digit comes before the accents`() {
        hold("e")
        assertEquals("3", view.popupItems.first())
        assertEquals(0, view.popupChoice)
    }

    @Test
    fun `letting go without moving takes the first, as the keycap said`() {
        val (x, y) = hold("e")
        send(MotionEvent.ACTION_UP, x, y)
        assertEquals("3", typed.toString())
    }

    @Test
    fun `sliding along the row picks a different one`() {
        val (x, y) = hold("e")
        send(MotionEvent.ACTION_MOVE, x + 40 * density, y)
        assertTrue("nothing was chosen", view.popupChoice > 0)
        val chosen = view.popupItems[view.popupChoice]
        send(MotionEvent.ACTION_UP, x + 40 * density, y)
        assertEquals(chosen, typed.toString())
    }

    /**
     * A key whose only alternate is the corner digit has nothing to choose between.
     *
     * "q" and "p" have no accents in any language Android ships a keyboard for, so holding them simply gives the
     * digit, with no row to slide along.
     */
    @Test
    fun `a key with one alternate just gives it, with no row to choose from`() {
        val (x, y) = hold("q")
        assertEquals(emptyList<String>(), view.popupItems)
        assertEquals("1", typed.toString())
        send(MotionEvent.ACTION_UP, x, y)
        assertEquals("letting go must not type it twice", "1", typed.toString())
    }

    @Test
    fun `a letter off the top row offers only its accents`() {
        hold("a")
        assertEquals(listOf("á", "â", "ä", "à", "ã", "æ", "å", "ā"), view.popupItems)
    }

    @Test
    fun `a capital key offers capital accents`() {
        show(FieldRules(), shift = Shift.ONCE)
        hold("A")
        assertEquals("Á", view.popupItems.first())
    }

    @Test
    fun `the row closes when the finger lifts`() {
        val (x, y) = hold("e")
        send(MotionEvent.ACTION_UP, x, y)
        assertEquals(emptyList<String>(), view.popupItems)
    }

    /**
     * Every alternate has to be reachable, including for the keys at the very edges.
     *
     * "q" and "p" sit against the sides, and a row of accents centred on them would hang half off the screen.
     */
    @Test
    fun `the row stays inside the keyboard, even at the edges`() {
        for (label in listOf("a", "l", "z", "e", "o", "u", "i", "s")) {
            hold(label)
            val boxes = view.popupBoxes
            assertTrue("no row opened for $label", boxes.isNotEmpty())
            for (box in boxes) {
                assertTrue("$label: an alternate runs off the left", box.left >= -0.5f)
                assertTrue("$label: an alternate runs off the right", box.right <= view.width + 0.5f)
                assertTrue("$label: an alternate is above the keyboard", box.top >= -0.5f)
                assertTrue(
                    "$label: an alternate is only ${box.width / density} dp wide",
                    box.width / density >= 24f && box.height / density >= 24f,
                )
            }
            val (x, y) = centre(label)
            send(MotionEvent.ACTION_UP, x, y)
            typed.setLength(0)
        }
    }

    /** Sliding between letters is how a fast thumb corrects itself: the letter it lets go on is the one meant. */
    @Test
    fun `sliding from one letter to another types the one you land on`() {
        val (x, y) = centre("q")
        val (farX, farY) = centre("p")
        send(MotionEvent.ACTION_DOWN, x, y)
        send(MotionEvent.ACTION_MOVE, farX, farY)
        send(MotionEvent.ACTION_UP, farX, farY)
        assertEquals("p", typed.toString())
    }

    /** Sliding off a modifier is the only way to take back pressing it, so that one still cancels. */
    @Test
    fun `sliding off shift takes it back`() {
        val (x, y) = centre("⇧")
        val (farX, farY) = centre("z")
        send(MotionEvent.ACTION_DOWN, x, y)
        send(MotionEvent.ACTION_MOVE, farX, farY)
        send(MotionEvent.ACTION_UP, farX, farY)
        assertEquals(0, shifts)
        assertEquals("", typed.toString())
    }

    /**
     * Typing fast, in the way that broke it: a thumb lands a few pixels off the key and lifts a few more pixels
     * away, often in the seam between two keys, where nothing at all used to be typed.
     */
    @Test
    fun `a drifting thumb still types the letter`() {
        val gap = 3 * density
        for (letter in listOf("t", "h", "e")) {
            val (x, y) = centre(letter)
            val box = view.placements.first { it.key.label == letter }.box
            send(MotionEvent.ACTION_DOWN, x, y)
            send(MotionEvent.ACTION_MOVE, box.right + gap, y)      // into the seam on the right
            send(MotionEvent.ACTION_UP, box.right + gap, y)
        }
        assertEquals("the", typed.toString())
    }

    /** The seam between two keys, and the rounded corner under the bottom row: a finger lands there all the time. */
    @Test
    fun `landing in the gap between keys hits the nearer one`() {
        val f = view.placements.first { it.key.label == "f" }.box
        val g = view.placements.first { it.key.label == "g" }.box
        val seam = (f.right + g.left) / 2
        val y = (f.top + f.bottom) / 2
        send(MotionEvent.ACTION_DOWN, seam - 1f, y)
        send(MotionEvent.ACTION_UP, seam - 1f, y)
        send(MotionEvent.ACTION_DOWN, seam + 1f, y)
        send(MotionEvent.ACTION_UP, seam + 1f, y)
        assertEquals("fg", typed.toString())
    }

    /** A wide space bar and a moving thumb: a space that turns into a cursor jump is the worst of the lot. */
    @Test
    fun `a thumb that drifts across the space bar still types a space`() {
        val (x, y) = centre("space")
        send(MotionEvent.ACTION_DOWN, x, y)
        send(MotionEvent.ACTION_MOVE, x + 20 * density, y)
        send(MotionEvent.ACTION_UP, x + 20 * density, y)
        assertEquals(" ", typed.toString())
        assertEquals(0, cursorSteps)
    }

    /**
     * Two thumbs and a capital letter: the board is rebuilt the instant the capital lands, because one-shot shift
     * turns itself off. The thumb already resting on the next letter must not lose it.
     */
    @Test
    fun `a finger already down survives the board being rebuilt`() {
        val (hx, hy) = centre("h")
        send(MotionEvent.ACTION_DOWN, hx, hy)
        show(FieldRules(), shift = Shift.OFF)   // what onText does after a capital
        send(MotionEvent.ACTION_UP, hx, hy)
        assertEquals("h", typed.toString())
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
        send(MotionEvent.ACTION_MOVE, x + 70 * density, y)
        send(MotionEvent.ACTION_UP, x + 70 * density, y)
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

    /** The toolbar above the keys: hide, and the editing every field supports. */
    @Test
    fun `the toolbar reaches the listener`() {
        val provider = view.accessibilityNodeProvider!!
        // The toolbar's virtual views come after the keys, in the order the view places them.
        val keys = view.placements
        for (id in keys.size until keys.size + view.toolbarPlacements.size) {
            provider.performAction(id, AccessibilityNodeInfo.ACTION_CLICK, null)
        }
        assertEquals(listOf("hide", "emoji", "selectAll", "copy", "paste"), toolbar)
    }

    @Test
    fun `every key is offered to a screen reader, and can be pressed by one`() {
        val provider = view.accessibilityNodeProvider
        assertNotNull("no accessibility node provider", provider)
        val letterQ = view.placements.indexOfFirst { it.key.label == "q" }

        val node = provider!!.createAccessibilityNodeInfo(letterQ)
        assertEquals("q", node?.contentDescription)

        provider.performAction(letterQ, AccessibilityNodeInfo.ACTION_CLICK, null)
        assertEquals("q", typed.toString())
    }

    @Test
    fun `a screen reader hears names, not glyphs`() {
        val provider = view.accessibilityNodeProvider!!
        val backspace = view.placements.indexOfFirst { it.key.kind == KeyKind.BACKSPACE }
        val description = provider.createAccessibilityNodeInfo(backspace)?.contentDescription.toString()
        assertTrue("backspace reads as $description", description.startsWith("Backspace"))
    }
}
