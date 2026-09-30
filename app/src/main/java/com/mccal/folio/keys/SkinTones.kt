package com.mccal.folio.keys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max

/**
 * Which emoji come in skin tones, and which tone each one is typed in.
 *
 * A tone is a second codepoint after the emoji, one of Unicode's five Fitzpatrick modifiers, and only the emoji
 * Unicode lists as Emoji_Modifier_Base take one. Tone 0 is no modifier at all: the yellow every emoji starts as.
 */
object SkinTones {

    /** The five modifiers, lightest first, after the empty one for tone 0. */
    val MODIFIERS: List<String> = listOf("", "🏻", "🏼", "🏽", "🏾", "🏿")

    const val COUNT = 6

    /**
     * The emoji Keyd ships that Unicode's emoji-data.txt (16.0) lists as Emoji_Modifier_Base, as they are written in
     * [Emoji.CATEGORIES].
     *
     * Only one person each. 👪 and 🤼 are modifier bases too, but a tone on them means a tone per person, and a row
     * of six can't offer that; they stay yellow rather than getting a picker that only half works.
     */
    private val BASES: List<String> = (
        "👋 🤚 ✋ 👌 ✌️ 🤞 🤟 🤘 👈 👉 👆 👇 👍 👎 ✊ 👊 👏 🙌 🙏 💪 🦵 👶 🧒 👦 👧 🧑 👨 👩 🧓 🙋 🤦 🤷 💁 🙇 " +
            "💃 🕺 🏂 🏋️ 🤸 ⛹️ 🤾 🏌️ 🏇 🧘 🏄 🏊 🤽 🚣 🧗 🚴 🚵"
        ).split(" ")

    /** Each base by its bare codepoint, to the way the grid writes it: ✌ is shown as ✌️, with its U+FE0F. */
    private val SHOWN: Map<String, String> = BASES.associateBy { key(it) }

    private const val VARIATION = "️"

    /**
     * The emoji with any tone and U+FE0F taken off, so 👍, 👍🏽 and 👍🏿 are one emoji to remember a choice by.
     *
     * U+FE0F only asks for the colorful form, and it goes when a tone is added (✌🏽, not ✌️🏽), so it is no part of
     * which emoji it is.
     */
    fun key(emoji: String): String {
        var plain = emoji.replace(VARIATION, "")
        for (modifier in MODIFIERS) if (modifier.isNotEmpty()) plain = plain.replace(modifier, "")
        return plain
    }

    /** Whether holding [emoji] offers tones: a single-person modifier base, toned already or not. */
    fun supports(emoji: String): Boolean = key(emoji) in SHOWN

    /** [emoji] in [tone], 0 being no tone. Anything that takes no tone comes back as it was. */
    fun withTone(emoji: String, tone: Int): String {
        val key = key(emoji)
        val shown = SHOWN[key] ?: return emoji
        return if (tone in 1 until COUNT) key + MODIFIERS[tone] else shown
    }

    /** Which tone [emoji] is in, 0 for none. */
    fun toneOf(emoji: String): Int =
        (1 until COUNT).firstOrNull { emoji.contains(MODIFIERS[it]) } ?: 0

    /**
     * What each emoji is typed in: the one picked for it by holding it, or else [default], the Settings choice.
     *
     * A picked tone is kept even when it is tone 0, so someone who wants their thumbs yellow and everything else
     * brown can have that.
     */
    data class Choices(val default: Int = 0, val picked: Map<String, Int> = emptyMap()) {

        fun toneFor(emoji: String): Int = picked[key(emoji)] ?: default

        /** [emoji] as a tap should type it. */
        fun apply(emoji: String): String = if (supports(emoji)) withTone(emoji, toneFor(emoji)) else emoji

        fun picking(emoji: String, tone: Int): Choices =
            if (!supports(emoji) || tone !in 0 until COUNT) this else copy(picked = picked + (key(emoji) to tone))
    }

    /** Stored as one string, "👍:3 ✋:0", the same way the recents are. */
    fun encode(picked: Map<String, Int>): String = picked.entries.joinToString(" ") { "${it.key}:${it.value}" }

    fun decode(stored: String?): Map<String, Int> =
        stored?.split(" ")?.mapNotNull { entry ->
            val emoji = entry.substringBefore(':', "")
            val tone = entry.substringAfter(':', "").toIntOrNull()
            if (tone == null || tone !in 0 until COUNT || !supports(emoji)) null else key(emoji) to tone
        }?.toMap().orEmpty()

