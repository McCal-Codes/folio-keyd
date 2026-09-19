package com.mccal.folio.keys

import android.content.Context
import android.text.InputType
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
 * What the keys actually do to the text.
 *
 * The keyboard's whole job happens on the other side of an [InputConnection], so this drives [TextActions] against a
 * real one and reads the text that comes out. A mistake here is the kind that means typing does nothing in someone's
 * mail app, or that shift sticks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeysServiceTest {

    /** A real editable behind the connection, plus a note of the actions an app would have been asked to perform. */
    private class Field(view: View) : BaseInputConnection(view, true) {
        val performed = mutableListOf<Int>()
        override fun performEditorAction(actionCode: Int): Boolean {
            performed += actionCode
            return true
        }
    }

    private class FakeIme(override val connection: InputConnection?) : Ime {
        override var editorInfo: EditorInfo? = null
        var switches = 0
        var shown: List<Row> = emptyList()
        var shift = Shift.OFF

        override fun switchKeyboard() { switches++ }
        var hides = 0
        override fun hideKeyboard() { hides++ }
        override fun show(rows: List<Row>, shift: Shift) {
            shown = rows
            this.shift = shift
        }
    }

    private lateinit var ime: FakeIme
    private lateinit var actions: TextActions
    private lateinit var field: Field

    private val text get() = field.editable.toString()
    private val labels get() = ime.shown.flatten().map { it.label }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        field = Field(View(context))
        ime = FakeIme(field)
        actions = TextActions(ime)
        start(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_UNSPECIFIED)
    }

    private fun start(inputType: Int, imeOptions: Int) {
        ime.editorInfo = EditorInfo().also {
            it.inputType = inputType
            it.imeOptions = imeOptions
        }
        actions.startInput(ime.editorInfo)
    }

    private fun type(word: String) = word.forEach { actions.onText(it.toString()) }

    // ---- typing -------------------------------------------------------------------------------------------------

    @Test
    fun `typing reaches the field`() {
        type("hello")
        assertEquals("hello", text)
    }

    @Test
    fun `the space key puts in a space`() {
        type("hi")
        actions.onText(" ")
        type("there")
        assertEquals("hi there", text)
    }

    @Test
    fun `backspace takes one character`() {
        type("cat")
        actions.onBackspace()
        assertEquals("ca", text)
    }

    @Test
    fun `backspace on an empty field is harmless`() {
        actions.onBackspace()
        assertEquals("", text)
    }

    @Test
    fun `a word swipe takes the word and its trailing space`() {
        type("hello world ")
        actions.onDeleteWord()
        assertEquals("hello ", text)
    }

    @Test
    fun `a word swipe on an empty field is harmless`() {
        actions.onDeleteWord()
        assertEquals("", text)
    }

    // ---- shift --------------------------------------------------------------------------------------------------

    @Test
    fun `shift lasts one letter`() {
        actions.onShift()
        assertEquals(Shift.ONCE, actions.shift)
        assertTrue("the keys should show upper case", labels.contains("Q"))

        actions.onText("Q")
        assertEquals(Shift.OFF, actions.shift)
        assertTrue("the keys should be back to lower case", labels.contains("q"))
    }

    @Test
    fun `shift twice locks, and a third tap lets go`() {
        actions.onShift()
        actions.onShift()
        assertEquals(Shift.LOCKED, actions.shift)
        actions.onText("A")
        assertEquals("a locked shift stays on", Shift.LOCKED, actions.shift)
        actions.onShift()
        assertEquals(Shift.OFF, actions.shift)
    }

    // ---- the field's rules --------------------------------------------------------------------------------------

    @Test
    fun `the action key asks the app to do what it asked for`() {
        start(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH)
        actions.onAction()
        assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), field.performed)
        assertEquals("the action must not also type anything", "", text)
    }

    @Test
    fun `a field that takes several lines gets a new line instead`() {
        start(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            EditorInfo.IME_ACTION_SEND,
        )
        type("one")
        actions.onAction()
        type("two")
        assertEquals("one\ntwo", text)
        assertTrue("a multiline field should not be sent", field.performed.isEmpty())
    }

    @Test
    fun `an email field brings its own keys`() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, EditorInfo.IME_ACTION_GO)
        assertTrue(labels.containsAll(listOf("@", ".com", "Go")))
    }

    @Test
    fun `a number field opens on the pad`() {
        start(InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_DONE)
        assertEquals(Layer.NUMBERS, actions.layer)
        assertTrue("letters have no business in a number field", !labels.contains("q"))
    }

    @Test
    fun `a password field never starts capitalised`() {
        start(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            EditorInfo.IME_ACTION_DONE,
        )
        assertEquals(Shift.OFF, actions.shift)
        assertTrue(actions.rules.ephemeral)
        type("secret")
        assertEquals("it still types; it just refuses to remember", "secret", text)
    }

    @Test
    fun `switching layer keeps the field's rules`() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, EditorInfo.IME_ACTION_GO)
        actions.onLayer(Layer.NUMBERS)
        assertEquals(Layer.NUMBERS, actions.layer)
        assertTrue("the action key should still say Go", labels.contains("Go"))
        assertEquals("the at sign is already on this layer", 1, labels.count { it == "@" })
    }

    // ---- nothing to type into -----------------------------------------------------------------------------------

    @Test
    fun `nothing blows up when there is no field`() {
        val loose = TextActions(FakeIme(null))
        loose.onText("a")
        loose.onBackspace()
        loose.onDeleteWord()
        loose.onAction()
        loose.onCursor(2)
        loose.onShift()
    }

    @Test
    fun `the toolbar asks the field to do the editing`() {
        type("hello")
        actions.onSelectAll()
        actions.onCopy()
        actions.onHide()
        assertEquals(1, ime.hides)
        assertEquals("the toolbar must not type anything", "hello", text)
    }

    @Test
    fun `the globe is passed to Android`() {
        actions.onSwitchKeyboard()
        assertEquals(1, ime.switches)
    }
}
