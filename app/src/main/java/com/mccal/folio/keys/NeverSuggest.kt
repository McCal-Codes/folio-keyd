package com.mccal.folio.keys

/**
 * Words someone held in the strip and asked not to be suggested.
 *
 * A learned word is simply forgotten instead, in [Learned]. This is for the words the dictionary has, which cannot be
 * taken out of it: they are left out of the strip, and never become a correction. Typing one is untouched. It is
 * still a word the dictionary knows, so it is not underlined and not changed, only never offered.
 *
 * Kept lowercase, since "May" and "may" are one suggestion in the strip. Capped, the oldest going first, so it stays
 * a quick lookup for every word the strip scores. Local, like everything else, and emptied by Forget in Privacy.
 */
class NeverSuggest(private val words: LinkedHashSet<String> = LinkedHashSet()) {

    val size: Int get() = words.size

    /** Every word on the list, the oldest first. */
    fun all(): List<String> = words.toList()

    operator fun contains(word: String): Boolean = words.isNotEmpty() && word.lowercase() in words

    /** Puts [word] on the list. True when the list changed, so the caller knows whether there is anything to save. */
    fun add(word: String): Boolean {
        val lower = word.trim().lowercase()
        if (lower.isEmpty() || '\n' in lower || lower in words) return false
        if (words.size >= LIMIT) words.remove(words.first())
        words += lower
        return true
    }

    fun encode(): String = words.joinToString("\n")

    companion object {
        /** Where it is stored, beside the learned words. */
        const val KEY = "neverSuggest"

        /** Far more words than anyone will hold down one at a time. */
        const val LIMIT = 500

        fun decode(stored: String?): NeverSuggest {
            val list = NeverSuggest()
            stored.orEmpty().split("\n").forEach { list.add(it) }
            return list
        }
    }
}
