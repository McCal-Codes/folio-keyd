package com.mccal.folio.keys

import android.content.Context
import java.io.InputStream

/**
 * The word list, held in about a megabyte instead of fifty-seven thousand objects.
 *
 * Each word carries a score from 0 (the commonest word in the language) to 99 (one no frequency list has seen).
 *
 * The obvious way to load a dictionary is a list of strings, which for this many words is several megabytes of heap
 * and fifty-seven thousand things for the garbage collector to walk. A keyboard is resident for as long as the phone
 * is on, so it is kept the other way: one block of bytes with the words end to end, an index of where each one
 * starts, and a byte of rank each. Lookups binary-search the index and never build a string until there is one to
 * show someone.
 *
 * The words are SCOWL, cut to US English at its "size 40" band. The scores are the OpenSubtitles frequency list,
 * log-scaled, because SCOWL's own bands are tiers rather than frequencies - its top tier holds four thousand words,
 * so inside it "the" ties with "tea", and a keyboard that cannot tell those apart suggests the wrong one. Both
 * licences are permissive and both notices ship beside the list in the assets.
 */
class Dictionary private constructor(
    private val bytes: ByteArray,
    private val starts: IntArray,
    private val ranks: ByteArray,
) : Suggestions.Words {

    override val size: Int get() = starts.size - 1

    /**
     * UTF-8, not ASCII.
     *
     * The moment a second language exists, half the words have a letter in them that does not fit in a byte -
     * "café", "ñ", "ß" - and reading them as ASCII turns each of those into a pair of question marks.
     */
    override fun word(index: Int): String =
        String(bytes, starts[index], starts[index + 1] - starts[index], Charsets.UTF_8)

    /** 0 is the commonest word in the language; 99 is one the frequency list has never seen. */
    override fun rank(index: Int): Int = ranks[index].toInt()

    /**
     * Compared as text, not as bytes.
     *
     * Byte comparison was fine while every word was ASCII and one letter was one byte. It is wrong the moment a
     * letter takes two: a prefix of "cafés" typed as characters can never line up with a word stored as bytes.
     * A binary search decodes about sixteen words per lookup, which costs nothing worth measuring.
     */
    private fun compare(index: Int, other: String): Int = word(index).compareTo(other, ignoreCase = true)

    /** Like [compare], but a word that merely starts with [prefix] counts as equal to it. */
    private fun comparePrefix(index: Int, prefix: String): Int {
        val word = word(index)
        if (word.length < prefix.length) return word.compareTo(prefix, ignoreCase = true).let { if (it == 0) -1 else it }
        return word.substring(0, prefix.length).compareTo(prefix, ignoreCase = true)
    }

    override fun startingWith(prefix: String): IntRange {
        if (prefix.isEmpty()) return IntRange.EMPTY
        val first = lowerBound { comparePrefix(it, prefix) >= 0 }
        if (first >= size || comparePrefix(first, prefix) != 0) return IntRange.EMPTY
        val last = lowerBound { comparePrefix(it, prefix) > 0 }
        return first until last
    }

    /**
     * Every word of a given length beginning with a given letter.
     *
     * The words are sorted alphabetically, not by length, so this narrows to the letter and then walks it. A letter
     * is a few thousand words at most, which is the difference between scanning the dictionary and scanning a page
     * of it on every keystroke.
     */
    override fun byShape(first: Char, length: Int): IntArray {
        val letter = first.lowercaseChar()
        // Cached per letter: the caller asks for three lengths in a row, for several letters, on every keystroke.
        val lengths = shapes.getOrPut(letter) {
            val range = startingWith(letter.toString())
            val byLength = HashMap<Int, MutableList<Int>>()
            // Length in letters, not in bytes: "café" is four letters and five bytes, and the caller is counting
            // what they typed.
            for (index in range) byLength.getOrPut(word(index).length) { ArrayList() }.add(index)
            byLength.mapValues { (_, list) -> list.toIntArray() }
        }
        return lengths[length] ?: EMPTY
    }

    private val shapes = HashMap<Char, Map<Int, IntArray>>()

    /** The first index where [test] is true, or [size] when it never is. */
    private inline fun lowerBound(test: (Int) -> Boolean): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (test(mid)) high = mid else low = mid + 1
        }
        return low
    }

    /**
     * Whether a word is within one edit of a common word.
     *
     * Used to decide that something is a slip rather than a word: "teh" is one swap from "the", and learning it
     * would put it in the strip next to "the" for good. Only common words count, because being one edit from
     * something obscure is no evidence of anything.
     */
    fun nearCommonWord(word: String, proximity: Suggestions.Proximity?): Boolean {
        val lower = word.lowercase()
        val first = lower.first()
        for (candidate in listOf(first) + proximity?.neighbours(first).orEmpty()) {
            for (length in word.length - 1..word.length + 1) {
                if (length < 1) continue
                for (index in byShape(candidate, length)) {
                    if (rank(index) > COMMON) continue
                    if (Suggestions.distance(lower, word(index).lowercase(), 1, proximity) <= 1) return true
                }
            }
        }
        return false
    }

    override fun contains(word: String): Boolean {
        val index = lowerBound { compare(it, word) >= 0 }
        return index < size && compare(index, word) == 0
    }

    companion object {

        private val EMPTY = IntArray(0)

        /** Common enough that a word one edit away from it is probably a slip. */
        const val COMMON = 35

        /**
         * Reads `word:NN`, one per line, already sorted, where NN is a two-digit commonness score.
         *
         * Parsed by hand rather than with `split`: this runs once at startup and the tidy version makes a hundred
         * and fourteen thousand short-lived strings on the way to making none.
         */
        fun read(stream: InputStream): Dictionary {
            val raw = stream.readBytes()
            val words = ByteArray(raw.size)
            val starts = IntArray(raw.size / 4 + 2)
            val ranks = ByteArray(raw.size / 4 + 2)
            var count = 0
            var out = 0
            var index = 0
            while (index < raw.size) {
                val begin = index
                var colon = -1
                while (index < raw.size && raw[index] != '\n'.code.toByte()) {
                    if (raw[index] == ':'.code.toByte()) colon = index
                    index++
                }
                if (colon > begin && colon + 2 < raw.size) {
                    starts[count] = out
                    for (i in begin until colon) words[out++] = raw[i]
                    val tens = raw[colon + 1] - '0'.code.toByte()
                    val units = raw[colon + 2] - '0'.code.toByte()
                    ranks[count] = (tens * 10 + units).toByte()
                    count++
                }
                index++
            }
            starts[count] = out
            return Dictionary(words.copyOf(out), starts.copyOf(count + 1), ranks.copyOf(count))
        }

        fun load(context: Context, language: Language = Language.ENGLISH): Dictionary =
            read(context.assets.open(language.dictionary))
    }
}
