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
        var emojiShowing = false
        var suggestedFor = mutableListOf<String>()
        var taught = mutableListOf<String>()

        override fun switchKeyboard() { switches++ }
        var hides = 0
        override fun hideKeyboard() { hides++ }
        override fun show(rows: List<Row>, shift: Shift) {
            shown = rows
            this.shift = shift
        }

        override fun showEmoji(showing: Boolean) {
            emojiShowing = showing
        }

        override fun suggest(word: String) {
            suggestedFor += word
        }

        override fun learn(word: String) {
            taught += word
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

    // ---- the word being typed -----------------------------------------------------------------------------------

    /** The last thing the strip was asked about, which is what it would be showing. */
    private val asked get() = ime.suggestedFor.last()

    @Test
    fun `the word grows as it is typed`() {
        type("hel")
        assertEquals("hel", asked)
    }

    @Test
    fun `a space ends the word`() {
        type("hello ")
        assertEquals("", asked)
    }

    /** A full stop or a bracket ends a word just as a space does. */
    @Test
    fun `punctuation ends the word`() {
        type("hello.")
        assertEquals("", asked)
        type("again)")
        assertEquals("", asked)
    }

    @Test
    fun `an apostrophe is part of the word`() {
        type("don't")
        assertEquals("don't", asked)
    }

    @Test
    fun `backspace shortens the word`() {
        type("hello")
        actions.onBackspace()
        assertEquals("hell", asked)
        actions.onBackspaceRepeat()
        assertEquals("hel", asked)
    }

    @Test
    fun `moving the cursor means we no longer know the word`() {
        type("hello")
        actions.onCursor(-2)
        assertEquals("", asked)
    }

    @Test
    fun `a new field starts with no word`() {
        type("hello")
        start(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_UNSPECIFIED)
        assertEquals("", asked)
    }

    /** Nothing about a password goes to the dictionary, not even to be looked up. */
    @Test
    fun `a password field is never asked about`() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_UNSPECIFIED)
        type("hunter2")
        assertTrue("asked about ${ime.suggestedFor}", ime.suggestedFor.all { it.isEmpty() })
    }

    // ---- correcting on its own ----------------------------------------------------------------------------------

    @Test
    fun `a word the suggestion thread flagged is corrected when it is finished`() {
        type("teh")
        actions.offered("teh", "the")
        type(" ")
        assertEquals("the ", text)
    }

    @Test
    fun `the ending that finished the word is kept`() {
        type("teh")
        actions.offered("teh", "the")
        type(".")
        assertEquals("the.", text)
    }

    @Test
    fun `text before the corrected word is left alone`() {
        type("well teh")
        actions.offered("teh", "the")
        type(" ")
        assertEquals("well the ", text)
    }

    /** The one key that undoes it. Without this, correcting on its own would not be worth doing at all. */
    @Test
    fun `backspace straight after a correction puts back what was typed`() {
        type("teh")
        actions.offered("teh", "the")
        type(" ")
        actions.onBackspace()
        assertEquals("teh ", text)
    }

    @Test
    fun `backspace after anything else deletes as usual`() {
        type("teh")
        actions.offered("teh", "the")
        type(" ")
        type("x")
        actions.onBackspace()
        assertEquals("the ", text)
    }

    @Test
    fun `a word with no correction offered is left alone`() {
        type("mccal")
        actions.offered("mccal", null)
        type(" ")
        assertEquals("mccal ", text)
    }

    /** A correction worked out for an earlier word must not be applied to a later one. */
    @Test
    fun `a stale correction is not applied`() {
        actions.offered("teh", "the")
        type("cat ")
        assertEquals("cat ", text)
    }

    @Test
    fun `nothing is corrected in a password field`() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_UNSPECIFIED)
        type("teh")
        actions.offered("teh", "the")
        type(" ")
        assertEquals("teh ", text)
    }

    // ---- learning -----------------------------------------------------------------------------------------------

    @Test
    fun `a finished word is offered for learning`() {
        type("mccal ")
        assertEquals(listOf("mccal"), ime.taught)
    }

    /** A word still being typed is a prefix, and every prefix of every word is not worth remembering. */
    @Test
    fun `a word still being typed is not learned`() {
        type("mcca")
        assertEquals(emptyList<String>(), ime.taught)
    }

    @Test
    fun `a full stop finishes a word just as a space does`() {
        type("folio.")
        assertEquals(listOf("folio"), ime.taught)
    }

    @Test
    fun `pressing return finishes the word`() {
        type("folio")
        actions.onAction()
        assertEquals(listOf("folio"), ime.taught)
    }

    /** Nothing typed into a password field is kept, whatever else is true. */
    @Test
    fun `nothing is learned from a password field`() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_UNSPECIFIED)
        type("hunter2 correcthorse ")
        assertEquals(emptyList<String>(), ime.taught)
    }

    /** An app can ask not to be learned from, and that is not negotiable by any setting of ours. */
    @Test
    fun `nothing is learned when the app asked not to be`() {
        start(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        type("something private ")
        assertEquals(emptyList<String>(), ime.taught)
    }

    // ---- taking a suggestion ------------------------------------------------------------------------------------

    @Test
    fun `taking a suggestion replaces the word and adds a space`() {
        type("teh")
        actions.onSuggestion("the")
        assertEquals("the ", text)
    }

    @Test
    fun `taking a suggestion leaves the text before it alone`() {
        type("well teh")
        actions.onSuggestion("the")
        assertEquals("well the ", text)
    }

    @Test
    fun `there is nothing to replace when no word is being typed`() {
        type("hello ")
        actions.onSuggestion("hello")
        assertEquals("a suggestion with no word must do nothing", "hello ", text)
    }

    /**
     * The replacement deletes by count, so it must first check that the count still means what it meant.
     *
     * If something else changed the field while a word was being typed, deleting three characters would eat three
     * characters of somebody's sentence.
     */
    @Test
    fun `a suggestion is refused when the text is not what we thought`() {
        type("teh")
        field.editable?.clear()
        field.editable?.append("something else entirely")
        actions.onSuggestion("the")
        assertEquals("something else entirely", text)
    }

    @Test
    fun `the word is finished with after it is taken`() {
        type("teh")
        actions.onSuggestion("the")
        assertEquals("", asked)
    }

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
