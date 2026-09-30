package com.mccal.folio.keys

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.autofill.inline.UiVersions
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
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Password manager suggestions in the strip.
 *
 * Robolectric cannot make a real suggestion: those come from an autofill service in another process, drawn into a
 * surface. So what is tested is everything around that: the request Keyd makes, and that it makes none with the
 * setting off; where the chips it is handed go, and that they go away; the switch; and the flag that tells Android
 * Keyd can show them at all. Plain views stand in for the chips.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class AutofillTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("keys", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    private fun service() = Robolectric.buildService(KeysService::class.java).create().get()

    private inline fun <reified T> ViewGroup.child(): T =
        (0 until childCount).map { getChildAt(it) }.filterIsInstance<T>().single()

    /** The input view, measured and laid out as a phone 411 dp wide would. */
    private fun KeysService.laidOut(): ViewGroup {
        val root = onCreateInputView() as ViewGroup
        onStartInputView(android.view.inputmethod.EditorInfo(), false)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    private fun relayout(root: ViewGroup) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    /** A stand-in for a chip the password manager drew, [width] pixels across as its layout asks. */
    private fun chip(width: Int, pinned: Boolean = false) =
        AutofillStrip.Chip(View(context).apply { layoutParams = ViewGroup.LayoutParams(width, 60) }, pinned)

    // ---- The request ---------------------------------------------------------------------------------------------

    @Test
    fun `with the setting on, the request asks for chips sized to the strip, in a style managers read`() {
        val request = service().onCreateInlineSuggestionsRequest(Bundle())
        assertNotNull(request)
        request!!
        assertEquals(Autofill.MAX_CHIPS, request.maxSuggestionCount)
        val spec = request.inlinePresentationSpecs.single()
        val height = Autofill.chipHeight(context.resources.displayMetrics.density)
        assertEquals(height, spec.minSize.height)
        assertEquals(height, spec.maxSize.height)
        assertTrue(spec.maxSize.width >= spec.minSize.width)
        assertTrue("the style must be one androidx can read", UiVersions.getVersions(spec.style).isNotEmpty())
    }

    @Test
    fun `the widest a chip may be is the strip's width, once the keys are laid out`() {
        val service = service()
        service.laidOut()
        val spec = service.onCreateInlineSuggestionsRequest(Bundle())!!.inlinePresentationSpecs.single()
        assertTrue("${spec.maxSize.width}", spec.maxSize.width in 600..822)
    }

    @Test
    fun `with the setting off, Keyd asks for no chips`() {
        Settings(passwordManagerSuggestions = false).save(prefs)
        assertNull(service().onCreateInlineSuggestionsRequest(Bundle()))
        assertNull(Autofill.request(context, on = false, widest = 800, theme = Theme.of(context, Appearance.SYSTEM, false)))
    }

    // ---- Where the chips go --------------------------------------------------------------------------------------

    @Test
    fun `chips handed over sit over the strip, and the strip's own buttons step aside`() {
        val service = service()
        val root = service.laidOut()
        val keys = root.child<KeyboardView>()
        assertTrue("the toolbar is there to begin with", keys.toolbarPlacements.isNotEmpty())
        service.showChips(listOf(chip(200), chip(150)))
        relayout(root)
        val strip = root.child<AutofillStrip>()
        assertTrue(service.chipsShowing)
        assertEquals(2, strip.childCount)
        assertTrue(keys.autofilling)
        assertTrue("nothing of Keyd's is left under the gaps", keys.toolbarPlacements.isEmpty())
        // On top of the keyboard, so a chip is touched before the strip under it.
        assertEquals(root.childCount - 1, root.indexOfChild(strip))
        // In the strip, from its left edge, and no taller than it.
        val first = strip.getChildAt(0)
        assertEquals(keys.stripLanes.single().first, first.left)
        assertTrue(first.bottom <= keys.stripBottom)
        assertTrue("below the board's padding", first.top >= keys.stripTop)
        val middle = (keys.stripTop + keys.stripBottom) / 2f
        assertEquals("centered on the strip", middle, (first.top + first.bottom) / 2f, 1f)
        assertEquals(200, first.width)
    }

    @Test
    fun `words typed while the chips show stay behind them, and come back when they go`() {
        val service = service()
        val root = service.laidOut()
        val keys = root.child<KeyboardView>()
        service.showChips(listOf(chip(200)))
        keys.suggestions = listOf("hel", "hello", "help")
        assertTrue(keys.toolbarPlacements.isEmpty())
        service.showChips(emptyList())
        assertFalse(service.chipsShowing)
        assertEquals(3, keys.toolbarPlacements.count { it.key.kind == KeyKind.SUGGESTION })
    }

    @Test
    fun `the chips go when the field is left or the keyboard is hidden`() {
        val service = service()
        val root = service.laidOut()
        val keys = root.child<KeyboardView>()
        service.showChips(listOf(chip(200)))
        service.onFinishInput()
        assertFalse(service.chipsShowing)
        assertFalse(keys.autofilling)
        assertEquals(0, root.child<AutofillStrip>().childCount)

        service.showChips(listOf(chip(200)))
        service.onWindowHidden()
        assertFalse(service.chipsShowing)
        assertTrue(keys.toolbarPlacements.isNotEmpty())
    }

    @Test
    fun `the chips go with the letters when a panel opens in their place`() {
        val service = service()
        val root = service.laidOut()
        service.showChips(listOf(chip(200)))
        service.showEmoji(true)
        relayout(root)
        val strip = root.child<AutofillStrip>()
        assertEquals(0, strip.width)
        service.showEmoji(false)
        relayout(root)
        assertTrue(strip.width > 0)
        assertEquals(200, strip.getChildAt(0).width)
    }

    @Test
    fun `pinned chips go last and at the far end, as Gboard puts them`() {
        val strip = AutofillStrip(context)
        val pinned = chip(80, pinned = true)
        val login = chip(200)
        strip.show(listOf(pinned, login))
        assertEquals(listOf(login.view, pinned.view), (0 until strip.childCount).map { strip.getChildAt(it) })
        assertEquals(listOf(0, 920), AutofillStrip.arrange(listOf(200, 80), listOf(false, true), listOf(0 to 1000)))
    }

    @Test
    fun `a chip that does not fit is left out, and a pinned one is kept first`() {
        val placed = AutofillStrip.arrange(
            listOf(300, 300, 300, 100), listOf(false, false, false, true), listOf(0 to 800), gap = 10,
        )
        assertEquals(listOf(0, 310, null, 700), placed)
    }

    @Test
    fun `on a split keyboard the chips share the halves and none sits on the gap between them`() {
        val lanes = listOf(0 to 400, 600 to 1000)
        val placed = AutofillStrip.arrange(listOf(150, 150, 150), listOf(false, false, false), lanes, gap = 8)
        assertEquals(listOf(0, 158, 600), placed)
        val widths = listOf(150, 150, 150)
        for ((index, left) in placed.withIndex()) {
            left!!
            assertTrue("chip $index is on the gap", lanes.any { left >= it.first && left + widths[index] <= it.second })
        }
    }

    @Test
    fun `a split keyboard's strip has two lanes, with the middle left clear`() {
        Settings(split = Split.ALWAYS).save(prefs)
        val service = service()
        val root = service.onCreateInputView() as ViewGroup
        service.onStartInputView(android.view.inputmethod.EditorInfo(), false)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val lanes = root.child<KeyboardView>().stripLanes
        assertEquals(2, lanes.size)
        assertTrue("the halves overlap", lanes[0].second < lanes[1].first)
        assertTrue("the gap is not in the middle", lanes[0].second < 900 && lanes[1].first > 900)
    }

    // ---- The switch, and Android's flag --------------------------------------------------------------------------

    @Test
    fun `the setting is on unless turned off, and is saved`() {
        assertTrue(Settings.load(prefs).passwordManagerSuggestions)
        Settings(passwordManagerSuggestions = false).save(prefs)
        assertFalse(Settings.load(prefs).passwordManagerSuggestions)
        assertTrue("the report lists it",
            DevLog.switches(Settings.load(prefs)).any { it.startsWith("passwordManagerSuggestions") })
    }

    @Test
    fun `smart typing has the switch and its note, search finds it, and a tap saves it`() {
        DevLog.clear(context)
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val title = a.getString(R.string.settings_password_manager)
        val index = a.searchIndex()
        val entry = index.single { it.title == title }
        assertEquals(a.getString(R.string.page_typing), entry.where)
        assertTrue(SettingsSearch.find(index, "password manager").any { it.title == title })
        assertTrue(SettingsSearch.find(index, "never sees what they say").any { it.title == title })

        fun all(): List<View> {
            val out = mutableListOf<View>()
            fun walk(v: View) { out += v; if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
            walk(a.window.decorView)
            return out
        }
        fun text(label: String) = all().filterIsInstance<TextView>().firstOrNull { it.text.toString() == label }
        fun tap(label: String) {
            var target: View = text(label) ?: throw AssertionError("no \"$label\" on screen")
            while (!target.isClickable && target.parent is View) target = target.parent as View
            target.performClick()
        }
        tap(a.getString(R.string.page_typing))
        assertNotNull(text(a.getString(R.string.settings_password_manager_sub)))
        assertNotNull(text(a.getString(R.string.settings_password_manager_note)))
        // Its own switch: Keyd's words off leaves the password manager's chips alone.
        tap(a.getString(R.string.settings_suggestions))
        val row = text(title)!!.parent.parent as ViewGroup
        assertTrue(row.isEnabled)
        assertTrue((0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<Switch>().single().isChecked)
        tap(title)
        val stored = Settings.load(prefs)
        assertFalse(stored.passwordManagerSuggestions)
        assertFalse(stored.suggestions)
    }

    @Test
    fun `the input method says it can show a password manager's suggestions`() {
        val parser = context.resources.getXml(R.xml.method)
        var found: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "input-method") {
                found = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "supportsInlineSuggestions")
            }
        }
        assertEquals("true", found)
    }
}
