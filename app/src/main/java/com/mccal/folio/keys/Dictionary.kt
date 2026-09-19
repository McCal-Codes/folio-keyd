package com.mccal.folio.keys

import android.content.Context
import java.io.InputStream

/**
 * The word list, held in about a megabyte instead of fifty-seven thousand objects.
 *
 * The obvious way to load a dictionary is a list of strings, which for this many words is several megabytes of heap
 * and fifty-seven thousand things for the garbage collector to walk. A keyboard is resident for as long as the phone
 * is on, so it is kept the other way: one block of bytes with the words end to end, an index of where each one
 * starts, and a byte of rank each. Lookups binary-search the index and never build a string until there is one to
 * show someone.
 *
 * The list is SCOWL, cut to US English at its "size 40" band. Its copyright notice ships beside it in the assets and
 * has to stay there - that is the whole of what its licence asks in return.
 */
class Dictionary private constructor(
    private val bytes: ByteArray,
    private val starts: IntArray,
    private val ranks: ByteArray,
) : Suggestions.Words {

    override val size: Int get() = starts.size - 1

    override fun word(index: Int): String =
        String(bytes, starts[index], starts[index + 1] - starts[index], Charsets.US_ASCII)

    override fun rank(index: Int): Int = ranks[index].toInt()

    /** Case-insensitive, because the list holds `Monday` and someone types `monday`. */
    private fun compare(index: Int, other: String): Int {
        val from = starts[index]
        val length = starts[index + 1] - from
        val shared = minOf(length, other.length)
        for (i in 0 until shared) {
            val mine = lower(bytes[from + i])
            val theirs = lower(other[i].code.toByte())
            if (mine != theirs) return mine - theirs
        }
        return length - other.length
    }

    /** Like [compare], but a word that merely starts with [prefix] counts as equal to it. */
    private fun comparePrefix(index: Int, prefix: String): Int {
        val from = starts[index]
        val length = starts[index + 1] - from
        for (i in prefix.indices) {
            if (i >= length) return -1
            val mine = lower(bytes[from + i])
            val theirs = lower(prefix[i].code.toByte())
            if (mine != theirs) return mine - theirs
        }
        return 0
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
            for (index in range) byLength.getOrPut(starts[index + 1] - starts[index]) { ArrayList() }.add(index)
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

    fun contains(word: String): Boolean {
        val index = lowerBound { compare(it, word) >= 0 }
        return index < size && compare(index, word) == 0
    }

    companion object {
        const val ASSET = "words-en-us.txt"

        private val EMPTY = IntArray(0)

        private fun lower(b: Byte): Int {
            val c = b.toInt() and 0xFF
            return if (c in 'A'.code..'Z'.code) c + 32 else c
        }

        /**
         * Reads `word:rank`, one per line, already sorted.
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
                if (colon > begin) {
                    starts[count] = out
                    for (i in begin until colon) words[out++] = raw[i]
                    ranks[count] = (raw[colon + 1] - '0'.code.toByte()).toByte()
                    count++
                }
                index++
            }
            starts[count] = out
            return Dictionary(words.copyOf(out), starts.copyOf(count + 1), ranks.copyOf(count))
        }

        fun load(context: Context): Dictionary = read(context.assets.open(ASSET))
    }
}
