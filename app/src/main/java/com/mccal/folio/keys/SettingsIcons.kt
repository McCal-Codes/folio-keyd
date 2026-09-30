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

    enum class Glyph {
        LANGUAGES, SHORTCUTS, TYPING, KEYS, LOOK, SOUND, CLIPBOARD, PRIVACY, WARNING,
        UNDO, EMOJI, TIP, SEARCH, BACKSPACE, SELECT, MOVE, SYMBOLS, TIMER,
        WAND, HAND, PALETTE, SPARKLES, REPORT, APPS, CHOICES, LETTERS, TRASH, NOT_LEARNING,
    }

    private val density = context.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.7f * density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tile }
    /** The tile's own color as a line, for what a glyph cuts out of itself, like the cross in backspace. */
    private val punch by lazy { Paint(stroke).apply { color = tile } }
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
            Glyph.WARNING -> {
                // A triangle with an exclamation mark, the sign every platform uses for "something went wrong".
                path.reset()
                path.moveTo(cx, cy - g * .45f)
                path.lineTo(cx + g * .48f, cy + g * .38f)
                path.lineTo(cx - g * .48f, cy + g * .38f)
                path.close()
                canvas.drawPath(path, stroke)
                canvas.drawLine(cx, cy - g * .12f, cx, cy + g * .1f, stroke)
                canvas.drawCircle(cx, cy + g * .24f, stroke.strokeWidth * .7f, fill)
            }
            Glyph.TRASH -> Icons.trash(canvas, cx, cy, g, stroke)
            Glyph.NOT_LEARNING -> Icons.eyeOff(canvas, cx, cy, g, stroke)
            Glyph.LETTERS -> {
                // "Aa": the letters, as a keyboard's own layer key would say it.
                fill.textSize = g * .78f
                fill.textAlign = Paint.Align.CENTER
                fill.isFakeBoldText = true
                canvas.drawText("Aa", cx, cy - (fill.descent() + fill.ascent()) / 2, fill)
            }
            Glyph.CHOICES -> {
                // A segmented control: one rounded bar in three parts, the middle one chosen.
                val w = g * .92f
                val h = g * .44f
                box.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
                canvas.drawRoundRect(box, h / 2, h / 2, stroke)
                box.set(cx - w / 6, cy - h / 2, cx + w / 6, cy + h / 2)
                canvas.drawRoundRect(box, h * .3f, h * .3f, fill)
            }
            Glyph.UNDO -> Icons.undo(canvas, cx, cy, g, stroke)
            Glyph.EMOJI -> Icons.smiley(canvas, cx, cy, g, stroke, fill)
            Glyph.TIP -> Icons.bulb(canvas, cx, cy, g, stroke)
            Glyph.SEARCH -> Icons.magnifier(canvas, cx, cy, g, stroke)
            Glyph.BACKSPACE -> Icons.backspace(canvas, cx, cy, g, fill, punch)
            Glyph.SELECT -> Icons.selectAll(canvas, cx, cy, g, stroke, fill)
            Glyph.MOVE -> Icons.move(canvas, cx, cy, g, stroke)
            Glyph.SYMBOLS -> {
                // The punctuation holding the period offers, as it is written.
                fill.textSize = g * .82f
                fill.textAlign = Paint.Align.CENTER
                fill.isFakeBoldText = true
                canvas.drawText("?!", cx, cy - (fill.descent() + fill.ascent()) / 2, fill)
            }
            Glyph.TIMER -> {
                // A clock face: how long a hold takes.
                val r = g * .42f
                canvas.drawCircle(cx, cy, r, stroke)
                canvas.drawLine(cx, cy, cx, cy - r * .62f, stroke)
                canvas.drawLine(cx, cy, cx + r * .45f, cy + r * .2f, stroke)
            }
            Glyph.WAND -> {
                // A wand with two sparks off its tip: typing that fixes itself.
                canvas.drawLine(cx - g * .42f, cy + g * .42f, cx + g * .2f, cy - g * .2f, stroke)
                spark(canvas, cx + g * .3f, cy - g * .36f, g * .13f)
                spark(canvas, cx - g * .12f, cy - g * .34f, g * .09f)
                spark(canvas, cx + g * .38f, cy + g * .08f, g * .08f)
            }
            Glyph.HAND -> {
                // A finger pointing up from a hand: touches, holds and swipes.
                box.set(cx - g * .1f, cy - g * .5f, cx + g * .1f, cy + g * .05f)
                canvas.drawRoundRect(box, g * .1f, g * .1f, stroke)
                box.set(cx - g * .32f, cy - g * .08f, cx + g * .32f, cy + g * .46f)
                canvas.drawRoundRect(box, g * .16f, g * .16f, stroke)
            }
            Glyph.PALETTE -> {
                // A painter's palette: a round board and three dabs of paint.
                val r = g * .44f
                canvas.drawCircle(cx, cy, r, stroke)
                val dot = g * .075f
                canvas.drawCircle(cx - r * .42f, cy - r * .2f, dot, fill)
                canvas.drawCircle(cx, cy - r * .5f, dot, fill)
                canvas.drawCircle(cx + r * .42f, cy - r * .2f, dot, fill)
                canvas.drawCircle(cx + r * .2f, cy + r * .42f, dot * 1.4f, fill)
            }
            Glyph.SPARKLES -> {
                // Two four-pointed stars, the sign for something new.
                star(canvas, cx - g * .08f, cy + g * .06f, g * .38f)
                star(canvas, cx + g * .3f, cy - g * .3f, g * .16f)
            }
            Glyph.REPORT -> {
                // A speech bubble with an exclamation mark: telling someone what went wrong.
                box.set(cx - g * .45f, cy - g * .38f, cx + g * .45f, cy + g * .22f)
                canvas.drawRoundRect(box, g * .14f, g * .14f, stroke)
                path.reset()
                path.moveTo(cx - g * .2f, cy + g * .22f)
                path.lineTo(cx - g * .28f, cy + g * .45f)
                path.lineTo(cx + g * .02f, cy + g * .22f)
                canvas.drawPath(path, stroke)
                canvas.drawLine(cx, cy - g * .24f, cx, cy - g * .04f, stroke)
                canvas.drawCircle(cx, cy + g * .08f, stroke.strokeWidth * .7f, fill)
            }
            Glyph.APPS -> {
                // Four app tiles: settings for each app.
                val s = g * .36f
                val d = g * .06f
                for ((dx, dy) in listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)) {
                    val x = if (dx < 0) cx - d - s else cx + d
                    val y = if (dy < 0) cy - d - s else cy + d
                    box.set(x, y, x + s, y + s)
                    canvas.drawRoundRect(box, s * .28f, s * .28f, fill)
                }
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

    /** A small plus-shaped spark, as drawn beside a wand. */
    private fun spark(canvas: Canvas, x: Float, y: Float, r: Float) {
        canvas.drawLine(x - r, y, x + r, y, stroke)
        canvas.drawLine(x, y - r, x, y + r, stroke)
    }

    /** A filled four-pointed star with curved sides. */
    private fun star(canvas: Canvas, x: Float, y: Float, r: Float) {
        path.reset()
        path.moveTo(x, y - r)
        path.quadTo(x, y, x + r, y)
        path.quadTo(x, y, x, y + r)
        path.quadTo(x, y, x - r, y)
        path.quadTo(x, y, x, y - r)
        path.close()
        canvas.drawPath(path, fill)
    }
}

/** The magnifier at the start of the search field: the emoji search's own, in the field's grey, with no tile. */
@android.annotation.SuppressLint("ViewConstructor")
internal class SearchGlyph(context: Context, color: Int) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.8f * context.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        Icons.magnifier(canvas, width / 2f, height / 2f, minOf(width, height) * .8f, stroke)
    }
}
