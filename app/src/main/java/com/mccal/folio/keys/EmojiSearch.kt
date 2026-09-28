package com.mccal.folio.keys

import android.content.Context
import java.io.FileNotFoundException
import java.text.Normalizer
import java.util.Locale

/**
 * Finds an emoji from a word, in the keyboard's language.
 *
 * The names and keywords are Unicode CLDR's, built into `assets/emoji/<tag>.tsv` by `scripts/emoji-keywords.py`: one
 * line per emoji Keyd ships, in the order [Emoji] keeps them. CLDR is what iOS and Android both search with, so
 * "heart" finds what someone expects it to, in six languages, without anything leaving the phone.
 *
 * Seven hundred emoji with a dozen words each is small enough to scan whole on every keystroke. The words are folded
 * once, when the file is read, so a search is string comparisons and nothing else.
 */
class EmojiSearch private constructor(private val entries: List<Entry>) {

    private class Entry(
        val glyph: String,
        val name: String,
        /** The name folded, for a query that is the whole name. */
        val whole: String,
        val nameWords: List<String>,
        val keywordWords: List<String>,
        /** Where it sits in [Emoji], so equal matches keep the order the grid shows. */
        val order: Int,
    )

    val size: Int get() = entries.size

    private val byGlyph: Map<String, Entry> = entries.associateBy { it.glyph }

    /** What TalkBack should say for an emoji: "red heart", not the glyph. Null for one this list does not know. */
    fun nameOf(glyph: String): String? = byGlyph[glyph]?.name

    /**
     * The emoji that match [query], best first.
     *
     * Every word typed has to match the start of some word in the emoji's name or keywords, so "red he" narrows as it
     * is typed rather than widening. Better matches come first: the whole name, then a whole word of the name, then a
     * whole keyword, then the start of a name word, then the start of a keyword. That is what puts the red heart
     * above a house for "heart": both have the keyword, but only one is called a heart.
     *
     * Among equal matches, the ones in [recents] come first, and then the order the grid uses.
     */
    fun search(query: String, limit: Int, recents: List<String> = emptyList()): List<String> {
        val words = words(fold(query))
        if (words.isEmpty() || limit <= 0) return emptyList()
        val phrase = words.joinToString(" ")
        val scored = ArrayList<Pair<Entry, Int>>()
        for (entry in entries) {
            var total = 0
            for (word in words) {
                val tier = tier(entry, word)
                if (tier < 0) {
                    total = -1
                    break
                }
                total += tier
            }
            if (total < 0) continue
            // The whole name typed out beats any number of single-word matches.
            if (entry.whole == phrase) total = -1
            scored += entry to total
        }
        val recent = recents.withIndex().associate { it.value to it.index }
        return scored
            .sortedWith(
                compareBy<Pair<Entry, Int>> { it.second }
                    .thenBy { recent[it.first.glyph] ?: Int.MAX_VALUE }
                    .thenBy { it.first.order },
            )
            .take(limit)
            .map { it.first.glyph }
    }

    /** How well one typed word matches an emoji, lower is better, or -1 for not at all. */
    private fun tier(entry: Entry, word: String): Int = when {
        word in entry.nameWords -> 0
        word in entry.keywordWords -> 1
        entry.nameWords.any { starts(it, word) } -> 2
        entry.keywordWords.any { starts(it, word) } -> 3
        else -> -1
    }

    /**
     * The start of a word, or the start of one part of a hyphenated word.
     *
     * "heart-eyes" is kept whole so that "heart" is not an exact match for the face with heart eyes, which would put
     * it level with the red heart. It still counts as starting with "eyes", because that is a word in it.
     */
    private fun starts(stored: String, word: String): Boolean {
        if (stored.startsWith(word)) return true
        if ('-' !in stored) return false
        return stored.split('-').any { it.startsWith(word) }
    }

    companion object {

        /** Where the files are, one per [Language] and named by its tag. */
        fun path(language: Language) = "emoji/${language.tag}.tsv"

        /**
         * Reads the file for [language], or English's if there isn't one.
         *
         * Always called off the main thread: it is small, but it is still a file and some parsing, and the moment
         * someone taps the search key is not the moment to make the keyboard wait for it.
         */
        fun load(context: Context, language: Language): EmojiSearch {
            val assets = context.assets
            val stream = try {
                assets.open(path(language))
            } catch (missing: FileNotFoundException) {
                assets.open(path(Language.ENGLISH))
            }
            return stream.bufferedReader(Charsets.UTF_8).useLines { parse(it) }
        }

        /** One `glyph<TAB>name<TAB>keyword,keyword` line per emoji. A line without a name is skipped. */
        fun parse(lines: Sequence<String>): EmojiSearch {
            val entries = ArrayList<Entry>()
            for (line in lines) {
                val parts = line.split('\t')
                if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) continue
                val name = parts[1]
                val keywords = parts.getOrNull(2).orEmpty().split(',').filter { it.isNotBlank() }
                val folded = fold(name)
                entries += Entry(
                    glyph = parts[0],
                    name = name,
                    whole = words(folded).joinToString(" "),
                    nameWords = words(folded),
                    keywordWords = keywords.flatMap { words(fold(it)) }.distinct(),
                    order = entries.size,
                )
            }
            return EmojiSearch(entries)
        }

        private val MARKS = Regex("\\p{Mn}+")

        /** Anything that is not a letter, a digit or a hyphen separates words. */
        private val SEPARATORS = Regex("[^\\p{L}\\p{N}-]+")

        /**
         * Lower case, with the accents taken off.
         *
         * Someone searching in French types "coeur" as often as "cœur", and on a keyboard where é is a long press, far
         * more often. Taking the marks off both sides makes those the same word. The ligatures are spelled out by
         * hand because Unicode does not count œ as o and e with something added.
         */
        fun fold(text: String): String {
            val lower = text.lowercase(Locale.ROOT)
                .replace("œ", "oe")
                .replace("æ", "ae")
                .replace("ß", "ss")
            return MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
        }

        private fun words(folded: String): List<String> =
            folded.split(SEPARATORS).map { it.trim('-') }.filter { it.isNotEmpty() }
    }
}
