package com.mccal.folio.keys

import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Holding a word in the strip to stop it being suggested: the menu under it with real touches, the same through a
 * screen reader, and what the service does with the answer against the dictionary that ships.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ForgetSuggestionTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("keys", Context.MODE_PRIVATE)

    private val t = Touches()
    private val view get() = t.view
    private val forgotten = mutableListOf<String>()

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        val touches = view.listener!!
        view.listener = object : KeyboardView.Listener by touches {
            override fun onForgetSuggestion(word: String) {
                forgotten += word
            }
        }
        view.suggestions = listOf("Folo", "Folio", "Foloo")
    }

    private fun word(label: String) = view.toolbarPlacements.first { it.key.label == label }
    private fun menu() = view.forgetMenuItems.single()

    // ---- touch ----------------------------------------------------------------------------------------------------

    @Test
    fun `holding a word opens the menu under the strip`() {
        t.hold(word("Foloo").box)
        assertEquals("Don’t suggest “Foloo”", menu().key.label)
        assertEquals(KeyKind.FORGET, menu().key.kind)
        assertTrue("under the strip", menu().box.top >= word("Foloo").box.bottom)
        assertTrue("a full fingertip tall", menu().box.height / t.density >= 48f)
        assertTrue("inside the keys", menu().box.left >= 0f && menu().box.right <= view.width)
        assertEquals("nothing is typed by the hold", emptyList<String>(), t.heard)
    }

    @Test
    fun `sliding down onto it and letting go turns the word down`() {
        t.hold(word("Foloo").box)
        val (x, y) = t.centre(menu().box)
        t.send(MotionEvent.ACTION_MOVE, x, y)
        t.send(MotionEvent.ACTION_UP, x, y)
        assertEquals(listOf("Foloo"), forgotten)
        assertEquals("gone from the strip at once", listOf("Folo", "Folio"), view.suggestions)
        assertTrue(view.forgetMenuItems.isEmpty())
        assertFalse("not typed either", "suggestion" in t.heard)
    }

    @Test
    fun `letting go on the word leaves the menu open for a tap`() {
        val (x, y) = t.hold(word("Foloo").box)
        t.send(MotionEvent.ACTION_UP, x, y)
        assertEquals(1, view.forgetMenuItems.size)
        assertFalse("the held word is not typed", "suggestion" in t.heard)
        t.tap(menu().box)
        assertEquals(listOf("Foloo"), forgotten)
    }

    @Test
    fun `password manager chips arriving close an open menu`() {
        val (x, y) = t.hold(word("Foloo").box)
        t.send(MotionEvent.ACTION_UP, x, y)
        assertEquals(1, view.forgetMenuItems.size)
        view.autofilling = true
        assertTrue(view.forgetMenuItems.isEmpty())
    }

    @Test
    fun `turning down the last word leaves the toolbar`() {
        view.suggestions = listOf("Folo", "Foloo")
        val (x, y) = t.hold(word("Foloo").box)
        t.send(MotionEvent.ACTION_UP, x, y)
        t.tap(menu().box)
        assertEquals(emptyList<String>(), view.suggestions)
        assertEquals(KeyKind.HIDE, view.toolbarPlacements.first().key.kind)
    }

    @Test
    fun `the typed word has no menu, and holding it only takes it`() {
        val (x, y) = t.hold(word("Folo").box)
        assertTrue(view.forgetMenuItems.isEmpty())
        t.send(MotionEvent.ACTION_UP, x, y)
        assertEquals(listOf("suggestion"), t.heard)
    }

    @Test
    fun `after a space every word in the strip can be turned down`() {
        view.typedFirst = false
        view.suggestions = listOf("the", "a", "to")
        t.hold(word("the").box)
        assertEquals("Don’t suggest “the”", menu().key.label)
    }

    @Test
    fun `the emoji and the mic have no menu`() {
        view.voiceAvailable = true
        view.suggestedEmoji = SuggestedEmoji("🍕", "pizza")
        val (x, y) = t.hold(view.toolbarPlacements.first { it.key.kind == KeyKind.SUGGESTED_EMOJI }.box)
        assertTrue(view.forgetMenuItems.isEmpty())
        t.send(MotionEvent.ACTION_UP, x, y)
        t.hold(view.toolbarPlacements.first { it.key.kind == KeyKind.VOICE }.box)
        assertTrue(view.forgetMenuItems.isEmpty())
    }

    @Test
    fun `a touch outside only closes it`() {
        val (x, y) = t.hold(word("Foloo").box)
        t.send(MotionEvent.ACTION_UP, x, y)
        t.tap(t.key("a").box)
        assertTrue(view.forgetMenuItems.isEmpty())
        assertEquals("the key under the touch is not typed", "", t.typed.toString())
        assertEquals(emptyList<String>(), forgotten)
    }

    @Test
    fun `a new field, a new strip or hiding closes it`() {
        t.hold(word("Foloo").box)
        view.forgetTouches()
        assertTrue(view.forgetMenuItems.isEmpty())

        t.hold(word("Foloo").box)
        view.suggestions = listOf("Folio", "Folios")
        assertTrue(view.forgetMenuItems.isEmpty())
    }

    // ---- screen readers -------------------------------------------------------------------------------------------

    @Test
    fun `a screen reader holds a word and turns it down`() {
        val provider = view.accessibilityNodeProvider!!
        val id = view.placements.size + view.toolbarPlacements.indexOfFirst { it.key.label == "Foloo" }
        val node = provider.createAccessibilityNodeInfo(id)!!
        val hold = node.actionList.first { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }
        assertEquals("Don’t suggest", hold.label)
        val typedId = view.placements.size
        assertFalse(
            "the typed word cannot be turned down",
            provider.createAccessibilityNodeInfo(typedId)!!.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK },
        )

        assertTrue(provider.performAction(id, AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
        val all = view.placements.size + view.toolbarPlacements.size + view.railPlacements.size
        val item = provider.createAccessibilityNodeInfo(all)!!
        assertEquals("Don’t suggest “Foloo”", item.contentDescription)
        assertEquals("android.widget.Button", item.className)
        assertTrue(provider.performAction(all, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(listOf("Foloo"), forgotten)
    }

    // ---- what the service does with it ----------------------------------------------------------------------------

    private fun KeysService.settle() = repeat(4) {
        shadowOf(suggestionLooper).idleFor(Duration.ofSeconds(1))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun service(): Pair<KeysService, KeyboardView> {
        val service = Robolectric.buildService(KeysService::class.java).create().get()
        val root = service.onCreateInputView() as ViewGroup
        val keys = (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<KeyboardView>().single()
        service.onStartInputView(EditorInfo().also { it.inputType = InputType.TYPE_CLASS_TEXT }, false)
        service.settle()
        return service to keys
    }

    @Test
    fun `a dictionary word turned down leaves the strip and is never a correction`() {
        val (service, keys) = service()
        service.suggest("hel", "")
        service.settle()
        assertTrue(keys.suggestions.toString(), "help" in keys.suggestions)

        service.forget("help")
        service.settle()
        assertEquals("help", prefs.getString(NeverSuggest.KEY, null))
        service.suggest("hel", "")
        service.settle()
        assertFalse(keys.suggestions.toString(), "help" in keys.suggestions)

        val words = Dictionary.load(context, Language.ENGLISH)
        val never = NeverSuggest.decode("help\nfriend")
        assertEquals("friend", Suggestions.correction("freind", words, null))
        assertNull(Suggestions.correction("freind", words, null, never = never))
        // Still a word: typed out in full it is neither underlined nor changed.
        assertTrue(Suggestions.known("friend", words))
        assertNull(Suggestions.correction("friend", words, null, never = never))
    }

    @Test
    fun `a learned word is forgotten rather than listed`() {
        prefs.edit().putString("learnedWords", "foloo:4\nmccal:2").commit()
        val (service, keys) = service()
        service.suggest("folo", "")
        service.settle()
        assertTrue(keys.suggestions.toString(), "foloo" in keys.suggestions)

        service.forget("foloo")
        service.settle()
        assertEquals(0, Learned.decode(prefs.getString("learnedWords", null)).count("foloo"))
        assertEquals("the others stay", 2, Learned.decode(prefs.getString("learnedWords", null)).count("mccal"))
        assertNull("nothing to list: the dictionary never had it", prefs.getString(NeverSuggest.KEY, null))
        service.suggest("folo", "")
        service.settle()
        assertFalse(keys.suggestions.toString(), "foloo" in keys.suggestions)
    }

    @Test
    fun `forgetting everything it learned empties the list too`() {
        prefs.edit().putString(NeverSuggest.KEY, "help").commit()
        val (service, _) = service()
        service.forgetLearned()
        service.settle()
        assertNull(prefs.getString(NeverSuggest.KEY, null))
    }

    @Test
    fun `what might come next leaves a turned-down word out`() {
        val next = NextWords.read("i\twant have am".byteInputStream())
        assertEquals(listOf("want", "have", "am"), Suggestions.predict("i", next))
        assertEquals(listOf("have", "am"), Suggestions.predict("i", next, never = NeverSuggest.decode("want")))
    }

    /** The correction waiting for the space was worked out before the word was turned down, and goes with it. */
    @Test
    fun `a correction already waiting is dropped when its word is turned down`() {
        val field = android.view.inputmethod.BaseInputConnection(android.view.View(context), true)
        val asked = mutableListOf<String>()
        val ime = object : Ime {
            override val connection = field
            override val editorInfo = EditorInfo().also { it.inputType = InputType.TYPE_CLASS_TEXT }
            override fun switchKeyboard() = Unit
            override fun hideKeyboard() = Unit
            override fun show(rows: List<Row>, shift: Shift) = Unit
            override fun suggest(word: String) = Unit
            override fun learn(word: String) = Unit
            override fun showEmoji(showing: Boolean) = Unit
            override fun showClipboard(showing: Boolean) = Unit
            override fun forget(word: String) { asked += word }
        }
        val actions = TextActions(ime)
        actions.startInput(ime.editorInfo)
        "freind".forEach { actions.onText(it.toString()) }
        actions.offered(Verdict("freind", "friend", misspelled = true))
        actions.onForgetSuggestion("friend")
        actions.onText(" ")
        assertEquals(listOf("friend"), asked)
        assertEquals("freind ", field.editable.toString())
    }

    // ---- the list itself, and moving it to a new phone ------------------------------------------------------------

    @Test
    fun `the list is lowercase, has no repeats, and keeps its newest`() {
        val list = NeverSuggest()
        assertTrue(list.add("Foloo"))
        assertFalse(list.add("foloo"))
        assertTrue("May" in NeverSuggest.decode("may"))
        repeat(NeverSuggest.LIMIT) { list.add("w$it") }
        assertEquals(NeverSuggest.LIMIT, list.size)
        assertFalse("the oldest went first", "foloo" in list)
        assertEquals(list.encode(), NeverSuggest.decode(list.encode()).encode())
    }

    @Test
    fun `a backup carries the list, and importing only adds to it`() {
        val file = Backup.export(Learned(), Shortcuts(), NeverSuggest.decode("help\nfoloo"))
        val result = Backup.merge(file, Learned(), Shortcuts(), NeverSuggest.decode("the")) as Backup.Result.Added
        assertEquals(listOf("the", "help", "foloo"), result.never.all())
        // A file from before 0.4.1 has no list, and leaves this one as it is.
        val old = Backup.merge(Backup.export(Learned(), Shortcuts()).replace("\"neverSuggest\"", "\"x\""),
            Learned(), Shortcuts(), NeverSuggest.decode("the")) as Backup.Result.Added
        assertEquals(listOf("the"), old.never.all())
    }

    @Test
    fun `a phrase turned down comes back from a backup`() {
        val list = NeverSuggest().apply { add("on my way") }
        val file = Backup.export(Learned(), Shortcuts(), list)
        val result = Backup.merge(file, Learned(), Shortcuts(), NeverSuggest()) as Backup.Result.Added
        assertEquals(listOf("on my way"), result.never.all())
        val tabbed = file.replace("on my way", "on\\tmy way")
        val rejected = Backup.merge(tabbed, Learned(), Shortcuts(), NeverSuggest()) as Backup.Result.Added
        assertEquals("a tab is not something the strip offers", emptyList<String>(), rejected.never.all())
    }
}
