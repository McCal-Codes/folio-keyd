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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A space after the numbers or symbols goes back to the letters, as on an iPhone and on Gboard. The keys are the ones
 * [TextActions] hands to a real [KeyboardView], pressed with real touches; the text is a real editable behind a real
 * connection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class BackToLettersTest {

    private class FakeIme(override val connection: InputConnection?, val view: KeyboardView) : Ime {
        override var editorInfo: EditorInfo? = null
        override fun switchKeyboard() = Unit
        override fun hideKeyboard() = Unit
        override fun show(rows: List<Row>, shift: Shift) {
            view.rows = rows
            view.measure(
                View.MeasureSpec.makeMeasureSpec((411 * view.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
        override fun suggest(word: String) = Unit
        override fun learn(word: String) = Unit
        override fun showEmoji(showing: Boolean) = Unit
        override fun showClipboard(showing: Boolean) = Unit
    }

    private lateinit var field: BaseInputConnection
    private lateinit var view: KeyboardView
    private lateinit var ime: FakeIme
    private lateinit var actions: TextActions
    private val text get() = field.editable.toString()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        field = BaseInputConnection(View(context), true)
        view = KeyboardView(context)
        ime = FakeIme(field, view)
        actions = TextActions(ime)
        view.listener = actions
        start()
    }

    private fun start(inputType: Int = InputType.TYPE_CLASS_TEXT, settings: Settings = Settings()) {
        actions.settings = settings
        view.settings = settings
        ime.editorInfo = EditorInfo().also { it.inputType = inputType }
        actions.startInput(ime.editorInfo)
        view.rules = actions.rules
    }

    private fun press(placement: Placement) {
        val box = placement.box
        val x = (box.left + box.right) / 2
        val y = (box.top + box.bottom) / 2
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0, 0, action, x, y, 0)
            view.onTouchEvent(event)
            event.recycle()
        }
    }

    private fun tap(label: String) = press(view.placements.first { it.key.label == label })
    private fun space() = press(view.placements.first { it.key.kind == KeyKind.SPACE })
    private val showing get() = view.placements.map { it.key.label }

    @Test
    fun `a digit and a space go back to the letters`() {
        tap("123")
        assertEquals(Layer.NUMBERS, actions.layer)
        tap("3")
        space()
        assertEquals(Layer.LETTERS, actions.layer)
        assertTrue("the keys on screen are the letters again", "q" in showing)
        tap("p")
        tap("m")
        assertEquals("3 pm", text)
    }

    @Test
    fun `several digits and symbols, then a space, go back too`() {
        tap("123")
        for (key in listOf("1", "2", ":", "3", "0")) tap(key)
        space()
        assertEquals(Layer.LETTERS, actions.layer)
        assertEquals("12:30 ", text)
    }

    @Test
    fun `from the second symbols page as well`() {
        tap("123")
        tap("#+=")
        assertEquals(Layer.SYMBOLS, actions.layer)
        tap("%")
        space()
        assertEquals(Layer.LETTERS, actions.layer)
    }

    @Test
    fun `a space straight after 123 goes back, as on an iPhone`() {
        tap("123")
        space()
        assertEquals(Layer.LETTERS, actions.layer)
        assertEquals(" ", text)
    }

    @Test
    fun `digits without a space stay on the numbers`() {
        tap("123")
        for (key in listOf("2", "0", "2", "6", "-")) tap(key)
        assertEquals(Layer.NUMBERS, actions.layer)
    }

    @Test
    fun `with the setting off the numbers stay until ABC`() {
        start(settings = Settings(backToLetters = false))
        tap("123")
        tap("3")
        space()
        assertEquals(Layer.NUMBERS, actions.layer)
        tap("4")
        assertEquals("3 4", text)
        tap("ABC")
        assertEquals(Layer.LETTERS, actions.layer)
    }

    @Test
    fun `a web or email address keeps the symbols`() {
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)) {
            start(InputType.TYPE_CLASS_TEXT or variation)
            tap("123")
            tap("3")
            space()
            assertEquals("variation $variation", Layer.NUMBERS, actions.layer)
        }
    }

    @Test
    fun `number and phone fields never leave their pad`() {
        for (type in listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE)) {
            start(type)
            actions.onText(" ")
            assertEquals("type $type", Layer.NUMBERS, actions.layer)
        }
    }

    @Test
    fun `a space typed on the letters changes nothing`() {
        tap("h")
        tap("i")
        space()
        assertEquals(Layer.LETTERS, actions.layer)
        assertEquals("hi ", text)
    }

    @Test
    fun `it is on unless turned off, and the answer is kept`() {
        assertTrue(Settings().backToLetters)
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("back-to-letters-test", Context.MODE_PRIVATE)
        assertTrue(Settings.load(prefs).backToLetters)
        Settings(backToLetters = false).save(prefs)
        assertFalse(Settings.load(prefs).backToLetters)
    }
}
