package com.mccal.folio.keys

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The field rules and the rows that come out of them. These are the decisions a keyboard gets wrong in ways people
 * notice — an email field with no @, a password field that behaves like any other — so they are checked here rather
 * than by typing on a phone and hoping.
 */
class LayoutsTest {

    private fun rows(rules: FieldRules, layer: Layer = Layer.LETTERS, shifted: Boolean = false) =
        Layouts.rows(layer, shifted, rules)

    private fun labels(rules: FieldRules) = rows(rules).flatten().map { it.label }

    @Test
    fun `a plain field returns`() {
        val rules = Layouts.rules(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_UNSPECIFIED)
        assertEquals(FieldKind.TEXT, rules.kind)
        assertEquals("return", rules.actionLabel)
        assertFalse(rules.password)
    }

    @Test
    fun `the action key says what the app asked for`() {
        fun label(option: Int) = Layouts.rules(InputType.TYPE_CLASS_TEXT, option).actionLabel
        assertEquals("Go", label(EditorInfo.IME_ACTION_GO))
        assertEquals("Search", label(EditorInfo.IME_ACTION_SEARCH))
        assertEquals("Send", label(EditorInfo.IME_ACTION_SEND))
        assertEquals("Done", label(EditorInfo.IME_ACTION_DONE))
    }

    @Test
    fun `the app's own wording wins over the action`() {
        val rules = Layouts.rules(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEND, "Post")
        assertEquals("Post", rules.actionLabel)
    }

    @Test
    fun `a field that takes several lines gets a return, not an action`() {
        val rules = Layouts.rules(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            EditorInfo.IME_ACTION_SEND,
        )
        assertTrue(rules.multiline)
        assertEquals("return", rules.actionLabel)
    }

    @Test
    fun `an email field has an at sign and dot com`() {
        val rules = Layouts.rules(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            EditorInfo.IME_ACTION_GO,
        )
        assertEquals(FieldKind.EMAIL, rules.kind)
        assertTrue(labels(rules).containsAll(listOf("@", ".com")))
    }

    @Test
    fun `a web address gets a slash instead of a comma`() {
        val rules = Layouts.rules(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            EditorInfo.IME_ACTION_GO,
        )
        assertEquals(FieldKind.URL, rules.kind)
        val labels = labels(rules)
        assertTrue(labels.contains("/"))
        assertFalse(labels.contains(","))
    }

    @Test
    fun `every kind of password field is recognised`() {
        val text = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val visible = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val web = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        val pin = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        for (type in listOf(text, visible, web, pin)) {
            val rules = Layouts.rules(type, EditorInfo.IME_ACTION_DONE)
            assertTrue("password not seen in $type", rules.password)
            assertTrue("password must never be learned from", rules.ephemeral)
        }
    }

    @Test
    fun `an app can ask for nothing to be learned`() {
        val rules = Layouts.rules(
            InputType.TYPE_CLASS_TEXT,
            EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )
        assertFalse(rules.password)
        assertTrue(rules.ephemeral)
    }

    @Test
    fun `a number field gets the pad, not the letters`() {
        val rules = Layouts.rules(InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_DONE)
        val rows = rows(rules)
        assertEquals(FieldKind.NUMBER, rules.kind)
        assertTrue(rows.flatten().none { it.label == "q" })
        assertTrue(rows.flatten().map { it.label }.containsAll(listOf("1", "5", "9", "0")))
    }

    @Test
    fun `shift changes the letters, not the layout`() {
        val rules = FieldRules()
        val lower = rows(rules, shifted = false).flatten().map { it.label }
        val upper = rows(rules, shifted = true).flatten().map { it.label }
        assertEquals(lower.size, upper.size)
        assertTrue(lower.contains("q"))
        assertTrue(upper.contains("Q"))
    }

    @Test
    fun `the letters row keeps shift and backspace at its ends`() {
        val row = rows(FieldRules())[2]
        assertEquals(KeyKind.SHIFT, row.first().kind)
        assertEquals(KeyKind.BACKSPACE, row.last().kind)
    }

    @Test
    fun `every layer offers a way back to the letters`() {
        for (layer in listOf(Layer.NUMBERS, Layer.SYMBOLS)) {
            val keys = rows(FieldRules(), layer).flatten()
            assertTrue(
                "no way back from $layer",
                keys.any { it.kind == KeyKind.LAYER && it.output == Layer.LETTERS.name },
            )
        }
    }

    @Test
    fun `no key is given a share of the row it cannot use`() {
        for (layer in Layer.entries) {
            for (row in rows(FieldRules(), layer)) {
                assertTrue("empty row in $layer", row.isNotEmpty())
                row.forEach { assertTrue("weight ${it.weight} on ${it.label}", it.weight > 0f) }
            }
        }
    }

    /**
     * The space bar names the language when there is room, and says "space" when there isn't.
     *
     * Six languages and one globe key: without this, the only way to find out which was on was to type a word and
     * see what came back corrected.
     */
    @Test
    fun `the space bar names the language when it fits`() {
        assertEquals("English", spaceLabel(200f, Language.ENGLISH, "space"))
        assertEquals("Français", spaceLabel(200f, Language.FRENCH, "space"))
        assertEquals("Português", spaceLabel(200f, Language.PORTUGUESE, "space"))
        // A narrow bar keeps the plain word rather than shrinking a name nobody could read.
        assertEquals("space", spaceLabel(90f, Language.PORTUGUESE, "space"))
        assertEquals("space", spaceLabel(SPACE_NAME_MIN_DP - 1f, Language.ENGLISH, "space"))
        assertEquals("English", spaceLabel(SPACE_NAME_MIN_DP, Language.ENGLISH, "space"))
    }

    /** Every language Keyd ships has a name short enough to sit on the bar; a longer one would fall back. */
    @Test
    fun `every language's own name fits the bar`() {
        for (language in Language.entries) {
            assertEquals(
                "${language.tag} should name itself on a wide bar",
                language.ownName, spaceLabel(240f, language, "space"),
            )
        }
    }
}
