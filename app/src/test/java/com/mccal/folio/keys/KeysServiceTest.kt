package com.mccal.folio.keys

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val keys = mutableListOf<Int>()
        /** An app that doesn't handle its own action answers false. */
        var handles = true

        /** How often the app was asked what is selected: a blocking call, slow in a browser. */
        var selectedAsks = 0
        override fun getSelectedText(flags: Int): CharSequence? {
            selectedAsks++
            return super.getSelectedText(flags)
        }
        override fun performEditorAction(actionCode: Int): Boolean {
            performed += actionCode
            return handles
        }

        override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) keys += event.keyCode
            return super.sendKeyEvent(event)
        }
    }

    private class FakeIme(override val connection: InputConnection?) : Ime {
        override var editorInfo: EditorInfo? = null
        var switches = 0
        var shown: List<Row> = emptyList()
        var shift = Shift.OFF
        var emojiShowing = false
        var clipboardShowing = false
        var suggestedFor = mutableListOf<String>()
        var taught = mutableListOf<String>()

        override fun switchKeyboard() { switches++ }
        var hides = 0
        override fun hideKeyboard() { hides++ }
        override fun show(rows: List<Row>, shift: Shift) {
            shown = rows
            this.shift = shift
        }

        override fun showClipboard(showing: Boolean) {
            clipboardShowing = showing
        }

        var copies = 0
        override fun copied() { copies++ }

        override fun showEmoji(showing: Boolean) {
            emojiShowing = showing
        }

        override fun suggest(word: String) {
            suggestedFor += word
        }

        val previousFor = mutableListOf<String>()
        override fun suggest(word: String, previous: String) {
            previousFor += previous
            suggest(word)
        }

        override fun learn(word: String) {
            taught += word
        }

        val remembered = mutableListOf<String>()
        override fun rememberEmoji(emoji: String) { remembered += emoji }

        val corrections = mutableListOf<Boolean>()
        override fun learn(word: String, corrected: Boolean) {
            corrections += corrected
            learn(word)
        }

        val stood = mutableListOf<Pair<String, String>>()
        val putBack = mutableListOf<String>()
        val answers = mutableListOf<Pair<Insights.Offer, Boolean>>()
        override fun fixStood(typed: String, replacement: String) { stood += typed to replacement }
        override fun putBack(typed: String) { putBack += typed }
        override fun answered(offer: Insights.Offer, accepted: Boolean) { answers += offer to accepted }

        var quietGaps = 0
        override fun quietGap() { quietGaps++ }
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

    // ---- text that did not come from Keyd -------------------------------------------------------------------------

    /** Puts text in the field the way a paste, an app's autofill or `adb input text` does: straight in, past Keyd. */
    private fun fromOutside(value: String) {
        val editable = field.editable!!
        editable.append(value)
        android.text.Selection.setSelection(editable, editable.length)
    }

    /** With Suggestions off the strip is told of the gap after a word on its own, so a gesture tip can take it. */
    @Test
    fun `with suggestions off the gap after a word is still offered to a tip`() {
        actions.settings = Settings(suggestions = false)
        type("hello")
        assertEquals("not in the middle of a word", 0, ime.quietGaps)
        type(" ")
        assertEquals(1, ime.quietGaps)
    }

    @Test
    fun `with suggestions on the gap goes to what might come next, as before`() {
        type("hello ")
        assertEquals(0, ime.quietGaps)
        assertEquals("hello", ime.previousFor.last())
    }

    @Test
    fun `a password field or an address has no quiet gap`() {
        actions.settings = Settings(suggestions = false)
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)) {
            start(InputType.TYPE_CLASS_TEXT or variation, EditorInfo.IME_ACTION_UNSPECIFIED)
            type("hello ")
        }
        assertEquals(0, ime.quietGaps)
    }

    @Test
    fun `a key typed after text already in the field adds to it`() {
        fromOutside("Folded or open, it fits the wind")
        start(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_UNSPECIFIED)
        type("o")
        assertEquals("Folded or open, it fits the windo", text)
    }

    @Test
    fun `text pasted in while typing is kept when typing goes on`() {
        type("hi ")
        fromOutside("Folded or open, it fits the wind")
        type("ow")
        assertEquals("hi Folded or open, it fits the window", text)
    }

    @Test
    fun `the toolbar's Copy tells the keyboard to look at the clipboard`() {
        // It used to rely on the next field opening, so what someone copied from the toolbar wasn't in the history
        // until they had moved on - the one moment they were most likely to want it back.
        actions.onCopy()
        assertEquals(1, ime.copies)
    }

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

    // ---- what the settings change -------------------------------------------------------------------------------

    @Test
    fun `turning suggestions off stops the strip being asked at all`() {
        actions.settings = Settings(suggestions = false)
        type("hello")
        assertTrue("asked about ${ime.suggestedFor}", ime.suggestedFor.all { it.isEmpty() })
    }

    /**
     * Switching the strip off switches correcting off with it.
     *
     * The worst of the four combinations would be a keyboard showing no sign of the feature while still quietly
     * changing words, so the two are tied together on purpose.
     */
    @Test
    fun `no strip means nothing is corrected either`() {
        actions.settings = Settings(suggestions = false, autocorrect = true)
        type("teh")
        actions.offered(Verdict("teh", "the", misspelled = false))
        type(" ")
        assertEquals("teh ", text)
    }

    @Test
    fun `turning correcting off leaves the strip working`() {
        actions.settings = Settings(suggestions = true, autocorrect = false)
        type("teh")
        actions.offered(Verdict("teh", "the", misspelled = false))
        type(" ")
        assertEquals("teh ", text)
        assertTrue("the strip should still be asked", ime.suggestedFor.any { it == "teh" })
    }

    @Test
    fun `turning learning off stops words being kept`() {
        actions.settings = Settings(learn = false)
        type("mccal ")
        assertEquals(emptyList<String>(), ime.taught)
    }

    // ---- double space -------------------------------------------------------------------------------------------

    @Test
    fun `two spaces after a word become a full stop`() {
        type("hello  ")
        assertEquals("hello. ", text)
    }

    @Test
    fun `two spaces on their own stay two spaces`() {
        type("  ")
        assertEquals("  ", text)
    }

    @Test
    fun `two spaces after punctuation stay two spaces`() {
        type("hello.  ")
        assertEquals("hello.  ", text)
    }

    @Test
    fun `a full stop is not added when the setting is off`() {
        actions.settings = Settings(doubleSpaceFullStop = false)
        type("hello  ")
        assertEquals("hello  ", text)
    }

    @Test
    fun `spaces far apart are not a double space`() {
        type("a b ")
        assertEquals("a b ", text)
    }

    // ---- correcting on its own ----------------------------------------------------------------------------------

    @Test
    fun `a word the suggestion thread flagged is corrected when it is finished`() {
        type("teh")
        actions.offered(Verdict("teh", "the", misspelled = false))
        type(" ")
        assertEquals("the ", text)
    }

    @Test
    fun `the ending that finished the word is kept`() {
        type("teh")
        actions.offered(Verdict("teh", "the", misspelled = false))
        type(".")
        assertEquals("the.", text)
    }

    @Test
    fun `text before the corrected word is left alone`() {
        type("well teh")
        actions.offered(Verdict("teh", "the", misspelled = false))
        type(" ")
        assertEquals("well the ", text)
    }

    @Test
    fun `a word that was corrected is marked so, and one left alone is not`() {
        type("teh")
        actions.offered(Verdict("teh", "the", misspelled = false))
        type(" wifi ")
        assertEquals(listOf(true, false), ime.corrections)
    }

    @Test
    fun `i on its own becomes I`() {
        type("so i")
        type(" ")
        assertEquals("so I ", text)
    }

    @Test
    fun `i as a numeral is left alone`() {
        type("part i: ")
        type("i) ")
        type("i. ")
        assertEquals("part i: i) i. ", text)
    }

    @Test
    fun `an underlined word is only learned once the next word begins`() {
        type("zorp")
        actions.offered(Verdict("zorp", null, misspelled = true))
        type(" ")
        assertTrue(ime.taught.isEmpty())
        type("a")
        assertEquals(listOf("zorp"), ime.taught)
    }

    @Test
    fun `an underlined word that is backspaced into is not learned`() {
        type("zorp")
        actions.offered(Verdict("zorp", null, misspelled = true))
        type(" ")
        actions.onBackspace()
        type("s ")
        assertFalse(ime.taught.contains("zorp"))
    }

    @Test
    fun `a predicted word after text the app changed still gets its own space`() {
        type("I want ")
        field.editable!!.delete(field.editable!!.length - 1, field.editable!!.length)
        actions.onSuggestion("to")
        assertEquals("I want to ", text)
    }

    @Test
    fun `i is left alone with capitals off`() {
        actions.settings = Settings(autoCapitalise = false)
        start(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_UNSPECIFIED)
        type("so i ")
        assertEquals("so i ", text)
    }

    @Test
    fun `i becomes I even with autocorrect off, and backspace puts it back`() {
        actions.settings = Settings(autocorrect = false)
        start(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_UNSPECIFIED)
        type("so i'm ")
        assertEquals("so I'm ", text)
        actions.onBackspace()
        assertEquals("so i'm ", text)
        assertTrue(ime.stood.isEmpty() && ime.putBack.isEmpty())
    }

    @Test
    fun `the word before goes with every question to the strip`() {
        type("hello wor")
        assertEquals("hello", ime.previousFor.last())
        type("ld. ")
        assertEquals(SENTENCE_START, ime.previousFor.last())
        type("a, ")
        assertEquals("", ime.previousFor.last())
    }

    /** The one key that undoes it. Without this, correcting on its own would not be worth doing at all. */
    @Test
    fun `backspace straight after a correction puts back what was typed`() {
        type("teh")
        actions.offered(Verdict("teh", "the", misspelled = false))
        type(" ")
        actions.onBackspace()
        assertEquals("teh ", text)
    }

    @Test
    fun `backspace after anything else deletes as usual`() {
        type("teh")
        actions.offered(Verdict("teh", "the", misspelled = false))
        type(" ")
        type("x")
        actions.onBackspace()
        assertEquals("the ", text)
    }

    @Test
    fun `a word with no correction offered is left alone`() {
        type("mccal")
        actions.offered(Verdict("mccal", null, misspelled = false))
        type(" ")
        assertEquals("mccal ", text)
    }

    /** A correction worked out for an earlier word must not be applied to a later one. */
    @Test
    fun `a stale correction is not applied`() {
        actions.offered(Verdict("teh", "the", misspelled = false))
        type("cat ")
        assertEquals("cat ", text)
    }

    @Test
    fun `nothing is corrected in a password field`() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_UNSPECIFIED)
        type("teh")
        actions.offered(Verdict("teh", "the", misspelled = false))
        type(" ")
        assertEquals("teh ", text)
    }

    // ---- counting what it fixes ---------------------------------------------------------------------------------

    private fun correctTeh(ending: String = " ") {
        type("teh")
        actions.offered(Verdict("teh", "the", misspelled = false))
        type(ending)
    }

    @Test
    fun `a correction counts as a fix once the next word begins without an undo`() {
        correctTeh()
        // Not yet: the next key could still be the backspace that takes it back.
        assertEquals(emptyList<Pair<String, String>>(), ime.stood)
        type("c")
        assertEquals(listOf("teh" to "the"), ime.stood)
        type("at ")
        assertEquals(1, ime.stood.size)
        assertEquals(emptyList<String>(), ime.putBack)
    }

    @Test
    fun `undoing a correction counts as putting it back, not as a fix`() {
        correctTeh()
        actions.onBackspace()
        assertEquals("teh ", text)
        assertEquals(listOf("teh"), ime.putBack)
        type("cat ")
        assertEquals(emptyList<Pair<String, String>>(), ime.stood)
    }

    @Test
    fun `a correction followed by moving the cursor is neither`() {
        correctTeh()
        actions.onCursor(-2)
        type("x")
        assertEquals(emptyList<Pair<String, String>>(), ime.stood)
        assertEquals(emptyList<String>(), ime.putBack)
    }

    @Test
    fun `nothing is counted when the app asked not to be learned from`() {
        start(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        correctTeh()
        assertEquals("the ", text)
        type("c")
        correctTeh()
        actions.onBackspace()
        assertEquals(emptyList<Pair<String, String>>(), ime.stood)
        assertEquals(emptyList<String>(), ime.putBack)
    }

    @Test
    fun `nothing is counted in a password field`() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_UNSPECIFIED)
        correctTeh()
        type("c")
        actions.onBackspace()
        assertEquals(emptyList<Pair<String, String>>(), ime.stood)
        assertEquals(emptyList<String>(), ime.putBack)
    }

    @Test
    fun `nothing is counted with learning off`() {
        actions.settings = Settings(learn = false)
        correctTeh()
        type("c")
        correctTeh()
        actions.onBackspace()
        assertEquals(emptyList<Pair<String, String>>(), ime.stood)
        assertEquals(emptyList<String>(), ime.putBack)
    }

    @Test
    fun `the strip's answer goes to the keyboard service`() {
        actions.onOffer(Insights.Offer("teh", "the"), accepted = true)
        actions.onOffer(Insights.Offer("Folio", null), accepted = false)
        assertEquals(listOf(Insights.Offer("teh", "the") to true, Insights.Offer("Folio", null) to false), ime.answers)
    }

    // ---- underlining what it has never heard of ------------------------------------------------------------------

    /** The span the editor draws its squiggle from, if there is one on the text. */
    private fun misspelledSpans(): List<android.text.style.SuggestionSpan> {
        val editable = field.editable ?: return emptyList()
        return editable.getSpans(0, editable.length, android.text.style.SuggestionSpan::class.java)
            .filter { it.flags and android.text.style.SuggestionSpan.FLAG_MISSPELLED != 0 }
    }

    @Test
    fun `a word nothing has heard of is underlined`() {
        type("zxqwv")
        actions.offered(Verdict("zxqwv", null, misspelled = true, suggestions = listOf("zxqwv")))
        type(" ")
        assertEquals("the text itself is unchanged", "zxqwv ", text)
        assertEquals(1, misspelledSpans().size)
    }

    @Test
    fun `an ordinary word is not underlined`() {
        type("hello")
        actions.offered(Verdict("hello", null, misspelled = false))
        type(" ")
        assertEquals(emptyList<Any>(), misspelledSpans())
    }

    /** The span carries the answers, so tapping the word offers them in the editor's own menu. */
    @Test
    fun `the underline brings the suggestions with it`() {
        type("teh")
        actions.offered(Verdict("teh", null, misspelled = true, suggestions = listOf("the", "ten", "tea")))
        type(" ")
        val span = misspelledSpans().single()
        assertEquals(listOf("the", "ten", "tea"), span.suggestions.toList())
    }

    @Test
    fun `nothing is underlined in a password field`() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_UNSPECIFIED)
        type("zxqwv")
        actions.offered(Verdict("zxqwv", null, misspelled = true))
        type(" ")
        assertEquals(emptyList<Any>(), misspelledSpans())
    }

    @Test
    fun `nothing is underlined when the setting is off`() {
        actions.settings = Settings(spellCheck = false)
        type("zxqwv")
        actions.offered(Verdict("zxqwv", null, misspelled = true))
        type(" ")
        assertEquals(emptyList<Any>(), misspelledSpans())
    }

    /** Correcting and underlining are two answers to one question; a corrected word is not also a wrong one. */
    @Test
    fun `a word that was corrected is not also underlined`() {
        type("teh")
        actions.offered(Verdict("teh", "the", misspelled = true, suggestions = listOf("the")))
        type(" ")
        assertEquals("the ", text)
        assertEquals(emptyList<Any>(), misspelledSpans())
    }

    /** A verdict about an earlier word must never be applied to a later one. */
    @Test
    fun `a stale verdict does not underline the wrong word`() {
        actions.offered(Verdict("zxqwv", null, misspelled = true))
        type("hello ")
        assertEquals(emptyList<Any>(), misspelledSpans())
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
        type("hello.")
        actions.onSuggestion("hello")
        assertEquals("a suggestion with no word, and nowhere a word begins, must do nothing", "hello.", text)
    }

    /** After a space the strip holds what might come next, and taking one is typing it. */
    @Test
    fun `a predicted word taken after a space goes in with its own space`() {
        type("I want ")
        actions.onSuggestion("to")
        assertEquals("I want to ", text)
        assertEquals("to", ime.previousFor.last())
    }

    @Test
    fun `a predicted word uses up a capital waiting for one letter`() {
        type("hi. ")
        actions.onShift()
        actions.onSuggestion("The")
        assertEquals("hi. The ", text)
        assertEquals(Shift.OFF, ime.shift)
    }

    @Test
    fun `the strip is only asked what comes next where a word begins`() {
        type("hello")
        type(".")
        assertEquals("", ime.previousFor.last())
        type(" ")
        assertEquals(SENTENCE_START, ime.previousFor.last())
        actions.onBackspace()
        assertEquals("", ime.previousFor.last())
    }

    @Test
    fun `nothing is predicted where suggestions are off`() {
        actions.settings = Settings(suggestions = false)
        start(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_UNSPECIFIED)
        type("I want ")
        assertEquals("", ime.previousFor.last())
    }

    @Test
    fun `nothing is predicted in a password field`() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_UNSPECIFIED)
        type("I want ")
        assertEquals("", ime.previousFor.last())
    }

    @Test
    fun `nothing is predicted in an address`() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_UNSPECIFIED)
        type("keyd ")
        assertEquals("", ime.previousFor.last())
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

    // ---- return, search and go ------------------------------------------------------------------------------------

    private val url = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    private val email = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS

    @Test
    fun `search runs the app's search`() {
        start(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH)
        type("cats")
        actions.onAction()
        assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), field.performed)
        assertTrue(field.keys.isEmpty())
    }

    @Test
    fun `an app that asks for plain enter gets enter, not its action`() {
        start(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        actions.onAction()
        assertTrue(field.performed.isEmpty())
        assertEquals(listOf(android.view.KeyEvent.KEYCODE_ENTER), field.keys)
    }

    /** "keyd.dev": the period ends the word "keyd", and fixing it would send the browser somewhere else. */
    @Test
    fun `nothing is corrected in a web address`() {
        start(url, EditorInfo.IME_ACTION_GO)
        type("keyd")
        actions.offered(Verdict("keyd", correction = "keys", misspelled = true))
        type(".dev")
        assertEquals("keyd.dev", text)
    }

    @Test
    fun `nothing is corrected in an email address`() {
        start(email, EditorInfo.IME_ACTION_NEXT)
        type("mccal")
        actions.offered(Verdict("mccal", correction = "metal", misspelled = true))
        type("@")
        assertEquals("mccal@", text)
    }

    @Test
    fun `addresses are never learned`() {
        start(url, EditorInfo.IME_ACTION_GO)
        type("keyd.dev ")
        assertTrue(ime.taught.isEmpty())
    }

    @Test
    fun `two spaces stay two spaces in an address`() {
        start(url, EditorInfo.IME_ACTION_GO)
        type("keyd  ")
        assertEquals("keyd  ", text)
    }

    @Test
    fun `an app that asks for no suggestions gets none`() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 0)
        ime.suggestedFor.clear()
        type("hel")
        assertTrue(ime.suggestedFor.all { it.isEmpty() })
    }

    // ---- the capital at the start of a sentence -------------------------------------------------------------------

    private val sentences = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

    @Test
    fun `a new field starts with a capital`() {
        start(sentences, 0)
        assertEquals(Shift.ONCE, ime.shift)
    }

    @Test
    fun `a period and a space bring the capital back`() {
        start(sentences, 0)
        type("hi. ")
        assertEquals(Shift.ONCE, ime.shift)
    }

    @Test
    fun `mid-sentence a space does not`() {
        start(sentences, 0)
        type("hi there ")
        assertEquals(Shift.OFF, ime.shift)
    }

    /** Found typing "the quick brown fox" on a phone: after deleting everything, the T came out lowercase. */
    @Test
    fun `deleting back to an empty field brings the capital back`() {
        start(sentences, 0)
        type("ab")
        assertEquals(Shift.OFF, ime.shift)
        actions.onBackspace()
        actions.onBackspace()
        assertEquals("", text)
        assertEquals(Shift.ONCE, ime.shift)
    }

    @Test
    fun `caps lock is left alone`() {
        start(sentences, 0)
        actions.onShift()   // ONCE to LOCKED
        type("ab ")
        assertEquals(Shift.LOCKED, ime.shift)
    }

    @Test
    fun `a field that asks for no capitals never gets one`() {
        start(InputType.TYPE_CLASS_TEXT, 0)
        type("hi. ")
        assertEquals(Shift.OFF, ime.shift)
    }

    // ---- the emoji at the end of the strip ----------------------------------------------------------------------

    @Test
    fun `the strip's emoji goes in after the word, which stays as typed`() {
        type("pizza")
        actions.onSuggestedEmoji("🍕")
        assertEquals("pizza 🍕 ", text)
        assertEquals(listOf("🍕"), ime.remembered)
        assertTrue("the word is not learned", "pizza" !in ime.taught)
        // The space after the emoji was put in by the tap, so a space typed now is not the second of a double space.
        type("yum ")
        assertTrue(text, text.endsWith("🍕 yum "))
    }

    @Test
    fun `with nothing typed, the emoji does nothing`() {
        actions.onSuggestedEmoji("🍕")
        assertEquals("", text)
        assertTrue(ime.remembered.isEmpty())
    }

    // ---- what backspace asks the app ----------------------------------------------------------------------------

    /** Selects [start] to [end] in the field and tells the keyboard, as the editor's selection report would. */
    private fun select(start: Int, end: Int) {
        android.text.Selection.setSelection(field.editable, start, end)
        actions.selectionChanged(start, end)
        field.selectedAsks = 0
    }

    @Test
    fun `backspace does not ask the app for a selection it already knows is not there`() {
        type("hello")
        select(5, 5)
        actions.onBackspace()
        assertEquals("hell", text)
        assertEquals(0, field.selectedAsks)
    }

    @Test
    fun `a selection it knows about goes whole, without asking`() {
        type("hello world")
        select(0, 6)
        actions.onBackspace()
        assertEquals("world", text)
        assertEquals(0, field.selectedAsks)
        // Until the editor says where the cursor went, the next press asks rather than trusting the old selection.
        actions.onBackspace()
        assertEquals(1, field.selectedAsks)
    }

    @Test
    fun `shift and backspace ask at most once`() {
        type("hello")
        android.text.Selection.setSelection(field.editable, 1, 3)
        field.selectedAsks = 0
        actions.onBackspaceStart(true)
        actions.onBackspace()
        assertEquals("hlo", text)
        assertEquals("not known, so asked, but only once", 1, field.selectedAsks)
    }

    @Test
    fun `when the editor has not said where the selection is, backspace asks`() {
        type("hello")
        android.text.Selection.setSelection(field.editable, 0, 2)
        field.selectedAsks = 0
        actions.onBackspace()
        assertEquals("llo", text)
        assertEquals(1, field.selectedAsks)
    }

    // ---- punctuation after a taken suggestion -------------------------------------------------------------------

    @Test
    fun `a full stop after a taken suggestion goes before its space`() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, EditorInfo.IME_ACTION_UNSPECIFIED)
        type("hel")
        actions.onSuggestion("hello")
        type(".")
        assertEquals("hello. ", text)
        assertEquals("the next sentence starts with a capital", Shift.ONCE, actions.shift)
        type("Hi")
        assertEquals("hello. Hi", text)
    }

    @Test
    fun `every mark that sits against its word gives the space way`() {
        for (mark in listOf(",", "?", "!", ":", ";", ")")) {
            start(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_UNSPECIFIED)
            field.editable!!.clear()
            type("hel")
            actions.onSuggestion("hello")
            type(mark)
            assertEquals("hello$mark ", text)
        }
    }

    @Test
    fun `only the first key after the suggestion moves the space`() {
        type("hel")
        actions.onSuggestion("hello")
        type("a.")
        assertEquals("hello a.", text)
    }

    @Test
    fun `a letter after a taken suggestion leaves its space alone`() {
        type("hel")
        actions.onSuggestion("hello")
        type("w")
        assertEquals("hello w", text)
    }

    @Test
    fun `a predicted word takes punctuation the same way`() {
        type("see ")
        actions.onSuggestion("you")
        type("!")
        assertEquals("see you! ", text)
    }

    @Test
    fun `an autocorrect's space gives way too`() {
        actions.offered(Verdict("teh", "the", misspelled = false))
        type("teh ")
        type(",")
        assertEquals("the, ", text)
    }

    @Test
    fun `a space typed by hand is kept`() {
        type("hello ")
        type(".")
        assertEquals("hello .", text)
    }

    @Test
    fun `not once the cursor has moved away and back`() {
        type("hel")
        actions.selectionChanged(3, 3)
        actions.onSuggestion("hello")
        actions.selectionChanged(6, 6)
        android.text.Selection.setSelection(field.editable, 2)
        actions.selectionChanged(2, 2)
        android.text.Selection.setSelection(field.editable, 6)
        actions.selectionChanged(6, 6)
        type(".")
        assertEquals("hello .", text)
    }

    @Test
    fun `not when the cursor has moved somewhere else`() {
        fromOutside("one ")
        start(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_UNSPECIFIED)
        type("hel")
        actions.onSuggestion("hello")
        android.text.Selection.setSelection(field.editable, 4)
        type(".")
        assertEquals("one .hello ", text)
    }

    @Test
    fun `reports from before the suggestion was taken do not count as a move`() {
        type("hel")
        actions.selectionChanged(3, 3)
        actions.onSuggestion("hello")
        actions.selectionChanged(2, 2)
        type(".")
        assertEquals("hello. ", text)
    }

    @Test
    fun `never in an address`() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_UNSPECIFIED)
        type("exa")
        actions.onSuggestion("example")
        type(".")
        assertEquals("example .", text)
    }

    @Test
    fun `two spaces still make a full stop after the word that follows`() {
        type("hel")
        actions.onSuggestion("hello")
        type("there  ")
        assertEquals("hello there. ", text)
    }
}
