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
 * A password field shows the row of digits even with the Number row setting off, as Gboard and Samsung do. The keys
 * are the ones [TextActions] hands to a real [KeyboardView]; the text is a real editable behind a real connection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class PasswordNumberRowTest {

    private class FakeIme(override val connection: InputConnection?, val view: KeyboardView) : Ime {
        override var editorInfo: EditorInfo? = null
        var rows: List<Row> = emptyList()
        override fun switchKeyboard() = Unit
        override fun hideKeyboard() = Unit
        override fun show(rows: List<Row>, shift: Shift) {
            this.rows = rows
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
    private val digits = "1234567890".map { it.toString() }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        field = BaseInputConnection(View(context), true)
        view = KeyboardView(context)
        ime = FakeIme(field, view)
        actions = TextActions(ime)
        view.listener = actions
        actions.settings = Settings(numberRow = false)
    }

    private fun start(inputType: Int) {
        ime.editorInfo = EditorInfo().also { it.inputType = inputType }
        actions.startInput(ime.editorInfo)
        view.rules = actions.rules
    }

    private fun tap(label: String) {
        val box = view.placements.first { it.key.label == label }.box
        val x = (box.left + box.right) / 2
        val y = (box.top + box.bottom) / 2
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(0, 0, action, x, y, 0)
            view.onTouchEvent(event)
            event.recycle()
        }
    }

    @Test
    fun `a password field has the digits above the letters with the setting off`() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertEquals(digits, ime.rows.first().map { it.label })
        assertEquals("the letters are all still there", 5, ime.rows.size)
        tap("k")
        tap("7")
        assertEquals("k7", field.editable.toString())
        assertFalse("the setting is not changed by the field", actions.settings.numberRow)
    }

    @Test
    fun `every kind of text password gets them`() {
        for (variation in listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) {
            start(InputType.TYPE_CLASS_TEXT or variation)
            assertEquals("variation $variation", digits, ime.rows.first().map { it.label })
        }
    }

    @Test
    fun `an ordinary field keeps the setting's answer`() {
        start(InputType.TYPE_CLASS_TEXT)
        assertFalse(ime.rows.first().map { it.label } == digits)
        assertEquals(4, ime.rows.size)
        actions.settings = Settings(numberRow = true)
        start(InputType.TYPE_CLASS_TEXT)
        assertEquals(digits, ime.rows.first().map { it.label })
    }

    @Test
    fun `a PIN keeps its number pad, with no row of digits on top`() {
        start(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        assertTrue(actions.rules.password)
        assertEquals(Layouts.rows(Layer.NUMBERS, false, actions.rules), ime.rows)
        assertFalse(ime.rows.first().map { it.label } == digits)
    }

    @Test
    fun `the symbols of a password field are not given a digit row they already have`() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        actions.onLayer(Layer.NUMBERS)
        assertEquals(4, ime.rows.size)
    }
}
