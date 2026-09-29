package com.mccal.folio.keys

import android.content.Context
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Shift and backspace delete the character after the cursor. The text is a real editable behind a real connection,
 * as in [KeysServiceTest]; the fingers are real touches, as in [KeyboardViewTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ForwardDeleteTest {

    private class FakeIme(override val connection: InputConnection?) : Ime {
        override var editorInfo: EditorInfo? = null
        var shift = Shift.OFF
        override fun switchKeyboard() = Unit
        override fun hideKeyboard() = Unit
        override fun show(rows: List<Row>, shift: Shift) { this.shift = shift }
        override fun suggest(word: String) = Unit
        override fun learn(word: String) = Unit
        override fun showEmoji(showing: Boolean) = Unit
        override fun showClipboard(showing: Boolean) = Unit
    }

    private lateinit var field: BaseInputConnection
    private lateinit var ime: FakeIme
    private lateinit var actions: TextActions
    private val text get() = field.editable.toString()

    @Before
    fun setUp() {
        field = BaseInputConnection(View(ApplicationProvider.getApplicationContext<Context>()), true)
        ime = FakeIme(field)
        actions = TextActions(ime)
        start()
    }

    /** A plain field, or one that asks for capitals at the start of a sentence. */
    private fun start(capitals: Boolean = false) {
        ime.editorInfo = EditorInfo().also {
            it.inputType = InputType.TYPE_CLASS_TEXT or (if (capitals) InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else 0)
        }
        actions.startInput(ime.editorInfo)
    }

    /** [before] the cursor and [after] it, with the cursor between. */
    private fun text(before: String, after: String) {
        val editable = field.editable!!
        editable.replace(0, editable.length, before + after)
        android.text.Selection.setSelection(editable, before.length)
    }

    private val cursor get() = android.text.Selection.getSelectionStart(field.editable)

    /** A tap on backspace, as the keyboard sends it. */
    private fun backspace(shiftHeld: Boolean = false) {
        actions.onBackspaceStart(shiftHeld)
        actions.onBackspace()
    }

    @Test
    fun `shift held then backspace takes the letter after the cursor`() {
        text("abc", "def")
        actions.onShift()
        backspace(shiftHeld = true)
        assertEquals("abcef", text)
        assertEquals(3, cursor)
        assertEquals("shift is used up, as a letter uses it", Shift.OFF, actions.shift)
        backspace()
        assertEquals("the next one goes backwards again", "abef", text)
    }

    @Test
    fun `a shift tapped for a capital, then backspace, still fixes the typo behind it`() {
        text("I met teh ", "")
        actions.onShift()
        backspace()
        assertEquals("I met teh", text)
    }

    @Test
    fun `holding shift down while backspace is pressed does the same, with shift off`() {
        text("abc", "def")
        backspace(shiftHeld = true)
        assertEquals("abcef", text)
    }

    @Test
    fun `an emoji goes whole, skin tone, flag and all`() {
        for (emoji in listOf("👍🏽", "🇫🇷", "👨‍👩‍👧", "é")) {
            text("a", "${emoji}b")
            backspace(shiftHeld = true)
            assertEquals("after $emoji", "ab", text)
        }
    }

    @Test
    fun `at the end of the text it does nothing`() {
        text("abc", "")
        actions.onShift()
        backspace(shiftHeld = true)
        assertEquals("abc", text)
        assertEquals(Shift.OFF, actions.shift)
    }

    @Test
    fun `a selection goes as it always does`() {
        text("abcdef", "")
        android.text.Selection.setSelection(field.editable, 1, 4)
        actions.onShift()
        backspace()
        assertEquals("aef", text)
        assertEquals(Shift.OFF, actions.shift)
    }

    @Test
    fun `held with shift it keeps deleting forward, though shift went with the first`() {
        text("ab", "cdefg")
        actions.onShift()
        actions.onBackspaceStart(true)
        repeat(3) { actions.onBackspaceRepeat() }
        assertEquals("abfg", text)
        assertEquals(Shift.OFF, actions.shift)
        // The next press, without shift, goes back to deleting backwards.
        actions.onBackspaceStart(false)
        actions.onBackspaceRepeat()
        assertEquals("afg", text)
    }

    @Test
    fun `the capital at the start of a sentence is not shift tapped by hand`() {
        start(capitals = true)
        text("Hi. ", "there")
        start(capitals = true)
        android.text.Selection.setSelection(field.editable, 4)
        assertEquals("shift came on by itself", Shift.ONCE, actions.shift)
        backspace()
        assertEquals("the space before goes, as it always did", "Hi.there", text)
    }

    @Test
    fun `caps lock deletes backwards`() {
        text("abc", "def")
        actions.onShift()
        actions.onShift()
        assertEquals(Shift.LOCKED, actions.shift)
        backspace()
        assertEquals("abdef", text)
        assertEquals(Shift.LOCKED, actions.shift)
    }

    @Test
    fun `the emoji panel's backspace always deletes backwards`() {
        text("ab", "cd")
        actions.onShift()
        actions.onBackspaceStart(false)
        actions.onEmojiPanel()
        actions.onBackspace()
        assertEquals("acd", text)
    }

    // ---- on the keys ---------------------------------------------------------------------------------------------

    @Test
    fun `a finger holding shift while another presses backspace starts a forward delete`() {
        val t = Touches()
        val shift = t.centre(t.kind(KeyKind.SHIFT).box)
        val back = t.centre(t.kind(KeyKind.BACKSPACE).box)
        val properties = arrayOf(0, 1).map { id ->
            MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }.toTypedArray()
        val coords = arrayOf(shift, back).map { (x, y) ->
            MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        }.toTypedArray()
        fun send(action: Int, count: Int) = MotionEvent.obtain(0, 0, action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            .let { t.view.onTouchEvent(it); it.recycle() }
        send(MotionEvent.ACTION_DOWN, 1)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
        send(MotionEvent.ACTION_UP, 1)
        assertEquals(listOf("start:true", "backspace"), t.heard)
        assertTrue("letting go of shift afterwards does not turn it on", "shift" !in t.heard)
    }

    @Test
    fun `backspace on its own starts an ordinary delete`() {
        val t = Touches()
        t.tap(t.kind(KeyKind.BACKSPACE).box)
        assertEquals(listOf("start:false", "backspace"), t.heard)
    }
}
