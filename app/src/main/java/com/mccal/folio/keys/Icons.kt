package com.mccal.folio.keys

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

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

    // The toolbar is redrawn on every frame, so nothing here may allocate: a handful of rectangles per frame is a
    // thousand objects a second, and the garbage collector takes them back in pauses long enough to see.
    private val rect = RectF()
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dashedFor = -1f
    private var dashPattern: DashPathEffect? = null

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
        rect.set(cx - r * 0.48f, cy - r, cx + r * 0.48f, cy + r)
        canvas.drawOval(rect, stroke)
    }

    /** A face, for the emoji: drawn rather than set in the emoji font, so it matches the other icons. */
    fun smiley(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint, fill: Paint) {
        val r = size * 0.46f
        canvas.drawCircle(cx, cy, r, stroke)
        val eye = r * 0.12f
        canvas.drawCircle(cx - r * 0.34f, cy - r * 0.26f, eye, fill)
        canvas.drawCircle(cx + r * 0.34f, cy - r * 0.26f, eye, fill)
        rect.set(cx - r * 0.5f, cy - r * 0.3f, cx + r * 0.5f, cy + r * 0.55f)
        canvas.drawArc(rect, 20f, 140f, false, stroke)
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
        rect.set(cx - w, cy - h, cx + w * 0.4f, cy + h * 0.55f)
        canvas.drawRoundRect(rect, r, r, stroke)
        rect.set(cx - w * 0.4f, cy - h * 0.55f, cx + w, cy + h)
        canvas.drawRoundRect(rect, r, r, stroke)
    }

    /** A clipboard with a tab: paste. */
    fun paste(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint, fill: Paint) {
        val r = size * 0.12f
        val w = size * 0.36f
        val h = size * 0.46f
        rect.set(cx - w, cy - h, cx + w, cy + h)
        canvas.drawRoundRect(rect, r, r, stroke)
        val tabW = w * 0.5f
        rect.set(cx - tabW, cy - h - size * 0.08f, cx + tabW, cy - h + size * 0.14f)
        canvas.drawRoundRect(rect, r * 0.8f, r * 0.8f, fill)
    }

    /** A dashed marquee around two lines of text: select all. A filled square reads as a stop button. */
    /**
     * A clipboard with lines on it: the history, as against [paste], which is the clipboard with a tab and means
     * "put the last one back". The two sit next to each other on the toolbar, so they have to read differently at
     * twenty density-independent pixels.
     */
    fun clipboard(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint, fill: Paint) {
        val half = size / 2
        rect.set(cx - half * 0.72f, cy - half, cx + half * 0.72f, cy + half)
        canvas.drawRoundRect(rect, size * 0.12f, size * 0.12f, stroke)
        // The clip at the top, filled so it reads as a clip rather than another line.
        rect.set(cx - half * 0.3f, cy - half - size * 0.08f, cx + half * 0.3f, cy - half + size * 0.14f)
        canvas.drawRoundRect(rect, size * 0.06f, size * 0.06f, fill)
        // Two lines of something written down.
        for (line in 0..1) {
            val y = cy - size * 0.06f + line * size * 0.26f
            canvas.drawLine(cx - half * 0.4f, y, cx + half * 0.4f, y, stroke)
        }
    }

    /** A microphone: the capsule, the cradle under it, and the stand. */
    fun mic(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint) {
        val half = size / 2
        rect.set(cx - half * 0.32f, cy - half, cx + half * 0.32f, cy + half * 0.2f)
        canvas.drawRoundRect(rect, half * 0.32f, half * 0.32f, stroke)
        rect.set(cx - half * 0.62f, cy - half * 0.6f, cx + half * 0.62f, cy + half * 0.5f)
        canvas.drawArc(rect, 0f, 180f, false, stroke)
        canvas.drawLine(cx, cy + half * 0.5f, cx, cy + half, stroke)
    }

    /** Four arrows from the middle: the key that opens the cursor pad. */
    fun move(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint) {
        val half = size / 2
        val head = half * 0.3f
        canvas.drawLine(cx - half, cy, cx + half, cy, stroke)
        canvas.drawLine(cx, cy - half, cx, cy + half, stroke)
        for ((dx, dy) in DIRECTIONS) {
            val tipX = cx + dx * half
            val tipY = cy + dy * half
            // Each head is the tip plus two points stepped back along the arrow and out to either side.
            canvas.drawLine(tipX, tipY, tipX - dx * head + dy * head, tipY - dy * head + dx * head, stroke)
            canvas.drawLine(tipX, tipY, tipX - dx * head - dy * head, tipY - dy * head - dx * head, stroke)
        }
    }

    /**
     * A hook curving back on itself, its head down on the left: undo. [redo] is the same hook the other way round, so
     * the pair read as a pair.
     */
    fun undo(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint) = hook(canvas, cx, cy, size, stroke, 1f)

    fun redo(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint) = hook(canvas, cx, cy, size, stroke, -1f)

    private fun hook(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint, side: Float) {
        val r = size * 0.34f
        val centreY = cy + size * 0.1f
        canvas.save()
        canvas.scale(side, 1f, cx, cy)
        // Over the top from the left end, round to the lower right.
        rect.set(cx - r, centreY - r, cx + r, centreY + r)
        canvas.drawArc(rect, 180f, 225f, false, stroke)
        val tipX = cx - r
        val head = size * 0.18f
        canvas.drawLine(tipX, centreY, tipX - head, centreY - head, stroke)
        canvas.drawLine(tipX, centreY, tipX + head, centreY - head, stroke)
        canvas.restore()
    }

    /** Scissors: two rings, and the blades crossing above them. */
    fun cut(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint) {
        val ring = size * 0.13f
        val ringY = cy + size * 0.3f
        val apart = size * 0.2f
        canvas.drawCircle(cx - apart, ringY, ring, stroke)
        canvas.drawCircle(cx + apart, ringY, ring, stroke)
        val top = cy - size * 0.46f
        canvas.drawLine(cx - apart + ring * 0.5f, ringY - ring, cx + apart * 0.9f, top, stroke)
        canvas.drawLine(cx + apart - ring * 0.5f, ringY - ring, cx - apart * 0.9f, top, stroke)
    }

    /**
     * The icon for one of the toolbar's buttons, so the keyboard and the settings page that arranges it draw the same
     * thing. False for a kind that has no icon.
     */
    fun tool(canvas: Canvas, kind: KeyKind, cx: Float, cy: Float, size: Float, stroke: Paint, fill: Paint): Boolean {
        when (kind) {
            KeyKind.HIDE -> chevronDown(canvas, cx, cy, size * 1.2f, stroke)
            KeyKind.EMOJI -> smiley(canvas, cx, cy, size, stroke, fill)
            KeyKind.SELECT_ALL -> selectAll(canvas, cx, cy, size, stroke, fill)
            KeyKind.COPY -> copy(canvas, cx, cy, size, stroke)
            KeyKind.PASTE -> paste(canvas, cx, cy, size, stroke, fill)
            KeyKind.CLIPBOARD -> clipboard(canvas, cx, cy, size, stroke, fill)
            KeyKind.VOICE -> mic(canvas, cx, cy, size, stroke)
            KeyKind.CURSOR_PAD -> move(canvas, cx, cy, size, stroke)
            KeyKind.UNDO -> undo(canvas, cx, cy, size, stroke)
            KeyKind.REDO -> redo(canvas, cx, cy, size, stroke)
            KeyKind.CUT -> cut(canvas, cx, cy, size, stroke)
            else -> return false
        }
        return true
    }

    private val DIRECTIONS = arrayOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)

    /** A pushpin: the head, and the point it goes in by. Filled when the clip is pinned, outlined when it is not. */
    fun pin(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint, fill: Paint, pinned: Boolean) {
        val half = size / 2
        val head = if (pinned) fill else stroke
        canvas.drawCircle(cx, cy - half * 0.3f, half * 0.52f, head)
        canvas.drawLine(cx, cy + half * 0.2f, cx, cy + half, stroke)
    }

    /** A magnifying glass: the lens up and to the left, the handle down to the right, the way every search key is. */
    fun magnifier(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint) {
        val half = size / 2
        val r = half * 0.56f
        val lensX = cx - half * 0.16f
        val lensY = cy - half * 0.16f
        canvas.drawCircle(lensX, lensY, r, stroke)
        // The handle starts on the rim, at 45 degrees, so it looks attached rather than floating beside the lens.
        val rim = r * 0.7071f
        canvas.drawLine(lensX + rim, lensY + rim, cx + half * 0.78f, cy + half * 0.78f, stroke)
    }

    /** An arrow pointing left: back to where you came from. */
    fun back(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint) {
        val half = size / 2
        val tip = cx - half * 0.8f
        val head = half * 0.62f
        canvas.drawLine(tip, cy, cx + half * 0.8f, cy, stroke)
        canvas.drawLine(tip, cy, tip + head, cy - head, stroke)
        canvas.drawLine(tip, cy, tip + head, cy + head, stroke)
    }

    /** A cross, for forgetting one clip. */
    fun close(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint) {
        val half = size / 2 * 0.62f
        canvas.drawLine(cx - half, cy - half, cx + half, cy + half, stroke)
        canvas.drawLine(cx - half, cy + half, cx + half, cy - half, stroke)
    }

    fun selectAll(canvas: Canvas, cx: Float, cy: Float, size: Float, stroke: Paint, fill: Paint) {
        val w = size * 0.46f
        // The dashes only have to be remade when the icon changes size, which is when the keyboard is laid out.
        dashed.set(stroke)
        if (size != dashedFor) {
            dashedFor = size
            dashPattern = DashPathEffect(floatArrayOf(size * 0.16f, size * 0.12f), 0f)
        }
        dashed.pathEffect = dashPattern
        rect.set(cx - w, cy - w, cx + w, cy + w)
        canvas.drawRoundRect(rect, size * 0.14f, size * 0.14f, dashed)
        val line = w * 0.5f
        val thick = size * 0.09f
        rect.set(cx - line, cy - thick * 1.8f, cx + line, cy - thick * 0.4f)
        canvas.drawRoundRect(rect, thick, thick, fill)
        rect.set(cx - line, cy + thick * 0.4f, cx + line * 0.3f, cy + thick * 1.8f)
        canvas.drawRoundRect(rect, thick, thick, fill)
    }
}
