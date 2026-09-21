package com.mccal.folio.keys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * The coloured square at the start of a settings row, iOS-style, with a white glyph drawn on it.
 *
 * Drawn rather than loaded, like every key on the keyboard: Keyd takes no image or icon libraries, and a glyph
 * this size is a few lines of path. Where a key already has the right glyph ([Icons.globe], [Icons.clipboard],
 * [Icons.shift]) the row uses the same one, so the settings and the keyboard speak the same visual language.
 */
// Only ever built in code, with its glyph and colour, so the layout-inflation constructors would have nothing to
// build one from.
@android.annotation.SuppressLint("ViewConstructor")
internal class SettingsIcon(context: Context, private val glyph: Glyph, private val tile: Int) : View(context) {

    enum class Glyph { LANGUAGES, SHORTCUTS, TYPING, KEYS, LOOK, SOUND, CLIPBOARD, PRIVACY }

    private val density = context.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.7f * density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tile }
    private val box = RectF()
    private val path = Path()

    init {
        // The row says what it is; the picture only repeats it.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        box.set(0f, 0f, size, size)
        canvas.drawRoundRect(box, size * .23f, size * .23f, back)
        val cx = size / 2
        val cy = size / 2
        val g = size * .56f
        when (glyph) {
            Glyph.LANGUAGES -> Icons.globe(canvas, cx, cy, g, stroke)
            Glyph.CLIPBOARD -> Icons.clipboard(canvas, cx, cy, g, stroke, fill)
            Glyph.KEYS -> Icons.shift(canvas, cx, cy, g, fill, locked = false)
            Glyph.SHORTCUTS -> {
                // An abbreviation opening out: a short line, then a long one.
                canvas.drawLine(cx - g * .42f, cy - g * .2f, cx + g * .05f, cy - g * .2f, stroke)
                canvas.drawLine(cx - g * .42f, cy + g * .2f, cx + g * .42f, cy + g * .2f, stroke)
                canvas.drawLine(cx + g * .2f, cy - g * .38f, cx + g * .42f, cy - g * .2f, stroke)
                canvas.drawLine(cx + g * .2f, cy - g * .02f, cx + g * .42f, cy - g * .2f, stroke)
            }
            Glyph.TYPING -> {
                // A text cursor between two lines of writing.
                canvas.drawLine(cx - g * .45f, cy - g * .25f, cx + g * .1f, cy - g * .25f, stroke)
                canvas.drawLine(cx - g * .45f, cy + g * .25f, cx - g * .05f, cy + g * .25f, stroke)
                canvas.drawLine(cx + g * .3f, cy - g * .45f, cx + g * .3f, cy + g * .45f, stroke)
            }
            Glyph.LOOK -> {
                // Light or dark: a circle, half of it filled.
                val r = g * .42f
                canvas.drawCircle(cx, cy, r, stroke)
                path.reset()
                path.addArc(cx - r, cy - r, cx + r, cy + r, 90f, 180f)
                path.close()
                canvas.drawPath(path, fill)
            }
            Glyph.SOUND -> {
                // A speaker and one wave.
                path.reset()
                path.moveTo(cx - g * .45f, cy - g * .14f)
                path.lineTo(cx - g * .25f, cy - g * .14f)
                path.lineTo(cx - g * .02f, cy - g * .36f)
                path.lineTo(cx - g * .02f, cy + g * .36f)
                path.lineTo(cx - g * .25f, cy + g * .14f)
                path.lineTo(cx - g * .45f, cy + g * .14f)
                path.close()
                canvas.drawPath(path, fill)
                canvas.drawArc(cx - g * .1f, cy - g * .3f, cx + g * .4f, cy + g * .3f, -50f, 100f, false, stroke)
            }
            Glyph.PRIVACY -> {
                // A padlock.
                val w = g * .62f
                box.set(cx - w / 2, cy - g * .05f, cx + w / 2, cy + g * .45f)
                canvas.drawRoundRect(box, g * .08f, g * .08f, fill)
                canvas.drawArc(cx - w * .34f, cy - g * .45f, cx + w * .34f, cy + g * .15f, 180f, 180f, false, stroke)
            }
        }
    }
}
