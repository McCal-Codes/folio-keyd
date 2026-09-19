package com.mccal.folio.keys

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * The glyphs on the keys that aren't letters, drawn rather than typed.
 *
 * Unicode characters like ⇧ and ⌫ come out of whatever font the system hands over: different weights, different
 * sizes, different vertical centring, and often a box on a phone that lacks them. Every keyboard worth using draws
 * these, which is most of the difference between a grid of characters and something that looks finished.
 *
 * Each icon is drawn inside a square of [size] centred on (cx, cy), so a key only has to say where its middle is.
 */
object Icons {

    private val path = Path()

    /** An outline arrow, which is what a modern keyboard shows; a solid one looks like a road sign. */
    fun shift(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint, locked: Boolean) {
        val half = size / 2
        val shoulder = half * 0.34f
        path.reset()
        path.moveTo(cx, cy - half)                       // the point
        path.lineTo(cx + half, cy + shoulder * 0.2f)     // right shoulder
        path.lineTo(cx + shoulder, cy + shoulder * 0.2f)
        path.lineTo(cx + shoulder, cy + half * 0.72f)    // right side of the stem
        path.lineTo(cx - shoulder, cy + half * 0.72f)
        path.lineTo(cx - shoulder, cy + shoulder * 0.2f)
        path.lineTo(cx - half, cy + shoulder * 0.2f)
        path.close()
        canvas.drawPath(path, paint)
        // Caps lock is the same arrow with a bar under it, the way every keyboard has always shown it.
        if (locked) {
            canvas.drawRect(cx - shoulder, cy + half * 0.84f, cx + shoulder, cy + half * 1.04f, paint)
        }
    }

    /** An outline tag with a cross in it, to match the shift arrow rather than fighting it. */
    fun backspace(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint, stroke: Paint) {
        val w = size * 0.62f
        val h = size * 0.46f
        val left = cx - w
        path.reset()
        path.moveTo(left, cy)                    // the point of the tag
        path.lineTo(left + h, cy - h)
        path.lineTo(cx + w * 0.72f, cy - h)
        path.lineTo(cx + w * 0.72f, cy + h)
        path.lineTo(left + h, cy + h)
        path.close()
        canvas.drawPath(path, paint)
        // The cross inside, punched out in the board's colour by the caller's stroke paint.
        val arm = h * 0.42f
        val kx = cx + w * 0.08f
        canvas.drawLine(kx - arm, cy - arm, kx + arm, cy + arm, stroke)
        canvas.drawLine(kx + arm, cy - arm, kx - arm, cy + arm, stroke)
    }

    /** The return arrow: down the right side, then left, with a head. */
    fun enter(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint) {
        val w = size * 0.52f
        val h = size * 0.40f
        val right = cx + w
        val left = cx - w
        canvas.drawLine(right, cy - h, right, cy + h * 0.55f, stroke)
        canvas.drawLine(right, cy + h * 0.55f, left, cy + h * 0.55f, stroke)
        val head = size * 0.26f
        canvas.drawLine(left, cy + h * 0.55f, left + head, cy + h * 0.55f - head, stroke)
        canvas.drawLine(left, cy + h * 0.55f, left + head, cy + h * 0.55f + head, stroke)
    }

    fun globe(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint) {
        val r = size * 0.46f
        canvas.drawCircle(cx, cy, r, stroke)
        canvas.drawLine(cx - r, cy, cx + r, cy, stroke)
        // Two meridians, drawn as ovals, which is what makes it read as a globe rather than a target.
        val oval = android.graphics.RectF(cx - r * 0.48f, cy - r, cx + r * 0.48f, cy + r)
        canvas.drawOval(oval, stroke)
    }

    /** A chevron pointing down: hide the keyboard. */
    fun chevronDown(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint) {
        val w = size * 0.42f
        val h = size * 0.22f
        canvas.drawLine(cx - w, cy - h, cx, cy + h, stroke)
        canvas.drawLine(cx + w, cy - h, cx, cy + h, stroke)
    }

    /** Two overlapping sheets: copy. */
    fun copy(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint) {
        val r = size * 0.12f
        val w = size * 0.34f
        val h = size * 0.42f
        val back = android.graphics.RectF(cx - w, cy - h, cx + w * 0.4f, cy + h * 0.55f)
        val front = android.graphics.RectF(cx - w * 0.4f, cy - h * 0.55f, cx + w, cy + h)
        canvas.drawRoundRect(back, r, r, stroke)
        canvas.drawRoundRect(front, r, r, stroke)
    }

    /** A clipboard with a tab: paste. */
    fun paste(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint, fill: Paint) {
        val r = size * 0.12f
        val w = size * 0.36f
        val h = size * 0.46f
        canvas.drawRoundRect(android.graphics.RectF(cx - w, cy - h, cx + w, cy + h), r, r, stroke)
        val tabW = w * 0.5f
        canvas.drawRoundRect(
            android.graphics.RectF(cx - tabW, cy - h - size * 0.08f, cx + tabW, cy - h + size * 0.14f),
            r * 0.8f, r * 0.8f, fill,
        )
    }

    /** A dashed marquee around two lines of text: select all. A filled square reads as a stop button. */
    fun selectAll(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint, fill: Paint) {
        val w = size * 0.46f
        val dashed = Paint(stroke).apply {
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(size * 0.16f, size * 0.12f), 0f)
        }
        canvas.drawRoundRect(
            android.graphics.RectF(cx - w, cy - w, cx + w, cy + w), size * 0.14f, size * 0.14f, dashed,
        )
        val line = w * 0.5f
        val thick = size * 0.09f
        canvas.drawRoundRect(
            android.graphics.RectF(cx - line, cy - thick * 1.8f, cx + line, cy - thick * 0.4f),
            thick, thick, fill,
        )
        canvas.drawRoundRect(
            android.graphics.RectF(cx - line, cy + thick * 0.4f, cx + line * 0.3f, cy + thick * 1.8f),
            thick, thick, fill,
        )
    }
}
