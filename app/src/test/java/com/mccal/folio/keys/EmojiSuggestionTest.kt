package com.mccal.folio.keys

import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The emoji a word is the name of, offered at the end of the strip: "pizza" offers the pizza.
 *
 * Against the names and the word lists that ship, because what goes wrong here is in the data: a name word like
 * "with" that would put an emoji after every sentence, or a keyword that outranks the emoji actually called that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class EmojiSuggestionTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("keys", Context.MODE_PRIVATE)

    private val english by lazy { EmojiSearch.load(context, Language.ENGLISH) }
    private val words by lazy { Dictionary.load(context, Language.ENGLISH) }

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    // ---- which emoji ----------------------------------------------------------------------------------------------

    @Test
    fun `a word that is an emoji's name offers that emoji`() {
        assertEquals("🍕", english.exact("pizza"))
        assertEquals("🐕", english.exact("dog"))
        assertEquals("🔥", english.exact("fire"))
        assertEquals("🍕", english.exact("Pizza"))
    }

    /** "heart" is a keyword of the house as well; the one called a heart wins, and the first of those in the grid. */
    @Test
    fun `a name beats a keyword`() {
        assertEquals("❤️", english.exact("heart"))
    }

    /** "corazón" is in the name of the face with heart eyes too, which comes first in the grid. */
    @Test
    fun `the shorter name wins`() {
        assertEquals("❤️", EmojiSearch.load(context, Language.SPANISH).exact("corazón"))
    }

    @Test
    fun `only a whole word counts`() {
        assertNull(english.exact("piz"))
        assertNull(english.exact("pizzas"))
        assertNull(english.exact("red heart"))
    }

    @Test
    fun `each language has its own names`() {
        val spanish = EmojiSearch.load(context, Language.SPANISH)
        assertEquals("🍕", spanish.exact("pizza"))
        assertEquals("❤️", spanish.exact("corazón"))
        assertEquals("❤️", spanish.exact("corazon"))
    }

    @Test
    fun `the commonest words never offer one`() {
        for (word in listOf("you", "the", "and", "with", "like")) {
            assertNull(word, Suggestions.emoji(word, words, english))
        }
        assertEquals("🍕", Suggestions.emoji("pizza", words, english))
        assertNull("the names are not there yet", Suggestions.emoji("pizza", words, null))
    }

    // ---- the setting ----------------------------------------------------------------------------------------------

    @Test
    fun `it is on unless turned off, and turned off it stays off`() {
        assertTrue(Settings.load(prefs).suggestEmoji)
        Settings(suggestEmoji = false).save(prefs)
        assertFalse(Settings.load(prefs).suggestEmoji)
        assertTrue("the report lists it", DevLog.switches(Settings.load(prefs)).any { it.startsWith("suggestEmoji") })
    }

    @Test
    fun `the smart typing page has the switch, under suggestions`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        fun all(): List<View> {
            val out = mutableListOf<View>()
            fun walk(v: View) { out += v; if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
            walk(activity.window.decorView)
            return out
        }
        fun label(text: String) = all().filterIsInstance<TextView>().firstOrNull { it.text.toString() == text }
        fun row(text: String): View {
            var target: View = label(text) ?: throw AssertionError("no \"$text\" on screen")
            while (!target.isClickable && target.parent is View) target = target.parent as View
            return target
        }
        row("Smart typing").performClick()
        assertNotNull(label(activity.getString(R.string.settings_suggest_emoji_sub)))
        assertNotNull(label("SUGGESTIONS"))
        val target = row(activity.getString(R.string.settings_suggest_emoji))
        fun find(v: View): Switch? = v as? Switch
            ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        assertTrue(find(target)!!.isChecked)
        target.performClick()
        assertFalse(Settings.load(prefs).suggestEmoji)
    }

    // ---- in the keyboard ------------------------------------------------------------------------------------------

    private fun KeysService.settle() = repeat(4) {
        shadowOf(suggestionLooper).idleFor(Duration.ofSeconds(1))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun field(inputType: Int = InputType.TYPE_CLASS_TEXT) = EditorInfo().also { it.inputType = inputType }

    private fun keyboard(service: KeysService): KeyboardView {
        val root = service.onCreateInputView() as ViewGroup
        return (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<KeyboardView>().single()
    }

    /** The names are read the first time a word could use them, so the first word waits on nothing. */
    @Test
    fun `typing a word with an emoji puts it in the strip`() {
        val service = Robolectric.buildService(KeysService::class.java).create().get()
        val keys = keyboard(service)
        service.onStartInputView(field(), false)
        service.settle()
        repeat(2) {
            service.suggest("pizza", "")
            service.settle()
        }
        assertEquals(SuggestedEmoji("🍕", "pizza"), keys.suggestedEmoji)
        assertEquals("pizza", keys.suggestions.first())
        service.suggest("piz", "")
        service.settle()
        assertNull(keys.suggestedEmoji)
    }

    @Test
    fun `turned off, no emoji is offered`() {
        Settings(suggestEmoji = false).save(prefs)
        val service = Robolectric.buildService(KeysService::class.java).create().get()
        val keys = keyboard(service)
        service.onStartInputView(field(), false)
        repeat(2) {
            service.suggest("pizza", "")
            service.settle()
        }
        assertNull(keys.suggestedEmoji)
    }

    @Test
    fun `an emoji from the strip goes in the recents`() {
        val service = Robolectric.buildService(KeysService::class.java).create().get()
        keyboard(service)
        service.rememberEmoji("🍕")
        assertEquals(listOf("🍕"), Emoji.decode(prefs.getString("emojiRecents", null)))
    }
}
