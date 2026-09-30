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
class Learned(
    private val words: MutableMap<String, Int> = LinkedHashMap(),
    private val seen: MutableMap<String, Sighting> = LinkedHashMap(),
) {

    val size: Int get() = words.size

    /** How many times this word has been typed, or zero. */
    fun count(word: String): Int = words[word] ?: 0

    /** Every learned word, most used first. */
    fun all(): List<String> = words.entries.sortedByDescending { it.value }.map { it.key }

    /**
     * Every learned word, in no particular order, without copying them.
     *
     * For the strip, which looks through all of them on every letter and scores each on its own, so the order is
     * no help there and sorting up to [LIMIT] words each time was the most expensive part of a lookup. A live view:
     * read it and let go, rather than keeping it.
     */
    val unordered: Collection<String> get() = java.util.Collections.unmodifiableSet(words.keys)

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

    /**
     * A word someone said, in so many words, to keep.
     *
     * Straight in at [MEANT_IT], past the rules [learn] is guarded by: it was asked, and answered Keep. That is also
     * what stops it being corrected, since a correction never touches a learned word, and what keeps [prune] from
     * taking it back out.
     */
    fun keep(word: String) {
        val existing = words[word]
        if (existing == null && words.size >= LIMIT) forgetLeastUsed()
        words[word] = maxOf(existing ?: 0, MEANT_IT)
    }

    /**
     * A word left exactly as typed that [learn] would not take, because it is one edit from a common word.
     *
     * Rule 2 above is right about "teh" and wrong about "wifi", "bruh" and "yeet", and the only way to tell them
     * apart is to watch: a typo gets corrected, or fixed by hand, and does not come back the same way. A word that
     * does - typed and left alone [SIGHTINGS] times, on at least [DAYS] different days - was meant, and is kept as
     * if Keep had been tapped, so it is not corrected again. The days matter: the same slip three times in one
     * hurried message is a habit of the moment, not a word. [day] is any count of days, such as days since 1970.
     * Returns true when the store changed.
     */
    fun sighted(word: String, day: Long): Boolean {
        if (word in words) return false
        val before = seen[word]
        val now = Sighting(
            times = (before?.times ?: 0) + 1,
            lastDay = day,
            days = when {
                before == null -> 1
                before.lastDay != day -> minOf(before.days + 1, DAYS)
                else -> before.days
            },
        )
        if (now.times >= SIGHTINGS && now.days >= DAYS) {
            seen.remove(word)
            keep(word)
            return true
        }
        if (before == null && seen.size >= SEEN_LIMIT) seen.remove(seen.keys.first())
        seen[word] = now
        return true
    }

    /** How often a word has been seen left alone, on how many days, and the last of them. */
    class Sighting(val times: Int, val lastDay: Long, val days: Int)

    /**
     * Drops the words [slip] says were typos, unless they were typed often enough to mean it. Returns how many went.
     *
     * For words learned before a rule got stricter: without this they would sit in the strip for as long as the
     * install lasts, which is the one thing the rules exist to prevent.
     */
    fun prune(slip: (String) -> Boolean): Int {
        val gone = words.entries.filter { it.value < MEANT_IT && slip(it.key) }.map { it.key }
        gone.forEach { words.remove(it) }
        return gone.size
    }

    /**
     * One word taken back out, for holding it in the strip and choosing Don't suggest. Its sightings go too, or a
     * near miss watched for a while would come straight back in. True when anything was there to forget.
     */
    fun forget(word: String): Boolean = (words.remove(word) != null) or (seen.remove(word) != null)

    /** Emptied, for the tap that says "forget what you have learned about me". */
    fun clear() {
        words.clear()
        seen.clear()
    }

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

    /** The words seen but not yet kept, in the same shape, stored apart so nothing else mistakes them for learned. */
    fun encodeSeen(): String =
        seen.entries.joinToString("\n") { (word, it) -> "$word:${it.times}:${it.lastDay}:${it.days}" }

    companion object {
        /** Enough for how anyone writes, small enough to read and scan on every keystroke. */
        const val LIMIT = 1200
        const val MAX_COUNT = 60

        /** Typed this many times, a "typo" is how someone spells it, and pruning leaves it alone. */
        const val MEANT_IT = 3

        /** Scores sit on the same scale the dictionary uses: 0 is the commonest word in the language. */
        const val FIRST_SIGHTING = 34
        const val PER_SIGHTING = 4
        const val SETTLED = 6
        const val UNKNOWN = 99

        /** Typed and left alone this many times, a near miss of a common word is a word. */
        const val SIGHTINGS = 3

        /** ...and on this many different days. */
        const val DAYS = 2

        /** Put back with backspace this many times, a correction was wrong and the word is kept. */
        const val PUT_BACKS = 2

        /** Seen-but-not-kept words are a waiting room, not a second dictionary: the oldest leave first. */
        const val SEEN_LIMIT = 200

        /** Shorter than this and it is more likely a slip or an initial than a word worth keeping. */
        const val SHORTEST = 3

        fun decode(stored: String?, seen: String? = null): Learned = Learned(counts(stored), sightings(seen))

        private fun sightings(stored: String?): LinkedHashMap<String, Sighting> {
            val seen = LinkedHashMap<String, Sighting>()
            for (line in stored.orEmpty().split("\n")) {
                // A word never holds a colon, so the first one ends it.
                val parts = line.split(':')
                if (parts.size != 4 || parts[0].isEmpty()) continue
                val times = parts[1].toIntOrNull() ?: continue
                val lastDay = parts[2].toLongOrNull() ?: continue
                val days = parts[3].toIntOrNull() ?: continue
                seen[parts[0]] = Sighting(times.coerceIn(1, SIGHTINGS), lastDay, days.coerceIn(1, DAYS))
            }
            return seen
        }

        private fun counts(stored: String?): LinkedHashMap<String, Int> {
            val words = LinkedHashMap<String, Int>()
            for (line in stored.orEmpty().split("\n")) {
                val colon = line.lastIndexOf(':')
                if (colon <= 0) continue
                val count = line.substring(colon + 1).toIntOrNull() ?: continue
                words[line.substring(0, colon)] = count.coerceIn(1, MAX_COUNT)
            }
            return words
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
