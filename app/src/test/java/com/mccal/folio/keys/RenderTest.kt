package com.mccal.folio.keys

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the real keyboard into a PNG.
 *
 * Not an assertion: a picture. A phone can refuse to run the keyboard - a device policy, a locked-down IME list, a
 * fold that hands the screen to someone else's keyboard - and none of that says anything about whether ours looks
 * right. This renders the same view the phone would, at the sizes that matter, into `build/renders/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RenderTest {

    private fun render(name: String, widthDp: Int, heightDp: Int, night: Boolean, build: (KeyboardView) -> Unit) {
        // Qualifiers rather than a hand-built Configuration: this is how a phone describes itself, and it gives the
        // render a real density, so the picture shows what someone would actually see.
        org.robolectric.RuntimeEnvironment.setQualifiers(
            "+w${widthDp}dp-h${heightDp}dp-" + (if (night) "night" else "notnight") + "-xhdpi",
        )
        val context = ApplicationProvider.getApplicationContext<Context>()

        val view = KeyboardView(context)
        build(view)
        val density = context.resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val bitmap = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
        // The board is drawn on whatever is behind it, so the picture gets a stand-in for the app.
        Canvas(bitmap).also { canvas ->
            canvas.drawColor(if (night) 0xFF101014.toInt() else 0xFFF2F2F7.toInt())
            view.draw(canvas)
        }
        val out = File("build/renders").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("rendered $name -> ${out.absolutePath} (${view.measuredWidth}x${view.measuredHeight})")
    }

    private fun letters(view: KeyboardView, rules: FieldRules = FieldRules(), layer: Layer = Layer.LETTERS) {
        view.rules = rules
        view.rows = Layouts.rows(layer, false, rules)
    }

    /** The emoji grid, drawn the same way: the picture is the only way to see that the cells line up. */
    private fun renderEmoji(name: String, widthDp: Int, heightDp: Int, night: Boolean, category: Int) {
        org.robolectric.RuntimeEnvironment.setQualifiers(
            "+w${widthDp}dp-h${heightDp}dp-" + (if (night) "night" else "notnight") + "-xhdpi",
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val panel = EmojiPanel(context)
        panel.selectCategory(category)
        val density = context.resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt()
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        val bitmap = Bitmap.createBitmap(panel.measuredWidth, panel.measuredHeight, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).also { canvas ->
            canvas.drawColor(if (night) 0xFF101014.toInt() else 0xFFF2F2F7.toInt())
            panel.draw(canvas)
        }
        val out = File("build/renders").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("rendered $name -> ${out.absolutePath}")
    }

    @Test
    fun `the emoji, as a phone would draw them`() {
        renderEmoji("emoji-phone-dark", 411, 891, night = true, category = 1)
        renderEmoji("emoji-phone-light", 411, 891, night = false, category = 1)
        renderEmoji("emoji-fold-dark", 932, 704, night = true, category = 1)
        renderEmoji("emoji-empty-recents-dark", 411, 891, night = true, category = 0)
    }

    @Test
    fun `the keyboard, as a phone would draw it`() {
        render("phone-dark", 411, 891, night = true) { letters(it) }
        render("phone-light", 411, 891, night = false) { letters(it) }
        render("fold-inner-dark", 932, 704, night = true) { letters(it) }
        render("fold-cover-dark", 475, 751, night = true) { letters(it) }
        render("email-dark", 411, 891, night = true) {
            letters(it, FieldRules(kind = FieldKind.EMAIL, actionLabel = "Go"))
        }
        render("symbols-dark", 411, 891, night = true) { letters(it, layer = Layer.NUMBERS) }
        render("tablet-capped-dark", 540, 860, night = true) { letters(it) }
        render("phone-landscape-dark", 891, 411, night = true) { letters(it) }
        render("password-light", 411, 891, night = false) {
            letters(it, FieldRules(password = true, actionLabel = "Done"))
        }
    }
}
