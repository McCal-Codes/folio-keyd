package com.mccal.folio.keys

import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
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

/**
 * The gesture tips: which one is due and when, how it sits in the strip and goes, and what marks it done - Got it, the
 * gesture itself, or being shown three times.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TipsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("keys", Context.MODE_PRIVATE)
    private val today = 20_000L

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    // ---- which tip, and when ----------------------------------------------------------------------------------

    @Test
    fun `the space bar comes first, then each switched-on gesture in turn`() {
        var tips = Tips()
        val seen = mutableListOf<Tip>()
        var day = today
        while (true) {
            val tip = tips.next(Settings(), day) ?: break
            seen += tip
            tips = tips.finish(tip).shownOn(tip, day)
            day++
        }
        assertEquals(listOf(Tip.CURSOR_SWIPE, Tip.DELETE_WORD, Tip.EDIT_SWIPES, Tip.SHIFT_SELECT, Tip.TWO_FINGER_UNDO, Tip.GLOBE_LANGUAGES, Tip.PERIOD_SYMBOLS), seen)
    }

    @Test
    fun `a gesture that is switched off gets no tip`() {
        val settings = Settings(cursorSwipe = false, deleteWordSwipe = false, editSwipes = false, twoFingerUndo = false, periodSymbols = "")
        assertEquals(Tip.SHIFT_SELECT, Tips().next(settings, today))
        // The globe's list is always there, so it is the one tip left when every switch is off.
        assertEquals(Tip.GLOBE_LANGUAGES, Tips().next(settings.copy(shiftSelect = false), today))
        assertNull(Tips().finish(Tip.GLOBE_LANGUAGES).next(settings.copy(shiftSelect = false), today))
    }

    @Test
    fun `the newer gestures' tips follow their own switches`() {
        for (tip in listOf(Tip.TWO_FINGER_UNDO, Tip.GLOBE_LANGUAGES, Tip.PERIOD_SYMBOLS)) assertTrue(tip.on(Settings()))
        assertFalse(Tip.TWO_FINGER_UNDO.on(Settings(twoFingerUndo = false)))
        assertFalse(Tip.PERIOD_SYMBOLS.on(Settings(periodSymbols = "")))
    }

    @Test
    fun `one a day`() {
        val shown = Tips().shownOn(Tip.CURSOR_SWIPE, today)
        assertNull(shown.next(Settings(), today))
        assertEquals(Tip.CURSOR_SWIPE, shown.next(Settings(), today + 1))
    }

    @Test
    fun `put away three times by typing, a tip counts as read`() {
        var tips = Tips()
        repeat(Tips.TIMES) { tips = tips.shownOn(Tip.CURSOR_SWIPE, today + it) }
        assertTrue(tips.done(Tip.CURSOR_SWIPE))
        assertEquals(Tip.DELETE_WORD, tips.next(Settings(), today + 10))
    }

    @Test
    fun `never with tips off, in a password field, or in a number or phone field`() {
        val tips = Tips()
        assertEquals(Tip.CURSOR_SWIPE, tips.due(Settings(), FieldRules(), today))
        assertNull(tips.due(Settings(gestureTips = false), FieldRules(), today))
        assertNull(tips.due(Settings(), FieldRules(password = true), today))
        assertNull(tips.due(Settings(), FieldRules(kind = FieldKind.NUMBER), today))
        assertNull(tips.due(Settings(), FieldRules(kind = FieldKind.PHONE), today))
    }

    @Test
    fun `what is stored comes back, and nonsense in it is left out`() {
        val tips = Tips().shownOn(Tip.DELETE_WORD, today).finish(Tip.CURSOR_SWIPE)
        tips.save(prefs)
        val back = Tips.load(prefs)
        assertEquals(1, back.times(Tip.DELETE_WORD))
        assertTrue(back.done(Tip.CURSOR_SWIPE))
        assertEquals(today, back.day)
        val odd = Tips.decode("cursorSwipe:x,nothing:2,deleteWord:99,,shiftSelect")
        assertEquals(0, odd.times(Tip.CURSOR_SWIPE))
        assertEquals(Tips.TIMES, odd.times(Tip.DELETE_WORD))
        assertEquals(setOf(Tip.DELETE_WORD), odd.finished())
    }

    @Test
    fun `the setting is on unless turned off, and comes back`() {
        assertTrue(Settings().gestureTips)
        Settings(gestureTips = false).save(prefs)
        assertFalse(Settings.load(prefs).gestureTips)
    }

    // ---- in the strip -----------------------------------------------------------------------------------------

    private lateinit var view: KeyboardView
    private val typed = StringBuilder()
    private val finished = mutableListOf<Tip>()
    private val used = mutableListOf<Tip>()
    private val density get() = view.resources.displayMetrics.density

    private fun keyboard(widthDp: Int = 411): KeyboardView {
        view = KeyboardView(context)
        view.listener = object : KeyboardView.Listener {
            override fun onText(text: String) { typed.append(text) }
            override fun onBackspace() = Unit
            override fun onDeleteWord() = Unit
            override fun onShift() = Unit
            override fun onLayer(layer: Layer) = Unit
            override fun onAction() = Unit
            override fun onSwitchKeyboard() = Unit
            override fun onCursor(steps: Int) = Unit
            override fun onSelectAll() = Unit
            override fun onCopy() = Unit
            override fun onPaste() = Unit
            override fun onEmojiPanel() = Unit
            override fun onClipboardPanel() = Unit
            override fun onSuggestion(word: String) = Unit
            override fun onHide() = Unit
            override fun onTipDone(tip: Tip) { finished += tip }
            override fun onGesture(tip: Tip) { used += tip }
        }
        view.rules = FieldRules()
        view.rows = Layouts.rows(Layer.LETTERS, false, FieldRules())
        val width = (widthDp * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    private fun strip() = view.toolbarPlacements.map { it.key.kind to it.key.label }

    private fun send(action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun box(label: String) = view.placements.first { it.key.label == label }.box
    private fun box(kind: KeyKind) = view.placements.first { it.key.kind == kind }.box

    private fun tapAt(b: Box) {
        send(MotionEvent.ACTION_DOWN, (b.left + b.right) / 2, (b.top + b.bottom) / 2)
        send(MotionEvent.ACTION_UP, (b.left + b.right) / 2, (b.top + b.bottom) / 2)
    }

    /** A press on [from] that travels [dxDp] sideways and [dyDp] down, then lets go. */
    private fun swipe(from: Box, dxDp: Float, dyDp: Float = 0f) {
        val x = (from.left + from.right) / 2
        val y = (from.top + from.bottom) / 2
        send(MotionEvent.ACTION_DOWN, x, y)
        for (step in 1..6) send(MotionEvent.ACTION_MOVE, x + dxDp * density * step / 6, y + dyDp * density * step / 6)
        send(MotionEvent.ACTION_UP, x + dxDp * density, y + dyDp * density)
    }

    @Test
    fun `the tip takes the strip with a Got it, and says it in fewer words on a narrow window`() {
        keyboard()
        view.tip = Tip.CURSOR_SWIPE
        assertEquals(
            listOf(KeyKind.TIP to "Tip: swipe the space bar to move the cursor", KeyKind.TIP_DONE to "Got it"),
            strip(),
        )
        assertTrue(view.tipShowing)
        keyboard(widthDp = 360)
        view.tip = Tip.CURSOR_SWIPE
        assertEquals("Tip: swipe space to move the cursor", strip().first().second)
        view.tip = Tip.DELETE_WORD
        assertEquals("Tip: swipe backspace left to erase a word", strip().first().second)
    }

    @Test
    fun `words and the strip's question come before a tip, and a password field shows none`() {
        keyboard()
        view.tip = Tip.CURSOR_SWIPE
        view.suggestions = listOf("the", "then")
        assertFalse(view.tipShowing)
        view.suggestions = emptyList()
        view.offer = Insights.Offer("Folio", null)
        assertFalse(view.tipShowing)
        view.offer = null
        assertTrue(view.tipShowing)
        view.rules = FieldRules(password = true)
        assertFalse(view.tipShowing)
    }

    @Test
    fun `the next key puts it away, and Got it marks it done`() {
        keyboard()
        view.tip = Tip.DELETE_WORD
        tapAt(box("e"))
        assertEquals("e", typed.toString())
        assertNull(view.tip)
        assertTrue(finished.isEmpty())

        view.tip = Tip.DELETE_WORD
        tapAt(view.toolbarPlacements.first { it.key.kind == KeyKind.TIP_DONE }.box)
        assertEquals(listOf(Tip.DELETE_WORD), finished)
        assertNull(view.tip)
        assertEquals("Got it answers the tip and types nothing", "e", typed.toString())
    }

    @Test
    fun `TalkBack reads the tip and finds Got it as a button`() {
        keyboard()
        view.tip = Tip.EDIT_SWIPES
        val first = view.placements.size
        val provider = view.accessibilityNodeProvider!!
        val said = provider.createAccessibilityNodeInfo(first)!!
        assertEquals("Tip: swipe up on C to copy, V to paste", said.contentDescription.toString())
        assertTrue(said.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        val button = provider.createAccessibilityNodeInfo(first + 1)!!
        assertEquals("Got it", button.contentDescription.toString())
        assertEquals("android.widget.Button", button.className.toString())
        provider.performAction(first + 1, AccessibilityNodeInfo.ACTION_CLICK, null)
        assertEquals(listOf(Tip.EDIT_SWIPES), finished)
    }

    @Test
    fun `using a gesture says so, once per swipe`() {
        keyboard()
        swipe(box(KeyKind.SPACE), 120f)
        assertEquals(listOf(Tip.CURSOR_SWIPE), used)
        used.clear()
        swipe(box(KeyKind.BACKSPACE), -60f)
        assertEquals(listOf(Tip.DELETE_WORD), used)
        used.clear()
        swipe(box(KeyKind.SHIFT), 120f)
        assertEquals(listOf(Tip.SHIFT_SELECT), used)
        used.clear()
        swipe(box("c"), 0f, -40f)
        assertEquals(listOf(Tip.EDIT_SWIPES), used)
        used.clear()
        tapAt(box(KeyKind.SPACE))
        tapAt(box("c"))
        assertTrue("a tap is not the gesture", used.isEmpty())
    }

    // ---- the service --------------------------------------------------------------------------------------------

    private fun service() = Robolectric.buildService(KeysService::class.java).create().get()

    private inline fun <reified T> ViewGroup.child(): T =
        (0 until childCount).map { getChildAt(it) }.filterIsInstance<T>().single()

    private fun field(inputType: Int = InputType.TYPE_CLASS_TEXT) = EditorInfo().also {
        it.packageName = "com.chat"
        it.inputType = inputType
    }

    private fun KeysService.settle() = repeat(3) {
        shadowOf(suggestionLooper).idle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** [count] characters from the keys, then the gap after a word, where a tip may go. */
    private fun KeysService.typeThenGap(keys: KeyboardView, count: Int) {
        repeat(count) { keys.listener!!.onText("a") }
        suggest("", "hello")
        settle()
    }

    @Test
    fun `a tip waits for twenty characters, then takes a gap between words, once per field`() {
        val service = service()
        val keys = (service.onCreateInputView() as ViewGroup).child<KeyboardView>()
        service.onStartInputView(field(), false)
        service.typeThenGap(keys, Tips.AFTER_CHARS - 1)
        assertNull("not straight away", keys.tip)
        service.typeThenGap(keys, 1)
        assertEquals(Tip.CURSOR_SWIPE, keys.tip)
        assertTrue("instead of what might come next", keys.suggestions.isEmpty())
        assertEquals(1, Tips.load(prefs).times(Tip.CURSOR_SWIPE))

        // Put away by typing; this field has had its chance.
        keys.tip = null
        service.typeThenGap(keys, 30)
        assertNull(keys.tip)
        // And another field the same day gets none.
        service.onStartInputView(field(), false)
        service.typeThenGap(keys, 30)
        assertNull(keys.tip)
        // Another day, the same tip again.
        Tips.load(prefs).let { Tips.decode(it.encode(), it.day - 1).save(prefs) }
        service.onStartInputView(field(), false)
        service.typeThenGap(keys, 30)
        assertEquals(Tip.CURSOR_SWIPE, keys.tip)
    }

    /** Suggestions off sent no word before the gap, so the one place a tip goes never came. */
    @Test
    fun `with suggestions off a tip still takes the gap after a word`() {
        Settings(suggestions = false).save(prefs)
        val service = service()
        val keys = (service.onCreateInputView() as ViewGroup).child<KeyboardView>()
        service.onStartInputView(field(), false)
        repeat(Tips.AFTER_CHARS - 1) { keys.listener!!.onText("a") }
        service.quietGap()
        service.settle()
        assertNull("not straight away", keys.tip)
        keys.listener!!.onText("a")
        service.quietGap()
        service.settle()
        assertEquals(Tip.CURSOR_SWIPE, keys.tip)
        assertTrue(keys.suggestions.isEmpty())
        assertEquals(1, Tips.load(prefs).times(Tip.CURSOR_SWIPE))
    }

    @Test
    fun `no tip in a password field, a number field, or with tips off`() {
        val service = service()
        val keys = (service.onCreateInputView() as ViewGroup).child<KeyboardView>()
        service.onStartInputView(field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD), false)
        service.typeThenGap(keys, 30)
        assertNull(keys.tip)
        service.onStartInputView(field(InputType.TYPE_CLASS_NUMBER), false)
        service.typeThenGap(keys, 30)
        assertNull(keys.tip)
        Settings(gestureTips = false).save(prefs)
        service.onStartInputView(field(), false)
        service.typeThenGap(keys, 30)
        assertNull(keys.tip)
        assertEquals("nothing was counted as shown", 0, Tips.load(prefs).times(Tip.CURSOR_SWIPE))
    }

    @Test
    fun `Got it, or the gesture itself, and that tip never comes back`() {
        val service = service()
        val keys = (service.onCreateInputView() as ViewGroup).child<KeyboardView>()
        service.onStartInputView(field(), false)
        service.typeThenGap(keys, 30)
        assertEquals(Tip.CURSOR_SWIPE, keys.tip)
        keys.listener!!.onTipDone(Tip.CURSOR_SWIPE)
        service.settle()
        assertTrue(Tips.load(prefs).done(Tip.CURSOR_SWIPE))

        // Used before its tip was ever shown: done all the same, and the strip moves on to the next.
        keys.listener!!.onGesture(Tip.DELETE_WORD)
        service.settle()
        assertTrue(Tips.load(prefs).done(Tip.DELETE_WORD))
        prefs.edit().remove(Tips.DAY).commit()
        service.onStartInputView(field(), false)
        service.typeThenGap(keys, 30)
        assertEquals(Tip.EDIT_SWIPES, keys.tip)
        // Swiping while it shows puts it away there and then.
        keys.listener!!.onGesture(Tip.EDIT_SWIPES)
        assertNull(keys.tip)
        assertTrue(Tips.load(prefs).done(Tip.EDIT_SWIPES))
    }

    @Test
    fun `no period tip in a field without a period key`() {
        val rest = Tips().finish(Tip.CURSOR_SWIPE).finish(Tip.DELETE_WORD).finish(Tip.EDIT_SWIPES)
            .finish(Tip.SHIFT_SELECT).finish(Tip.TWO_FINGER_UNDO).finish(Tip.GLOBE_LANGUAGES)
        assertEquals(Tip.PERIOD_SYMBOLS, rest.due(Settings(), FieldRules(), today))
        assertNull(rest.due(Settings(), FieldRules(kind = FieldKind.EMAIL), today))
        assertNull(rest.due(Settings(), FieldRules(kind = FieldKind.URL), today))
    }
}
