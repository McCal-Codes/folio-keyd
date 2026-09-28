package com.mccal.folio.keys

/**
 * Bold, italic, script and monospace for text that has no formatting of its own: a chat, a bio, a post.
 *
 * None of it is formatting. Each letter is swapped for its twin in Unicode's Mathematical Alphanumeric Symbols, a
 * block made for equations, which is why it works anywhere text goes and also why a screen reader may spell it out
 * and a search won't find it. Only the Latin letters and the digits have twins; everything else is kept as it was.
 *
 * Text that is already styled is put back to plain first, so Bold after Italic is bold rather than both.
 */
enum class TextStyle(
    val label: String,
    private val upper: Int,
    private val lower: Int,
    /** Where 0 starts, or -1 for a style with no digits of its own. */
    private val digits: Int,
    /** The letters that were in Unicode before this block and kept their older places, which the block leaves empty. */
    private val older: Map<Char, Int> = emptyMap(),
) {
    BOLD("Bold", 0x1D400, 0x1D41A, 0x1D7CE),
    ITALIC("Italic", 0x1D434, 0x1D44E, -1, mapOf('h' to 0x210E)),
    SCRIPT(
        "Script", 0x1D49C, 0x1D4B6, -1,
        mapOf(
            'B' to 0x212C, 'E' to 0x2130, 'F' to 0x2131, 'H' to 0x210B, 'I' to 0x2110, 'L' to 0x2112, 'M' to 0x2133,
            'R' to 0x211B, 'e' to 0x212F, 'g' to 0x210A, 'o' to 0x2134,
        ),
    ),
    MONOSPACE("Monospace", 0x1D670, 0x1D68A, 0x1D7F6);

    /** The code point for one plain character, or the character itself when this style has no twin for it. */
    fun of(c: Char): Int {
        older[c]?.let { return it }
        return when (c) {
            in 'A'..'Z' -> upper + (c - 'A')
            in 'a'..'z' -> lower + (c - 'a')
            in '0'..'9' -> if (digits < 0) c.code else digits + (c - '0')
            else -> c.code
        }
    }

    /** [text] in this style. */
    fun on(text: CharSequence): String {
        val plain = TextStyle.plain(text)
        val out = StringBuilder(plain.length * 2)
        for (c in plain) out.appendCodePoint(of(c))
        return out.toString()
    }

    companion object {
        /** Every styled code point back to its plain letter, from every style, so restyling starts from plain. */
        private val PLAIN: Map<Int, Char> by lazy {
            buildMap {
                for (style in TextStyle.entries) {
                    for (c in ('A'..'Z') + ('a'..'z') + ('0'..'9')) {
                        val code = style.of(c)
                        if (code != c.code) put(code, c)
                    }
                }
            }
        }

        fun plain(text: CharSequence): String {
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val code = Character.codePointAt(text, i)
                val back = PLAIN[code]
                if (back != null) out.append(back) else out.appendCodePoint(code)
                i += Character.charCount(code)
            }
            return out.toString()
        }
    }
}

/**
 * How much is selected, for the toolbar: characters as a person counts them, and words as runs between spaces.
 *
 * Characters are code points, so an emoji is one and not the two halves Java stores it as. Past [CAP] the text is
 * not read at all - asking an app for a whole document to count it would be a slow call for a number nobody needs
 * exact - and the toolbar says "2,000+" instead.
 */
data class Selected(val characters: Int, val words: Int, val capped: Boolean = false) {
    companion object {
        const val CAP = 2000

        fun of(text: CharSequence): Selected {
            var words = 0
            var inWord = false
            var i = 0
            var characters = 0
            while (i < text.length) {
                val code = Character.codePointAt(text, i)
                val space = Character.isWhitespace(code) || Character.isSpaceChar(code)
                if (!space && !inWord) words++
                inWord = !space
                characters++
                i += Character.charCount(code)
            }
            return Selected(characters, words)
        }

        /** Whether a selection this long, in UTF-16 units, is past the cap before anything is read. */
        fun tooLong(length: Int) = length > CAP
    }
}
