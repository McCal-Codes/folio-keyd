package com.mccal.folio.keys

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Editing without leaving the keys: a swipe up on Z X C V A, a slide from shift, the toolbar someone arranged, and
 * the tools that show while text is selected.
 *
 * The gestures are real touches, as in [KeyboardViewTest]; what they do to text is checked against a real
 * connection, as in [KeysServiceTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class EditingTweaksTest {

    private lateinit var view: KeyboardView
    private val typed = StringBuilder()
    private val done = mutableListOf<String>()
    private var shifts = 0
    private var selectSteps = 0
    private var cursorSteps = 0

    private val density get() = view.resources.displayMetrics.density

    @Before
    fun setUp() {
        view = KeyboardView(ApplicationProvider.getApplicationContext<Context>())
        view.listener = object : KeyboardView.Listener {
            override fun onText(text: String) { typed.append(text) }
            override fun onBackspace() { done += "backspace" }
            override fun onDeleteWord() { done += "deleteWord" }
            override fun onShift() { shifts++ }
            override fun onLayer(layer: Layer) { done += "layer" }
            override fun onAction() { done += "action" }
            override fun onSwitchKeyboard() { done += "switch" }
            override fun onCursor(steps: Int) { cursorSteps += steps }
            override fun onSelectAll() { done += "selectAll" }
            override fun onCopy() { done += "copy" }
            override fun onPaste() { done += "paste" }
            override fun onCut() { done += "cut" }
            override fun onUndo() { done += "undo" }
            override fun onRedo() { done += "redo" }
            override fun onSelectMove(steps: Int) { selectSteps += steps }
            override fun onStyle(style: TextStyle) { done += "style:${style.name}" }
            override fun onClipboardPanel() { done += "clipboard" }
            override fun onHide() { done += "hide" }
            override fun onEmojiPanel() { done += "emoji" }
            override fun onSuggestion(word: String) { done += "suggestion:$word" }
        }
        show(FieldRules())
    }

    private fun show(rules: FieldRules, widthDp: Int = 411, language: Language = Language.ENGLISH) {
        view.rules = rules
        view.rows = Layouts.rows(Layer.LETTERS, false, rules, language = language)
        val width = (widthDp * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun centre(box: Box) = (box.left + box.right) / 2 to (box.top + box.bottom) / 2

    private fun key(label: String) = view.placements.first { it.key.label == label }

    private fun send(action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun tapAt(box: Box) {
        val (x, y) = centre(box)
        send(MotionEvent.ACTION_DOWN, x, y)
        send(MotionEvent.ACTION_UP, x, y)
    }

    private fun tapTool(kind: KeyKind) = tapAt(view.toolbarPlacements.first { it.key.kind == kind }.box)

    private fun flick(label: String, byDp: Float) {
        val (x, y) = centre(key(label).box)
        send(MotionEvent.ACTION_DOWN, x, y)
        send(MotionEvent.ACTION_MOVE, x, y + byDp * density)
        send(MotionEvent.ACTION_UP, x, y + byDp * density)
    }

    private fun labels() = view.toolbarPlacements.map { it.key.label }

    // ---- swipe up on Z X C V A -------------------------------------------------------------------------------

    @Test
    fun `a swipe up on the five keys edits instead of typing a capital`() {
        for ((letter, action) in listOf("z" to "undo", "x" to "cut", "c" to "copy", "v" to "paste", "a" to "selectAll")) {
            done.clear()
            flick(letter, -40f)
            assertEquals("a swipe up on $letter", listOf(action), done)
        }
        assertEquals("no capital, and no letter", "", typed.toString())
    }

    @Test
    fun `the other letters still flick up to a capital`() {
        flick("e", -40f)
        flick("b", -40f)
        assertEquals("EB", typed.toString())
        assertTrue(done.isEmpty())
    }

    @Test
    fun `with the edit swipes off a swipe up on c types C again`() {
        view.settings = Settings(editSwipes = false)
        flick("c", -40f)
        assertEquals("C", typed.toString())
        assertTrue(done.isEmpty())
    }

    @Test
    fun `flicking down still gives the corner character, and never edits`() {
        flick("e", 40f)
        assertEquals("3", typed.toString())
        typed.setLength(0)
        flick("c", 40f)
        assertFalse("a flick down must not copy", "copy" in done)
        assertFalse(typed.contains("C"))
    }

    @Test
    fun `the bubble says what letting go will do, and coming back takes it back`() {
        val (x, y) = centre(key("c").box)
        send(MotionEvent.ACTION_DOWN, x, y)
        assertEquals(listOf("c"), view.previewLabels)
        send(MotionEvent.ACTION_MOVE, x, y - 40 * density)
        assertEquals(listOf("Copy"), view.previewLabels)
        send(MotionEvent.ACTION_MOVE, x, y - 4 * density)
        assertEquals(emptyList<String>(), view.previewLabels)
        send(MotionEvent.ACTION_UP, x, y - 4 * density)
        assertTrue("brought back, nothing happens", done.isEmpty())
        assertEquals("", typed.toString())
    }

    @Test
    fun `the five keys carry an edit, and the top row keeps its digits`() {
        for (letter in listOf("z", "x", "c", "v", "a")) assertNotNull(letter, key(letter).key.edit)
        assertNull(key("b").key.edit)
        assertNull("the arrow is drawn, not a long-press alternate", key("c").key.hint)
        assertEquals("1", key("q").key.hint)
    }

    /** The edits follow the places, not the letters: W X C V and Q on AZERTY, Y X C V and A on QWERTZ. */
    @Test
    fun `other layouts put the edits on the same places`() {
        fun edits(language: Language) = Layouts.rows(Layer.LETTERS, false, FieldRules(), language = language)
            .flatten().filter { it.edit != null }.associate { it.label to it.edit }
        val order = listOf(EditSwipe.UNDO, EditSwipe.CUT, EditSwipe.COPY, EditSwipe.PASTE, EditSwipe.SELECT_ALL)
        assertEquals(listOf("z", "x", "c", "v", "a").zip(order).toMap(), edits(Language.ENGLISH))
        assertEquals(listOf("w", "x", "c", "v", "q").zip(order).toMap(), edits(Language.FRENCH))
        assertEquals(listOf("y", "x", "c", "v", "a").zip(order).toMap(), edits(Language.GERMAN))
        assertEquals(listOf("z", "x", "c", "v", "a").zip(order).toMap(), edits(Language.SPANISH))
        // Shifted, the same keys, now capitals; and the number row doesn't move them.
        assertEquals(EditSwipe.COPY, Layouts.rows(Layer.LETTERS, true, FieldRules()).flatten().first { it.label == "C" }.edit)
        assertEquals(
            EditSwipe.SELECT_ALL,
            Layouts.rows(Layer.LETTERS, false, FieldRules(), numberRow = true).flatten().first { it.label == "a" }.edit,
        )
        assertTrue(Layouts.rows(Layer.NUMBERS, false, FieldRules()).flatten().none { it.edit != null })
    }

    @Test
    fun `on AZERTY a swipe up on w undoes`() {
        show(FieldRules(), language = Language.FRENCH)
        flick("w", -40f)
        assertEquals(listOf("undo"), done)
    }

    // ---- slide from shift ------------------------------------------------------------------------------------

    private fun shiftBox() = view.placements.first { it.key.kind == KeyKind.SHIFT }.box

    @Test
    fun `a tap on shift is still shift`() {
        tapAt(shiftBox())
        assertEquals(1, shifts)
        assertEquals(0, selectSteps)
    }

    @Test
    fun `sliding from shift selects, and does not change shift`() {
        val (x, y) = centre(shiftBox())
        send(MotionEvent.ACTION_DOWN, x, y)
        send(MotionEvent.ACTION_MOVE, x + 90 * density, y)
        send(MotionEvent.ACTION_UP, x + 90 * density, y)
        assertTrue("it should have selected to the right, got $selectSteps", selectSteps > 0)
        assertEquals("shift should not have toggled", 0, shifts)
        assertEquals("the plain cursor is not what moves", 0, cursorSteps)
        assertEquals("", typed.toString())
    }

    @Test
    fun `a short drift on shift is not a selection`() {
        val (x, y) = centre(shiftBox())
        send(MotionEvent.ACTION_DOWN, x, y)
        send(MotionEvent.ACTION_MOVE, x + 10 * density, y)
        send(MotionEvent.ACTION_UP, x + 10 * density, y)
        assertEquals(0, selectSteps)
        assertEquals(1, shifts)
    }

    @Test
    fun `with shift select off a slide selects nothing`() {
        view.settings = Settings(shiftSelect = false)
        val (x, y) = centre(shiftBox())
        send(MotionEvent.ACTION_DOWN, x, y)
        send(MotionEvent.ACTION_MOVE, x + 90 * density, y)
        send(MotionEvent.ACTION_UP, x + 90 * density, y)
        assertEquals(0, selectSteps)
    }

    // ---- the toolbar you arrange -----------------------------------------------------------------------------

    @Test
    fun `the default toolbar, with Voice last when there is a voice keyboard`() {
        view.voiceAvailable = true
        assertEquals(listOf("Hide", "Emoji", "Undo", "Cursor pad", "Copy", "Paste", "Clipboard", "Voice"), labels())
    }

    @Test
    fun `a custom order is kept, with Hide always first`() {
        view.settings = Settings(toolbar = listOf(ToolKey.PASTE, ToolKey.REDO, ToolKey.UNDO, ToolKey.CUT))
        assertEquals(listOf("Hide", "Paste", "Redo", "Undo", "Cut"), labels())
    }

    @Test
    fun `undo, redo and cut on the toolbar reach the listener`() {
        view.settings = Settings(toolbar = listOf(ToolKey.UNDO, ToolKey.REDO, ToolKey.CUT))
        tapTool(KeyKind.UNDO)
        tapTool(KeyKind.REDO)
        tapTool(KeyKind.CUT)
        assertEquals(listOf("undo", "redo", "cut"), done)
    }

    @Test
    fun `a narrow window drops buttons from the end, never Hide`() {
        view.voiceAvailable = true
        show(FieldRules(), widthDp = 300)
        val full = listOf("Hide", "Emoji", "Undo", "Cursor pad", "Copy", "Paste", "Clipboard", "Voice")
        val shown = labels()
        assertTrue("$shown should be shorter than the full list", shown.size < full.size)
        assertEquals(full.take(shown.size), shown)
        for (placement in view.toolbarPlacements) {
            assertTrue("${placement.key.label} is ${placement.box.width / density} dp", placement.box.width / density >= 44f - 0.01f)
        }
    }

    @Test
    fun `the stored list ignores unknown names and repeats, and keeps no more than seven`() {
        assertEquals(listOf(ToolKey.COPY, ToolKey.PASTE), Settings.parseToolbar("COPY,SPARKLES,PASTE,COPY"))
        val all = ToolKey.entries.joinToString(",") { it.name }
        assertEquals(ToolKey.entries.take(7), Settings.parseToolbar(all))
        assertEquals(emptyList<ToolKey>(), Settings.parseToolbar(""))
    }

    private fun prefs() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("editing-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test
    fun `an old install keeps its choices when the toolbar becomes a list`() {
        val noPad = prefs().also { it.edit().putBoolean(Settings.CURSOR_PAD_KEY, false).commit() }
        assertEquals(
            listOf(ToolKey.EMOJI, ToolKey.UNDO, ToolKey.SELECT_ALL, ToolKey.COPY, ToolKey.PASTE, ToolKey.CLIPBOARD, ToolKey.VOICE),
            Settings.load(noPad).toolbar,
        )
        val noVoice = prefs().also { it.edit().putBoolean(Settings.VOICE_KEY, false).commit() }
        assertFalse(ToolKey.VOICE in Settings.load(noVoice).toolbar)
        assertEquals(Settings.DEFAULT_TOOLBAR, Settings.load(prefs()).toolbar)
    }

    @Test
    fun `saving writes the list and lets the old switches go`() {
        val p = prefs().also { it.edit().putBoolean(Settings.CURSOR_PAD_KEY, false).commit() }
        Settings.load(p).save(p)
        assertFalse(p.contains(Settings.CURSOR_PAD_KEY))
        assertFalse(p.contains(Settings.VOICE_KEY))
        assertEquals("EMOJI,UNDO,SELECT_ALL,COPY,PASTE,CLIPBOARD,VOICE", p.getString(Settings.TOOLBAR, null))
        assertTrue(ToolKey.SELECT_ALL in Settings.load(p).toolbar)
        val custom = Settings(toolbar = listOf(ToolKey.REDO), editSwipes = false, shiftSelect = false, selectionTools = false)
        custom.save(p)
        assertEquals(custom, Settings.load(p))
    }

    /** Voice in the list still keeps the last slot while a word is being typed, and only then. */
    @Test
    fun `the mic keeps its place at the end of the strip`() {
        view.voiceAvailable = true
        view.suggestions = listOf("teh", "the")
        assertEquals("Voice", labels().last())
        view.settings = Settings(toolbar = listOf(ToolKey.COPY))
        assertFalse("Voice" in labels())
    }

    // ---- selection tools -------------------------------------------------------------------------------------

    @Test
    fun `with a selection the toolbar counts it and offers style, cut, copy and paste`() {
        view.selection = Selected(23, 4)
        assertEquals(listOf("Hide", "23 characters · 4 words", "Style", "Cut", "Copy", "Paste"), labels())
        view.selection = null
        assertEquals(listOf("Hide", "Emoji", "Undo", "Cursor pad", "Copy", "Paste", "Clipboard"), labels())
    }

    @Test
    fun `one of each reads as one, and a long selection says so`() {
        view.selection = Selected(1, 1)
        assertEquals("1 character · 1 word", labels()[1])
        view.selection = Selected(Selected.CAP, 0, capped = true)
        assertEquals("2,000+ characters", labels()[1])
    }

    @Test
    fun `no selection tools in a password field, or with the switch off`() {
        show(FieldRules(password = true))
        view.selection = Selected(8, 1)
        assertFalse(view.toolbarPlacements.any { it.key.kind == KeyKind.SELECTION })
        show(FieldRules())
        view.settings = Settings(selectionTools = false)
        assertFalse(view.toolbarPlacements.any { it.key.kind == KeyKind.SELECTION })
    }

    @Test
    fun `style opens a menu over the keys, and a choice closes it`() {
        view.selection = Selected(5, 1)
        tapTool(KeyKind.STYLE)
        val choices = view.styleMenuItems.filter { it.key.kind == KeyKind.STYLE_CHOICE }
        assertEquals(listOf("Bold", "Italic", "Script", "Monospace"), choices.map { it.key.label })
        assertTrue(view.styleMenuItems.any { it.key.kind == KeyKind.STYLE_NOTE })
        tapAt(choices[1].box)
        assertEquals(listOf("style:ITALIC"), done)
        assertTrue(view.styleMenuItems.isEmpty())
        assertEquals("the key under the choice was not typed", "", typed.toString())
    }

    @Test
    fun `any key closes the menu`() {
        view.selection = Selected(5, 1)
        tapTool(KeyKind.STYLE)
        tapTool(KeyKind.COPY)
        assertTrue(view.styleMenuItems.isEmpty())
        assertEquals(listOf("copy"), done)
        // A touch on the menu between its choices only closes it.
        tapTool(KeyKind.STYLE)
        val note = view.styleMenuItems.first { it.key.kind == KeyKind.STYLE_NOTE }.box
        tapAt(note)
        assertTrue(view.styleMenuItems.isEmpty())
        assertEquals("", typed.toString())
        // Style again closes it rather than opening it straight back up.
        tapTool(KeyKind.STYLE)
        tapTool(KeyKind.STYLE)
        assertTrue(view.styleMenuItems.isEmpty())
    }

    @Test
    fun `the menu goes when the selection does`() {
        view.selection = Selected(5, 1)
        tapTool(KeyKind.STYLE)
        view.selection = null
        assertTrue(view.styleMenuItems.isEmpty())
    }

    // ---- screen readers --------------------------------------------------------------------------------------

    private fun spoken(id: Int) = view.accessibilityNodeProvider!!.createAccessibilityNodeInfo(id)!!.contentDescription.toString()

    @Test
    fun `the edit keys say what a swipe up does`() {
        assertEquals("c, swipe up to copy", spoken(view.placements.indexOf(key("c"))))
        assertEquals("a, swipe up to select all", spoken(view.placements.indexOf(key("a"))))
        assertEquals("z, swipe up to undo", spoken(view.placements.indexOf(key("z"))))
        assertEquals("b", spoken(view.placements.indexOf(key("b"))))
        view.settings = Settings(editSwipes = false)
        assertEquals("c", spoken(view.placements.indexOf(key("c"))))
    }

    @Test
    fun `new toolbar buttons and the menu have names`() {
        view.settings = Settings(toolbar = listOf(ToolKey.UNDO, ToolKey.REDO, ToolKey.CUT))
        val first = view.placements.size
        assertEquals(listOf("Hide the keyboard", "Undo", "Redo", "Cut"), (first until first + 4).map(::spoken))

        view.selection = Selected(23, 4)
        assertEquals("23 characters, 4 words", spoken(first + 1))
        val count = view.accessibilityNodeProvider!!.createAccessibilityNodeInfo(first + 1)!!
        assertTrue(count.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        assertTrue(spoken(first + 2).startsWith("Style"))

        view.accessibilityNodeProvider!!.performAction(first + 2, AccessibilityNodeInfo.ACTION_CLICK, null)
        val menu = first + view.toolbarPlacements.size
        assertEquals(listOf("Bold", "Italic", "Script", "Monospace"), (menu until menu + 4).map(::spoken))
        assertTrue(spoken(menu + 4).startsWith("Styled letters are symbols"))
        view.accessibilityNodeProvider!!.performAction(menu, AccessibilityNodeInfo.ACTION_CLICK, null)
        assertEquals(listOf("style:BOLD"), done)
    }

    // ---- what they do to text --------------------------------------------------------------------------------

    private class Field(view: View) : BaseInputConnection(view, true) {
        val keys = mutableListOf<Pair<Int, Int>>()
        val menu = mutableListOf<Int>()
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) keys += event.keyCode to event.metaState
            return super.sendKeyEvent(event)
        }

        override fun performContextMenuAction(id: Int): Boolean {
            menu += id
            return true
        }
    }

    private class FakeIme(override val connection: InputConnection?) : Ime {
        override var editorInfo: EditorInfo? = null
        var copies = 0
        val selections = mutableListOf<Selected?>()
        override fun switchKeyboard() = Unit
        override fun hideKeyboard() = Unit
        override fun show(rows: List<Row>, shift: Shift) = Unit
        override fun suggest(word: String) = Unit
        override fun learn(word: String) = Unit
        override fun showEmoji(showing: Boolean) = Unit
        override fun showClipboard(showing: Boolean) = Unit
        override fun copied() { copies++ }
        override fun selected(selection: Selected?) { selections += selection }
    }

    private fun actions(inputType: Int = InputType.TYPE_CLASS_TEXT, text: String = ""): Triple<TextActions, FakeIme, Field> {
        val field = Field(View(ApplicationProvider.getApplicationContext<Context>()))
        field.editable!!.append(text)
        val ime = FakeIme(field)
        val actions = TextActions(ime)
        ime.editorInfo = EditorInfo().also { it.inputType = inputType }
        actions.startInput(ime.editorInfo)
        return Triple(actions, ime, field)
    }

    @Test
    fun `undo is Ctrl+Z and redo is Ctrl+Shift+Z`() {
        val (actions, _, field) = actions()
        actions.onUndo()
        actions.onRedo()
        val (undoCode, undoMeta) = field.keys[0]
        assertEquals(KeyEvent.KEYCODE_Z, undoCode)
        assertTrue(undoMeta and KeyEvent.META_CTRL_ON != 0)
        assertEquals(0, undoMeta and KeyEvent.META_SHIFT_ON)
        val (redoCode, redoMeta) = field.keys[1]
        assertEquals(KeyEvent.KEYCODE_Z, redoCode)
        assertTrue(redoMeta and KeyEvent.META_CTRL_ON != 0 && redoMeta and KeyEvent.META_SHIFT_ON != 0)
    }

    @Test
    fun `cut, copy, paste and select all go through the app's own menu, and copy looks at the clipboard`() {
        val (actions, ime, field) = actions()
        actions.onCut()
        actions.onCopy()
        actions.onPaste()
        actions.onSelectAll()
        assertEquals(listOf(android.R.id.cut, android.R.id.copy, android.R.id.paste, android.R.id.selectAll), field.menu)
        assertEquals(1, ime.copies)
    }

    @Test
    fun `sliding from shift sends arrows with Shift held`() {
        val (actions, _, field) = actions(text = "hello")
        actions.onSelectMove(-3)
        assertEquals(3, field.keys.size)
        assertTrue(field.keys.all { (code, meta) -> code == KeyEvent.KEYCODE_DPAD_LEFT && meta and KeyEvent.META_SHIFT_ON != 0 })
        field.keys.clear()
        actions.onSelectMove(2)
        assertEquals(2, field.keys.size)
        assertTrue(field.keys.all { (code, meta) -> code == KeyEvent.KEYCODE_DPAD_RIGHT && meta and KeyEvent.META_SHIFT_ON != 0 })
    }

    private fun select(field: Field, start: Int, end: Int) = android.text.Selection.setSelection(field.editable!!, start, end)

    @Test
    fun `a selection is counted, with an emoji as one character`() {
        val text = "Hi there 😀 you"
        val (actions, ime, field) = actions(text = text)
        select(field, 0, text.length)
        actions.selectionChanged(0, text.length)
        assertEquals(Selected(characters = 14, words = 4), ime.selections.last())
        // A cursor is not a selection.
        actions.selectionChanged(3, 3)
        assertNull(ime.selections.last())
    }

    @Test
    fun `a long selection is not read, only said to be long`() {
        val text = "a".repeat(2500)
        val (actions, ime, field) = actions(text = text)
        select(field, 0, text.length)
        actions.selectionChanged(0, text.length)
        assertEquals(Selected(Selected.CAP, 0, capped = true), ime.selections.last())
    }

    @Test
    fun `nothing is counted or styled in a password field`() {
        val (actions, ime, field) = actions(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "secret")
        select(field, 0, 6)
        actions.selectionChanged(0, 6)
        assertTrue(ime.selections.all { it == null })
        actions.onStyle(TextStyle.BOLD)
        assertEquals("secret", field.editable.toString())
    }

    @Test
    fun `counting only happens with the switch on`() {
        val (actions, ime, field) = actions(text = "words here")
        actions.settings = Settings(selectionTools = false)
        select(field, 0, 5)
        actions.selectionChanged(0, 5)
        assertNull(ime.selections.last())
    }

    @Test
    fun `style replaces the selection and keeps it selected`() {
        val (actions, _, field) = actions(text = "say hi 2 you")
        select(field, 4, 8)
        actions.selectionChanged(4, 8)
        actions.onStyle(TextStyle.BOLD)
        val bold = TextStyle.BOLD.on("hi 2")
        assertEquals("say $bold you", field.editable.toString())
        val editable = field.editable!!
        val start = android.text.Selection.getSelectionStart(editable)
        val end = android.text.Selection.getSelectionEnd(editable)
        assertEquals(bold, editable.subSequence(start, end).toString())
    }

    // ---- the styles themselves -------------------------------------------------------------------------------

    @Test
    fun `each style has the right code point for every letter and digit`() {
        fun expect(style: TextStyle, c: Char): Int = when (style) {
            TextStyle.BOLD -> when (c) {
                in 'A'..'Z' -> 0x1D400 + (c - 'A')
                in 'a'..'z' -> 0x1D41A + (c - 'a')
                else -> 0x1D7CE + (c - '0')
            }
            // Italic has no digits, and its small h is the Planck constant, which was in Unicode first.
            TextStyle.ITALIC -> when (c) {
                'h' -> 0x210E
                in 'A'..'Z' -> 0x1D434 + (c - 'A')
                in 'a'..'z' -> 0x1D44E + (c - 'a')
                else -> c.code
            }
            TextStyle.SCRIPT -> SCRIPT_OLDER[c] ?: when (c) {
                in 'A'..'Z' -> 0x1D49C + (c - 'A')
                in 'a'..'z' -> 0x1D4B6 + (c - 'a')
                else -> c.code
            }
            TextStyle.MONOSPACE -> when (c) {
                in 'A'..'Z' -> 0x1D670 + (c - 'A')
                in 'a'..'z' -> 0x1D68A + (c - 'a')
                else -> 0x1D7F6 + (c - '0')
            }
        }
        for (style in TextStyle.entries) {
            for (c in ('a'..'z') + ('A'..'Z') + ('0'..'9')) {
                val styled = style.on(c.toString())
                assertEquals("$style $c", expect(style, c), styled.codePointAt(0))
                assertEquals("$style $c is one character", 1, styled.codePointCount(0, styled.length))
            }
        }
        assertEquals("ℎ", TextStyle.ITALIC.on("h"))
    }

    @Test
    fun `everything but letters and digits is kept, and restyling starts from plain`() {
        assertEquals("${TextStyle.BOLD.on("a")}, é! 😀 ${TextStyle.BOLD.on("b")}", TextStyle.BOLD.on("a, é! 😀 b"))
        val italic = TextStyle.ITALIC.on("Hello 42")
        assertEquals(TextStyle.MONOSPACE.on("Hello 42"), TextStyle.MONOSPACE.on(italic))
        assertEquals("Hello 42", TextStyle.plain(TextStyle.SCRIPT.on("Hello 42")))
    }

    @Test
    fun `words are runs between spaces, and characters are what a person counts`() {
        assertEquals(Selected(0, 0), Selected.of(""))
        assertEquals(Selected(3, 0), Selected.of("  \n"))
        assertEquals(Selected(11, 2), Selected.of(" two\twords "))
        assertEquals(Selected(1, 1), Selected.of("😀"))
    }

    private companion object {
        val SCRIPT_OLDER = mapOf(
            'B' to 0x212C, 'E' to 0x2130, 'F' to 0x2131, 'H' to 0x210B, 'I' to 0x2110, 'L' to 0x2112, 'M' to 0x2133,
            'R' to 0x211B, 'e' to 0x212F, 'g' to 0x210A, 'o' to 0x2134,
        )
    }
}
