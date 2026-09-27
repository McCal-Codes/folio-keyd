package com.mccal.folio.keys

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Make a text shortcut" from a pinned clip: the long half arrives filled in, and nothing is added until Add. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShortcutsActivityTest {

    private fun ShortcutsActivity.all(): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) { out += v; if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(window.decorView)
        return out
    }

    private fun open(intent: Intent): ShortcutsActivity {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("keys", Context.MODE_PRIVATE).edit().clear().commit()
        return Robolectric.buildActivity(ShortcutsActivity::class.java, intent).setup().get()
    }

    @Test
    fun `a pinned clip becomes the phrase, with a note saying where it came from`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val a = open(Intent(context, ShortcutsActivity::class.java)
            .putExtra(ShortcutsActivity.EXTRA_EXPANSION, "12 Main St\nPittsburgh"))
        val (trigger, phrase) = a.all().filterIsInstance<EditText>()
        assertEquals("", trigger.text.toString())
        assertEquals("12 Main St Pittsburgh", phrase.text.toString())
        val note = a.all().filterIsInstance<TextView>().single { it.text.toString() == a.getString(R.string.shortcuts_from_clip) }
        assertEquals(View.VISIBLE, note.visibility)
        // Nothing saved yet.
        assertEquals(0, Shortcuts.decode(a.getSharedPreferences("keys", Context.MODE_PRIVATE).getString("shortcuts", null)).size)
    }

    @Test
    fun `opened the ordinary way there is no note`() {
        val a = open(Intent(ApplicationProvider.getApplicationContext(), ShortcutsActivity::class.java))
        val note = a.all().filterIsInstance<TextView>().single { it.text.toString() == a.getString(R.string.shortcuts_from_clip) }
        assertEquals(View.GONE, note.visibility)
        assertTrue(a.all().filterIsInstance<EditText>().all { it.text.isEmpty() })
    }

    /** From a clip, making the shortcut is the whole errand, so Add goes back to what was being typed in. */
    @Test
    fun `adding a shortcut from a clip closes the page`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val a = open(Intent(context, ShortcutsActivity::class.java).putExtra(ShortcutsActivity.EXTRA_EXPANSION, "OK's"))
        val (trigger, _) = a.all().filterIsInstance<EditText>()
        trigger.setText("oks")
        a.all().filterIsInstance<android.widget.Button>().single().performClick()
        assertTrue(a.isFinishing)
        val saved = Shortcuts.decode(a.getSharedPreferences("keys", Context.MODE_PRIVATE).getString("shortcuts", null))
        assertEquals(1, saved.size)
    }

    @Test
    fun `adding one the ordinary way keeps the page open`() {
        val a = open(Intent(ApplicationProvider.getApplicationContext(), ShortcutsActivity::class.java))
        val (trigger, phrase) = a.all().filterIsInstance<EditText>()
        trigger.setText("brb")
        phrase.setText("be right back")
        a.all().filterIsInstance<android.widget.Button>().single().performClick()
        assertTrue(!a.isFinishing)
    }
}
