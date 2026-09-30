package com.mccal.folio.keys

import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
 * What the service keeps between fields, and what it lets go: the emoji names across a rebuilt view, the app list,
 * the strip's question, the counts and when they are saved, and the Bluetooth listener.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ServiceStateTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("keys", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    private fun service() = Robolectric.buildService(KeysService::class.java).create().get()

    /** Both threads, until neither has anything left to do now. */
    private fun KeysService.settle() = repeat(3) {
        shadowOf(suggestionLooper).idle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private inline fun <reified T> ViewGroup.child(): T =
        (0 until childCount).map { getChildAt(it) }.filterIsInstance<T>().single()

    private fun field(app: String? = null, inputType: Int = InputType.TYPE_CLASS_TEXT, imeOptions: Int = 0) =
        EditorInfo().also {
            it.packageName = app
            it.inputType = inputType
            it.imeOptions = imeOptions
        }

    @Test
    fun `the emoji names reach a search built again after a rotation`() {
        val service = service()
        repeat(2) { time ->
            val root = service.onCreateInputView() as ViewGroup
            service.showEmoji(true)
            root.child<EmojiPanel>().listener!!.onSearch()
            service.settle()
            assertNotNull("search number ${time + 1} has no names", root.child<EmojiSearchPanel>().search)
        }
    }

    @Test
    fun `what was searched for goes when the search closes`() {
        val service = service()
        val root = service.onCreateInputView() as ViewGroup
        service.showEmoji(true)
        root.child<EmojiPanel>().listener!!.onSearch()
        val finder = root.child<EmojiSearchPanel>()
        finder.keys.listener!!.onText("h")
        assertEquals("h", finder.query)
        finder.listener!!.onBack()
        assertEquals("", finder.query)
        assertTrue(finder.results.isEmpty())
    }

    @Test
    fun `a password field or one that asked not to be learned from is not noted as an app typed in`() {
        AppProfiles().apply { set("com.bank", AppProfiles.Profile(useUsual = false, autocorrect = false)) }.save(prefs)
        val before = AppProfiles.load(prefs).recentApps
        val service = service()
        service.onStartInputView(
            field("com.vault", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD), false,
        )
        service.onStartInputView(field("com.diary", imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING), false)
        assertEquals(before, AppProfiles.load(prefs).recentApps)
        // An app already on the list still gets its own settings there.
        assertFalse(service.settingsFor("com.bank", noted = false).autocorrect)
        service.onStartInputView(field("com.vault"), false)
        assertEquals("com.vault", AppProfiles.load(prefs).recentApps.first())
    }

    @Test
    fun `the strip's question stays in its app, and never shows over a field that keeps nothing`() {
        val service = service()
        val keys = (service.onCreateInputView() as ViewGroup).child<KeyboardView>()
        val offer = Insights.Offer("Folio", null)
        service.onStartInputView(field("com.chat"), false)
        keys.offer = offer
        service.onStartInputView(field("com.chat"), false)
        assertEquals(offer, keys.offer)
        service.onStartInputView(field("com.mail"), false)
        assertNull(keys.offer)
        keys.offer = offer
        service.onStartInputView(field("com.mail", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD), false)
        assertNull(keys.offer)
    }

    @Test
    fun `counted fixes are saved together, a moment later or when the field closes`() {
        val service = service()
        service.settle()
        service.fixStood("teh", "the")
        service.fixStood("teh", "the")
        service.settle()
        assertNull("saved on every word", prefs.getString(KEY_INSIGHTS, null))
        service.onFinishInput()
        service.settle()
        assertEquals(2, Insights.decode(prefs.getString(KEY_INSIGHTS, null)).fixed().single().count)
        service.fixStood("teh", "the")
        service.settle()
        shadowOf(service.suggestionLooper).idleFor(Duration.ofSeconds(3))
        assertEquals(3, Insights.decode(prefs.getString(KEY_INSIGHTS, null)).fixed().single().count)
    }

    @Test
    fun `what Settings changes is not put back by the keyboard's older copy`() {
        val service = service()
        service.settle()
        repeat(4) { service.fixStood("teh", "the") }
        service.onFinishInput()
        service.settle()
        // Forgotten in Settings while the keyboard is still up, with no new field in between.
        prefs.edit().remove(KEY_INSIGHTS).commit()
        service.fixStood("teh", "the")
        service.onFinishInput()
        service.settle()
        assertEquals(1, Insights.decode(prefs.getString(KEY_INSIGHTS, null)).fixed().single().count)
        // And a shortcut made in Settings survives the strip's answer being saved.
        prefs.edit().putString("shortcuts", Shortcuts().apply { add("omw", "on my way") }.encode()).commit()
        service.answered(Insights.Offer("teh", "the"), accepted = true)
        service.settle()
        val rules = Shortcuts.decode(prefs.getString("shortcuts", null))
        assertEquals("on my way", rules.expand("omw"))
        assertEquals("the", rules.expand("teh"))
    }

    @Test
    fun `forgetting takes the counts with the learned words`() {
        prefs.edit()
            .putString("learnedWords", Learned().apply { learn("keyd") }.encode())
            .putString(KEY_INSIGHTS, Insights().apply { fixStood("teh", "the", offering = false) }.encode())
            .commit()
        val service = service()
        service.settle()
        service.forgetLearned()
        service.settle()
        assertNull(prefs.getString("learnedWords", null))
        assertNull(prefs.getString(KEY_INSIGHTS, null))
    }

    @Test
    fun `one Bluetooth listener, and only while the keyboard is on screen`() {
        val service = service()
        val root = service.onCreateInputView() as ViewGroup
        val keys = root.child<KeyboardView>()
        assertSame(keys.feedback, root.child<EmojiSearchPanel>().keys.feedback)
        assertFalse(keys.feedback.isListening)
        service.onWindowShown()
        assertTrue(keys.feedback.isListening)
        service.onWindowHidden()
        assertFalse(keys.feedback.isListening)
    }

    @Test
    fun `an open row of tones does not come back after a hide or in a new field`() {
        val service = service()
        val root = service.onCreateInputView() as ViewGroup
        service.showEmoji(true)
        val panel = root.child<EmojiPanel>()
        fun holdThumbsUp() {
            panel.recents = listOf("👍")
            panel.selectCategory(0)
            val width = (411 * panel.resources.displayMetrics.density).toInt()
            panel.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            )
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            val box = panel.cellBox(0)
            val down = android.view.MotionEvent.obtain(
                0, 0, android.view.MotionEvent.ACTION_DOWN, (box.left + box.right) / 2, (box.top + box.bottom) / 2, 0,
            )
            panel.onTouchEvent(down)
            down.recycle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
            assertEquals(6, panel.toneItems.size)
        }
        holdThumbsUp()
        service.onWindowHidden()
        service.showEmoji(true)
        assertTrue("after a hide", panel.toneItems.isEmpty())
        holdThumbsUp()
        service.onFinishInput()
        service.onStartInputView(field("com.chat"), false)
        service.showEmoji(true)
        assertTrue("in a new field", panel.toneItems.isEmpty())
    }

    private companion object {
        const val KEY_INSIGHTS = "typingInsights"
    }
}
