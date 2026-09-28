package com.mccal.folio.keys

import android.content.Context
import java.io.InputStream

/** What the word before is, at the start of a sentence: a full stop, since that is what usually put it there. */
const val SENTENCE_START = "."

/**
 * The words that most often come next, for the commonest words in a language.
 *
 * Two uses. After a space the strip has nothing to finish, so it offers what usually follows the word just typed -
 * "want" is followed by "to", "you", "me" - which is most of what a keyboard's prediction is. And while a word is
 * being typed, a candidate that often follows the word before it is the likelier one: after "I", "wnat" is "want".
 *
 * Built by `scripts/bigrams.py` from Tatoeba's sentences (CC BY 2.0 FR), counted in advance and shipped as a list:
 * nothing about what is typed is counted or kept here. One line per word, `word<TAB>next next next`, commonest
 * first, [SENTENCE_START] standing for the start of a sentence.
 */
class NextWords private constructor(private val after: Map<String, String>) {

    val size: Int get() = after.size

    /** What usually follows [previous], commonest first. Empty for a word it knows nothing about. */
    fun after(previous: String): List<String> = after[previous]?.split(' ').orEmpty()

    /**
     * Where [word] comes among the words that follow [previous]: 0 for the commonest, null if it is not there.
     *
     * Asked for every candidate on every keystroke, so it reads the line where it lies rather than splitting it.
     * The followers are kept as the one line each they arrive as - twenty thousand words as two thousand strings,
     * not twenty thousand, for a keyboard that stays in memory as long as the phone is on.
     */
    fun place(previous: String, word: String): Int? {
        val line = after[previous] ?: return null
        var start = 0
        var index = 0
        while (start < line.length) {
            val end = line.indexOf(' ', start).let { if (it < 0) line.length else it }
            if (end - start == word.length && line.regionMatches(start, word, 0, word.length, ignoreCase = true)) {
                return index
            }
            start = end + 1
            index++
        }
        return null
    }

    companion object {
        val EMPTY = NextWords(emptyMap())

        fun read(stream: InputStream): NextWords {
            val after = HashMap<String, String>()
            stream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEachLine
                val next = line.substring(tab + 1).trim()
                if (next.isNotEmpty()) after[line.substring(0, tab)] = next
            }
            return NextWords(after)
        }

        fun load(context: Context, language: Language): NextWords =
            context.assets.open("next-${language.tag}.txt").use { read(it) }
    }
}