    /** What TalkBack says for tone [tone] on its own, as the Settings row offers them. */
    fun spoken(context: Context, tone: Int): String = context.getString(TONE_NAMES[tone.coerceIn(0, COUNT - 1)])

    /** [name] in [tone]: "Thumbs up, medium skin tone", and only the name for tone 0. */
    fun spoken(context: Context, name: String, tone: Int): String =
        if (tone == 0) name else context.getString(R.string.emoji_tone_named, name, spoken(context, tone).lowercase())

    private val TONE_NAMES = listOf(
        R.string.emoji_tone_default, R.string.emoji_tone_light, R.string.emoji_tone_medium_light,
        R.string.emoji_tone_medium, R.string.emoji_tone_medium_dark, R.string.emoji_tone_dark,
    )
}

/**
 * The row of six tones over a held emoji, for the emoji grid and the search results alike.
 *
 * Laid out like the accents over a held letter, and it works like the language list: slide onto a tone and let go to
 * type it, or let go anywhere else and the row stays open for a tap. [current] is the tone the emoji is typed in now,
 * lit until a finger is over another.
 */
internal class TonePicker(val emoji: String, val current: Int, val boxes: List<Box>) {

    val items: List<String> = (0 until SkinTones.COUNT).map { SkinTones.withTone(emoji, it) }

    /** The tone under the finger while it slides, or -1. */
    var over: Int = -1

    val outline: Box get() = Box(boxes.first().left, boxes.first().top, boxes.last().right, boxes.last().bottom)

    /** The tone at a point, exactly: what a tap takes. */
    fun at(x: Float, y: Float): Int = boxes.indexOfFirst { it.contains(x, y) }

    /**
     * The tone a sliding finger is on. A little looser than [at] above and below, so a finger dragged up from the
     * emoji doesn't have to land precisely inside the row, but not so loose that letting go where it was held picks
     * something.
     */
    fun near(x: Float, y: Float): Int {
        val row = outline
        val slack = row.height * 0.4f
        if (y < row.top - slack || y > row.bottom + slack) return -1
        return boxes.indexOfFirst { x >= it.left && x < it.right }
    }

    fun draw(canvas: Canvas, theme: Theme, fill: Paint, glyph: Paint, dp: Float) {
        val rect = RectF()
        val row = outline
        val round = 12 * dp
        rect.set(row.left - 3 * dp, row.top - 3 * dp, row.right + 3 * dp, row.bottom + 3 * dp)
        fill.color = theme.preview
        canvas.drawRoundRect(rect, round + 3 * dp, round + 3 * dp, fill)
        val lit = if (over >= 0) over else current
        glyph.textSize = row.height * 0.56f
        val baseline = -(glyph.descent() + glyph.ascent()) / 2
        for ((index, box) in boxes.withIndex()) {
            if (index == lit) {
                rect.set(box.left, box.top, box.right, box.bottom)
                fill.color = theme.pressTint
                canvas.drawRoundRect(rect, round, round, fill)
            }
            canvas.drawText(items[index], (box.left + box.right) / 2, (box.top + box.bottom) / 2 + baseline, glyph)
        }
    }

    companion object {
        /** A tone is never narrower or shorter than this: a fingertip, and the size TalkBack asks of a target. */
        const val TONE_DP = 48f
        const val LIFT_DP = 4f

        /**
         * Six tones in a row over [anchor], kept between [left] and [right].
         *
         * Above the emoji where there is room, as the accents are. Where there isn't - the top row of the grid - it
         * is pushed down to [top] if that still leaves the emoji mostly clear, and otherwise goes below it.
         */
        fun place(emoji: String, current: Int, anchor: Box, left: Float, right: Float, top: Float, dp: Float): TonePicker {
            val size = max(TONE_DP * dp, anchor.width)
            val width = size * SkinTones.COUNT
            val centre = (anchor.left + anchor.right) / 2
            val start = (centre - width / 2).coerceIn(left, max(left, right - width))
            var rowTop = anchor.top - LIFT_DP * dp - size
            if (rowTop < top) {
                rowTop = top
                if (rowTop + size > anchor.top + anchor.height / 2) rowTop = anchor.bottom + LIFT_DP * dp
            }
            val boxes = (0 until SkinTones.COUNT).map { Box(start + it * size, rowTop, start + (it + 1) * size, rowTop + size) }
            return TonePicker(emoji, current, boxes)
        }
    }
}
