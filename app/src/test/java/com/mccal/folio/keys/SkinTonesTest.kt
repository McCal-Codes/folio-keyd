package com.mccal.folio.keys

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Skin tones: hold an emoji for its six, let go on one to type it, and it stays that emoji's tone.
 *
 * With real touches, held for as long as the hold takes, because what goes wrong here is timing and place: a row
 * that opens on a quick tap, a finger that lets go where it was held and types a tone nobody chose, or a tone picked
 * once that the next tap forgets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SkinTonesTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("keys", Context.MODE_PRIVATE)

    private lateinit var panel: EmojiPanel
    private val typed = mutableListOf<String>()
    private val toned = mutableListOf<Pair<String, Int>>()

    private val density get() = panel.resources.displayMetrics.density
    /** A short list, all on screen at once, so a tap never has to scroll to reach one. */
    private val people = listOf("😀", "👍", "✋", "✌️", "🐶", "👩")

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        panel = EmojiPanel(context)
        panel.listener = object : EmojiPanel.Listener {
            override fun onEmoji(emoji: String) { typed += emoji }
            override fun onBackspace() = Unit
            override fun onLetters() = Unit
            // What the service does: remember it, and hand the panel its tones back.
            override fun onSkinTone(emoji: String, tone: Int) {
                toned += emoji to tone
                panel.tones = panel.tones.picking(emoji, tone)
            }
        }
        panel.recents = people
        panel.selectCategory(0)
        val width = (411 * density).toInt()
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
    }

    private fun send(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun centre(box: Box) = (box.left + box.right) / 2 to (box.top + box.bottom) / 2

    private fun tap(view: View, box: Box) {
        val (x, y) = centre(box)
        send(view, MotionEvent.ACTION_DOWN, x, y)
        send(view, MotionEvent.ACTION_UP, x, y)
    }

    /** Down on emoji [index] and held, still down afterwards. */
    private fun hold(index: Int): Pair<Float, Float> {
        val (x, y) = centre(panel.cellBox(index))
        send(panel, MotionEvent.ACTION_DOWN, x, y)
        idle(1000)
        return x to y
    }

    /** From [from] to the middle of [to], in a few moves, the way a finger slides. */
    private fun slide(view: View, from: Pair<Float, Float>, to: Box) {
        val (tx, ty) = centre(to)
        for (step in 1..4) {
            val t = step / 4f
            send(view, MotionEvent.ACTION_MOVE, from.first + (tx - from.first) * t, from.second + (ty - from.second) * t)
        }
    }

    // ---- the data -----------------------------------------------------------------------------------------------

    @Test
    fun `only single-person emoji Keyd ships take a tone`() {
        assertTrue(SkinTones.supports("👍"))
        assertTrue(SkinTones.supports("👍🏽"))
        assertTrue(SkinTones.supports("✌️"))
        assertFalse(SkinTones.supports("😀"))
        assertFalse("a family is a tone per person", SkinTones.supports("👪"))
        assertFalse(SkinTones.supports("🤼"))
        val shipped = Emoji.CATEGORIES.flatMap { it.items }.toSet()
        val bases = shipped.filter { SkinTones.supports(it) }
        assertEquals(51, bases.size)
    }

    @Test
    fun `a tone replaces the colorful-form marker, and tone 0 gives it back`() {
        assertEquals("✌🏽", SkinTones.withTone("✌️", 3))
        assertEquals("✌️", SkinTones.withTone("✌🏽", 0))
        assertEquals("👍🏿", SkinTones.withTone("👍🏻", 5))
        assertEquals(5, SkinTones.toneOf("👍🏿"))
        assertEquals("😀", SkinTones.withTone("😀", 3))
    }

    @Test
    fun `picked tones survive being stored, and junk is left out`() {
        val picked = SkinTones.Choices().picking("👍🏽", 3).picking("✋", 0).picked
        assertEquals(picked, SkinTones.decode(SkinTones.encode(picked)))
        assertEquals(mapOf("👍" to 2), SkinTones.decode("👍:2 😀:3 ✋:9 nonsense"))
    }

    @Test
    fun `a new tone moves the emoji in the recents instead of listing it twice`() {
        val recents = Emoji.remember(listOf("😀", "👍"), "👍🏽")
        assertEquals(listOf("👍🏽", "😀"), recents)
    }

    // ---- holding on the grid ------------------------------------------------------------------------------------

    @Test
    fun `holding an emoji with tones opens a row of six above it, at least a fingertip each`() {
        // On the second row, so there is room above it; the top row's goes below instead.
        panel.recents = List(panel.visibleColumns) { "😀" } + "👍"
        val thumb = panel.visibleColumns
        hold(thumb)
        assertEquals(listOf("👍", "👍🏻", "👍🏼", "👍🏽", "👍🏾", "👍🏿"), panel.toneItems)
        assertTrue(panel.toneBoxes.all { it.width >= 48 * density - 0.5f && it.height >= 48 * density - 0.5f })
        assertTrue("above the emoji", panel.toneBoxes.first().bottom <= panel.cellBox(thumb).top)
        assertTrue("on screen", panel.toneBoxes.first().left >= 0 && panel.toneBoxes.last().right <= panel.width)
    }

    @Test
    fun `on the top row the tones go below the emoji, clear of it`() {
        val thumb = people.indexOf("👍")
        hold(thumb)
        assertTrue(panel.toneBoxes.first().top >= panel.cellBox(thumb).bottom)
    }

    @Test
    fun `sliding onto a tone and letting go types it, and later taps keep it`() {
        val thumb = people.indexOf("👍")
        val finger = hold(thumb)
        slide(panel, finger, panel.toneBoxes[3])
        val (x, y) = centre(panel.toneBoxes[3])
        send(panel, MotionEvent.ACTION_UP, x, y)
        assertEquals(listOf("👍🏽"), typed)
        assertEquals(listOf("👍" to 3), toned)
        assertTrue("the row closes", panel.toneItems.isEmpty())

        tap(panel, panel.cellBox(thumb))
        assertEquals("a tap types the remembered tone", "👍🏽", typed.last())
        assertEquals("and the grid shows it", "👍🏽", panel.showing()[thumb])
    }

    @Test
    fun `letting go where it was held types nothing, and the row waits for a tap`() {
        val thumb = people.indexOf("✋")
        val (x, y) = hold(thumb)
        send(panel, MotionEvent.ACTION_UP, x, y)
        assertEquals(emptyList<String>(), typed)
        assertEquals(6, panel.toneItems.size)
        tap(panel, panel.toneBoxes[5])
        assertEquals(listOf("✋🏿"), typed)
        assertEquals(listOf("✋" to 5), toned)
    }

    @Test
    fun `a tap off the open row closes it and types nothing`() {
        val (x, y) = hold(people.indexOf("👍"))
        send(panel, MotionEvent.ACTION_UP, x, y)
        tap(panel, panel.cellBox(people.indexOf("😀")))
        assertTrue(panel.toneItems.isEmpty())
        assertEquals(emptyList<String>(), typed)
    }

    @Test
    fun `an emoji without tones opens no row`() {
        val (x, y) = hold(people.indexOf("😀"))
        assertTrue(panel.toneItems.isEmpty())
        send(panel, MotionEvent.ACTION_UP, x, y)
        assertTrue(panel.toneItems.isEmpty())
    }

    @Test
    fun `a quick tap types the emoji without opening the row`() {
        tap(panel, panel.cellBox(people.indexOf("👍")))
        assertEquals(listOf("👍"), typed)
        idle(1000)
        assertTrue(panel.toneItems.isEmpty())
    }

    @Test
    fun `the Settings tone is where an emoji starts, and one picked for it wins`() {
        panel.tones = SkinTones.Choices(default = 2, picked = mapOf("✋" to 0))
        tap(panel, panel.cellBox(people.indexOf("👍")))
        tap(panel, panel.cellBox(people.indexOf("✋")))
        tap(panel, panel.cellBox(people.indexOf("😀")))
        assertEquals(listOf("👍🏼", "✋", "😀"), typed)
    }

    @Test
    fun `a screen reader holds an emoji and picks a tone from the row`() {
        val provider = panel.accessibilityNodeProvider!!
        val thumb = people.indexOf("👍")
        assertTrue(provider.createAccessibilityNodeInfo(thumb)!!.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK })
        val face = people.indexOf("😀")
        assertFalse(provider.createAccessibilityNodeInfo(face)!!.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK })
        provider.performAction(thumb, AccessibilityNodeInfo.ACTION_LONG_CLICK, null)
        assertEquals(6, panel.toneItems.size)
        val tone = 1_000_000 + 4
        assertEquals("👍🏾", provider.createAccessibilityNodeInfo(tone)!!.contentDescription)
        provider.performAction(tone, AccessibilityNodeInfo.ACTION_CLICK, null)
        assertEquals(listOf("👍🏾"), typed)
        assertTrue(panel.toneItems.isEmpty())
    }

    // ---- search -------------------------------------------------------------------------------------------------

    @Test
    fun `holding a search result offers its tones too`() {
        val search = EmojiSearchPanel(context)
        val found = mutableListOf<String>()
        search.listener = object : EmojiSearchPanel.Listener {
            override fun onEmoji(emoji: String) { found += emoji }
            override fun onBack() = Unit
            override fun onSkinTone(emoji: String, tone: Int) { search.tones = search.tones.picking(emoji, tone) }
        }
        search.search = EmojiSearch.load(context, Language.ENGLISH)
        search.opened(Settings(), Language.ENGLISH)
        val width = (411 * density).toInt()
        search.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        search.layout(0, 0, search.measuredWidth, search.measuredHeight)
        "thumbs".forEach { letter ->
            val key = search.keys.placements.first { it.key.label == letter.toString() }
            tap(search.keys, key.box)
        }
        val index = search.results.indexOf("👍")
        assertTrue("found ${search.results}", index >= 0)

        val (x, y) = centre(search.bar.resultBox(index))
        send(search.bar, MotionEvent.ACTION_DOWN, x, y)
        idle(1000)
        assertEquals(6, search.bar.toneItems.size)
        assertTrue("inside the row", search.bar.toneBoxes.all { it.top >= 0f && it.height >= 48 * density - 0.5f })
        slide(search.bar, x to y, search.bar.toneBoxes[2])
        val (lx, ly) = centre(search.bar.toneBoxes[2])
        send(search.bar, MotionEvent.ACTION_UP, lx, ly)
        assertEquals(listOf("👍🏼"), found)
        assertTrue(search.bar.spoken().any { it.endsWith("medium-light skin tone") })

        search.tapResultForTest(index)
        assertEquals("👍🏼", found.last())
    }

    // ---- Settings -----------------------------------------------------------------------------------------------

    @Test
    fun `Smart typing has a Skin tone row under Emoji, each tone read by name`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        fun all(): List<View> {
            val out = mutableListOf<View>()
            fun walk(v: View) { out += v; if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
            walk(activity.window.decorView)
            return out
        }
        fun label(text: String) = all().filterIsInstance<TextView>().firstOrNull { it.text.toString() == text }
        var row: View = label("Smart typing")!!
        while (!row.isClickable && row.parent is View) row = row.parent as View
        row.performClick()
        assertNotNull(label("EMOJI"))
        assertNotNull(label(activity.getString(R.string.settings_skin_tone_note)))
        val segmented = all().filterIsInstance<Segmented>().first { it.contentDescription == "Skin tone" }
        assertEquals(0, segmented.selected)
        assertEquals("✋🏽", segmented.option(3).text.toString())
        assertEquals("Medium skin tone", segmented.option(3).contentDescription)
        assertEquals("Default skin tone", segmented.option(0).contentDescription)
        segmented.option(3).performClick()
        assertEquals(3, Settings.load(prefs).emojiSkinTone)
    }
}
