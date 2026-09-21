package com.mccal.folio.keys

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The clipboard history, and mostly the things it refuses to remember.
 *
 * A keyboard that keeps what you copy is a keyboard that can leak it, so the interesting cases here are the password
 * field, the clip an app marked sensitive, and the hour after which an unpinned clip is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager get() = Clipboard.manager(context)!!
    private val prefs get() = context.getSharedPreferences("keys-test", Context.MODE_PRIVATE)
    private val now = 1_700_000_000_000L

    private fun clip(text: String) = ClipData.newPlainText("", text)

    private fun sensitive(text: String) = clip(text).apply {
        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }

    // ---- what may be taken at all ------------------------------------------------------------------------------

    @Test fun `plain text on the clipboard is readable`() {
        manager.setPrimaryClip(clip("shipping address"))
        assertEquals("shipping address", Clipboard.readable(manager, FieldRules()))
    }

    @Test fun `nothing is taken while a password field is open`() {
        manager.setPrimaryClip(clip("correct horse battery staple"))
        assertNull(Clipboard.readable(manager, FieldRules(password = true)))
        // Nor when the app asked for nothing to be remembered from it.
        assertNull(Clipboard.readable(manager, FieldRules(noLearning = true)))
    }

    @Test fun `a clip its own app marked sensitive is left alone`() {
        manager.setPrimaryClip(sensitive("123456"))
        assertNull(Clipboard.readable(manager, FieldRules()))
    }

    @Test fun `blank and empty clipboards give nothing`() {
        manager.setPrimaryClip(clip("   "))
        assertNull(Clipboard.readable(manager, FieldRules()))
    }

    @Test fun `a clip longer than the limit is kept as its beginning`() {
        manager.setPrimaryClip(clip("x".repeat(Clipboard.MAX_LENGTH + 500)))
        assertEquals(Clipboard.MAX_LENGTH, Clipboard.readable(manager, FieldRules())?.length)
    }

    // ---- what the list does with it ----------------------------------------------------------------------------

    @Test fun `the newest clip is first`() {
        val one = Clipboard.remembering(emptyList(), "first", now)
        val two = Clipboard.remembering(one, "second", now + 1)
        assertEquals(listOf("second", "first"), two.map { it.text })
    }

    @Test fun `copying the same thing twice moves it up rather than doubling it`() {
        var history = Clipboard.remembering(emptyList(), "address", now)
        history = Clipboard.remembering(history, "phone", now + 1)
        history = Clipboard.remembering(history, "address", now + 2)
        assertEquals(listOf("address", "phone"), history.map { it.text })
    }

    @Test fun `copying a pinned clip again keeps its pin`() {
        var history = Clipboard.remembering(emptyList(), "address", now)
        history = Clipboard.pinning(history, "address", true)
        history = Clipboard.remembering(history, "address", now + 5)
        assertEquals(true, history.single().pinned)
    }

    @Test fun `the list is capped, and the cap only ever drops loose clips`() {
        var history = emptyList<Clipboard.Clip>()
        for (index in 1..Clipboard.KEPT + 5) history = Clipboard.remembering(history, "clip $index", now + index)
        assertEquals(Clipboard.KEPT, history.size)
        assertEquals("clip ${Clipboard.KEPT + 5}", history.first().text)

        // With a pin at the bottom, the pin survives and a loose clip goes instead.
        history = Clipboard.pinning(history, history.last().text, true)
        val pinned = history.last().text
        history = Clipboard.remembering(history, "one more", now + 100)
        assertEquals(Clipboard.KEPT, history.size)
        assertTrue("the pinned clip should have survived the cap", history.any { it.text == pinned })
    }

    // ---- and what it forgets ------------------------------------------------------------------------------------

    @Test fun `an unpinned clip is gone after an hour, a pinned one is not`() {
        var history = Clipboard.remembering(emptyList(), "loose", now)
        history = Clipboard.remembering(history, "kept", now)
        history = Clipboard.pinning(history, "kept", true)

        val soon = Clipboard.current(history, now + Clipboard.KEEP_MILLIS - 1)
        assertEquals(setOf("loose", "kept"), soon.map { it.text }.toSet())

        val later = Clipboard.current(history, now + Clipboard.KEEP_MILLIS + 1)
        assertEquals(listOf("kept"), later.map { it.text })
    }

    @Test fun `forgetting one leaves the rest, and clearing leaves nothing`() {
        var history = Clipboard.remembering(emptyList(), "one", now)
        history = Clipboard.remembering(history, "two", now + 1)
        assertEquals(listOf("two"), Clipboard.forgetting(history, "one").map { it.text })
        assertEquals(emptyList<Clipboard.Clip>(), Clipboard.cleared())
    }

    @Test fun `what is saved comes back, and an expired clip does not`() {
        var history = Clipboard.remembering(emptyList(), "loose", now)
        history = Clipboard.remembering(history, "kept", now)
        history = Clipboard.pinning(history, "kept", true)
        Clipboard.save(prefs, history)

        val sameSitting = Clipboard.load(prefs, now + 1)
        assertEquals(setOf("loose", "kept"), sameSitting.map { it.text }.toSet())
        assertEquals(true, sameSitting.single { it.text == "kept" }.pinned)

        val tomorrow = Clipboard.load(prefs, now + 24 * Clipboard.KEEP_MILLIS)
        assertEquals(listOf("kept"), tomorrow.map { it.text })

        Clipboard.clear(prefs)
        assertEquals(emptyList<Clipboard.Clip>(), Clipboard.load(prefs, now))
    }

    @Test fun `nonsense on disk is no history rather than a crash`() {
        prefs.edit().putString("clipboard", "not json at all").apply()
        assertEquals(emptyList<Clipboard.Clip>(), Clipboard.load(prefs, now))
    }
}
