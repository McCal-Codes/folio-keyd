package com.mccal.folio.keys

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Each feature on What's New gets a picture of its own, not the shift arrow everything used to fall back on. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xhdpi")
class WhatsNewIconsTest {

    @Test
    fun `no two of this release's features share a picture`() {
        val titles = listOf("Languages", "Period symbols", "Two fingers to undo", "Emoji in the strip", "Gesture tips",
            "Hold delay and backspace speed", "Delete forward")
        val glyphs = titles.map { WhatsNew.glyph(it).first }
        assertEquals(glyphs.toString(), titles.size, glyphs.toSet().size)
    }

    @Test
    fun `the five features on any release's sheet never share a picture`() {
        val markdown = File("../CHANGELOG.md").takeIf { it.exists() } ?: File("CHANGELOG.md")
        val releases = WhatsNew.parse(markdown.readText())
        assertTrue(releases.size >= 5)
        for (release in releases) {
            val titles = release.sections.filter { it.first.equals("Added", true) }.flatMap { it.second }
                .map(WhatsNew::split).take(5).map { it.title ?: it.detail }
            val glyphs = WhatsNew.glyphs(titles).map { it.first }
            assertEquals("${release.version}: $glyphs", titles.size, glyphs.toSet().size)
        }
        // A title with nothing of its own to match still gets a picture nobody else on the sheet has.
        val plain = WhatsNew.glyphs(listOf("Faster", "Smaller", "Quieter")).map { it.first }
        assertEquals(3, plain.toSet().size)
    }

    @Test
    fun `every glyph draws, for a look in build renders`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val side = 96
        val glyphs = SettingsIcon.Glyph.entries
        val sheet = Bitmap.createBitmap(side * glyphs.size, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet).apply { drawColor(Color.BLACK) }
        glyphs.forEachIndexed { i, glyph ->
            val icon = SettingsIcon(context, glyph, Color.parseColor("#C93400"))
            icon.measure(View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY))
            icon.layout(0, 0, side, side)
            canvas.save()
            canvas.translate((i * side).toFloat(), 0f)
            icon.draw(canvas)
            canvas.restore()
        }
        File("build/renders").apply { mkdirs() }.resolve("whats-new-glyphs.png").outputStream().use {
            sheet.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
