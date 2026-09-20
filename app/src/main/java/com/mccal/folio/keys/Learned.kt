package com.mccal.folio.keys

/**
 * The words you use that no dictionary has.
 *
 * Names, handles, place names, jargon, the way you actually spell things. Without this the strip argues with your
 * own vocabulary forever: it has never heard of them, so it keeps offering something else instead.
 *
 * Three rules keep it from poisoning itself, because a learning dictionary that learns the wrong things is worse
 * than one that learns nothing:
 *
 * 1. **Only words the main dictionary does not have.** There is nothing to learn about "the".
 * 2. **Never a near miss of a common word.** Type "teh" once and a naive keyboard learns it, and from then on your
 *    own typo competes with "the" in the strip forever. Anything within one edit of a common word is assumed to be
 *    a slip, not a word — the cost of being wrong that way is a word you have to type out a few more times, and the
 *    cost of being wrong the other way lasts as long as the install does.
 * 3. **Never from a password field, and never when the app has asked not to be learned from.** That is checked
 *    before anything reaches this class, in [TextActions].
 *
 * It is capped, and the least used go first. Everything about it is local and it can be emptied in one tap.
 */
class Learned(private val words: MutableMap<String, Int> = LinkedHashMap()) {

    val size: Int get() = words.size

    /** How many times this word has been typed, or zero. */
    fun count(word: String): Int = words[word] ?: 0

    /** Every learned word, most used first. */
    fun all(): List<String> = words.entries.sortedByDescending { it.value }.map { it.key }

    /**
     * Notes that a word was typed, and says whether it was worth noting.
     *
     * Returns true when the store changed, so the caller knows whether there is anything new to save.
     */
    fun learn(word: String): Boolean {
        val existing = words[word]
        if (existing != null) {
            words[word] = minOf(existing + 1, MAX_COUNT)
            return true
        }
        if (words.size >= LIMIT) forgetLeastUsed()
        words[word] = 1
        return true
    }

    /** Emptied, for the tap that says "forget what you have learned about me". */
    fun clear() = words.clear()

    /**
     * How common to treat a learned word as.
     *
     * A word typed once is a maybe; a word typed ten times is part of how someone writes, and should sit alongside
     * the ordinary words of the language rather than below all of them.
     */
    fun score(word: String): Int {
        val count = words[word] ?: return UNKNOWN
        return (FIRST_SIGHTING - (count - 1) * PER_SIGHTING).coerceAtLeast(SETTLED)
    }

    private fun forgetLeastUsed() {
        val victim = words.entries.minByOrNull { it.value }?.key ?: return
        words.remove(victim)
    }

    fun encode(): String = words.entries.joinToString("\n") { "${it.key}:${it.value}" }

    companion object {
        /** Enough for how anyone writes, small enough to read and scan on every keystroke. */
        const val LIMIT = 1200
        const val MAX_COUNT = 60

        /** Scores sit on the same scale the dictionary uses: 0 is the commonest word in the language. */
        const val FIRST_SIGHTING = 34
        const val PER_SIGHTING = 4
        const val SETTLED = 6
        const val UNKNOWN = 99

        /** Shorter than this and it is more likely a slip or an initial than a word worth keeping. */
        const val SHORTEST = 3

        fun decode(stored: String?): Learned {
            val words = LinkedHashMap<String, Int>()
            for (line in stored.orEmpty().split("\n")) {
                val colon = line.lastIndexOf(':')
                if (colon <= 0) continue
                val count = line.substring(colon + 1).toIntOrNull() ?: continue
                words[line.substring(0, colon)] = count.coerceIn(1, MAX_COUNT)
            }
            return Learned(words)
        }

        /**
         * Whether a word is worth learning at all.
         *
         * [known] says the main dictionary already has it, and [nearMiss] that it is within one edit of a common
         * word - the rule that stops a typo becoming a permanent suggestion.
         */
        fun worthLearning(word: String, known: Boolean, nearMiss: Boolean): Boolean {
            if (word.length < SHORTEST || known || nearMiss) return false
            // Letters and apostrophes only: a word with a digit in it is a password, a code or a model number,
            // and none of those are things anyone wants suggested back to them later.
            return word.all { it.isLetter() || it == '\'' }
        }
    }
}
