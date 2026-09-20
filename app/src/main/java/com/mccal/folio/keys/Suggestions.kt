package com.mccal.folio.keys

import kotlin.math.abs
import kotlin.math.min

/**
 * What the strip offers, and why.
 *
 * Two different jobs wear the same coat. **Completion** finishes a word that is going fine - you typed `keyb`, it
 * offers `keyboard`. **Correction** proposes a word you did not type, because what you did type isn't one and
 * something close is. The second is the dangerous half: propose too eagerly and the keyboard is arguing with you.
 *
 * Three rules keep it honest:
 *
 * 1. **What you actually typed is always first and always offered.** Whatever is suggested, the literal is there to
 *    take, so nothing you meant can be lost by accident.
 * 2. **Nothing is ever replaced on its own.** A suggestion is taken by tapping it. That is the whole of the promise
 *    that this keyboard will not quietly turn a word you chose into a different one.
 * 3. **A correction has to beat the literal by a margin**, not merely tie with it, or a word the dictionary happens
 *    not to know gets shouted at for no reason.
 */
object Suggestions {

    /** How many go in the strip beside the literal. */
    const val LIMIT = 3

    /**
     * A word the dictionary knows, and how common it is: 0 is the most common band, 3 the least.
     *
     * Kept as an interface so the engine can be tested against a handful of words rather than fifty-seven thousand.
     */
    interface Words {
        val size: Int
        fun word(index: Int): String
        fun rank(index: Int): Int

        /** Every word starting with [prefix], ignoring case. Empty when there are none. */
        fun startingWith(prefix: String): IntRange

        /** Every word of this length starting with this letter: the pool a correction is drawn from. */
        fun byShape(first: Char, length: Int): IntArray
    }

    /**
     * The keys next to a given one, by where they actually are on screen.
     *
     * A fat thumb hits the key beside the one it meant, so the letters worth considering are the physical neighbours
     * of what was typed - and because this is built from the layout in use, a split keyboard or a custom one corrects
     * as well as a plain QWERTY does, rather than against a QWERTY that is no longer on the screen.
     */
    class Proximity(placements: List<Placement>) {
        private val near: Map<Char, Set<Char>>

        init {
            val letters = placements.filter { it.key.kind == KeyKind.CHAR && it.key.output.length == 1 }
                .associate { it.key.output.first().lowercaseChar() to it.box }
            near = letters.mapValues { (char, box) ->
                val cx = (box.left + box.right) / 2
                val cy = (box.top + box.bottom) / 2
                val reach = box.width * 1.6f
                letters.filterKeys { it != char }
                    .filterValues { other ->
                        val ox = (other.left + other.right) / 2
                        val oy = (other.top + other.bottom) / 2
                        abs(ox - cx) <= reach && abs(oy - cy) <= box.height * 1.3f
                    }
                    .keys
            }
        }

        fun neighbours(char: Char): Set<Char> = near[char.lowercaseChar()].orEmpty()

        fun isEmpty() = near.isEmpty()
    }

    /**
     * The candidates for what is being typed, best first, never including the literal itself.
     *
     * [typed] is the word so far, with no spaces in it.
     */
    fun forWord(
        typed: String,
        words: Words,
        proximity: Proximity?,
        learned: Learned? = null,
    ): List<String> {
        if (typed.isEmpty()) return emptyList()
        val lower = typed.lowercase()
        val scored = HashMap<String, Int>()

        // Completions: what this word could still turn into.
        val completions = words.startingWith(lower)
        for (index in completions) {
            val word = words.word(index)
            if (word.equals(typed, ignoreCase = true)) continue
            // A word that starts with what you typed is strong evidence, so completions sit at distance zero and
            // are separated only by how common they are and how much is left to add.
            val cost = words.rank(index) + (word.length - typed.length)
            scored.merge(word, cost, ::min)
        }

        // Corrections: only worth looking for once there is enough typed to be wrong about.
        if (typed.length >= SHORTEST_CORRECTABLE) {
            val firsts = buildSet {
                add(lower.first())
                proximity?.neighbours(lower.first())?.let { addAll(it) }
            }
            val allowed = if (typed.length <= 4) 1 else 2
            for (first in firsts) {
                for (length in typed.length - 1..typed.length + 1) {
                    if (length < 1) continue
                    for (index in words.byShape(first, length)) {
                        val word = words.word(index)
                        if (word.equals(typed, ignoreCase = true)) continue
                        val distance = distance(lower, word.lowercase(), allowed, proximity)
                        if (distance > allowed) continue
                        // Distance decides outright; commonness only ever settles a tie between equal distances.
                        // Before this, every word in the dictionary's top tier scored the same, so "teh" offered
                        // "get", "tea" and "ten" in alphabetical order and never reached "the".
                        val cost = distance * DISTANCE_WEIGHT + words.rank(index)
                        scored.merge(word, cost, ::min)
                    }
                }
            }
        }

        // What someone has typed before, judged on the same scale: a name they use constantly should beat a word
        // the language happens to contain but they never write.
        if (learned != null) {
            for (word in learned.all()) {
                if (word.equals(typed, ignoreCase = true)) continue
                val lowerWord = word.lowercase()
                val cost = when {
                    lowerWord.startsWith(lower) -> learned.score(word) + (word.length - typed.length)
                    typed.length < SHORTEST_CORRECTABLE -> continue
                    else -> {
                        val allowed = if (typed.length <= 4) 1 else 2
                        val distance = distance(lower, lowerWord, allowed, proximity)
                        if (distance > allowed) continue
                        distance * DISTANCE_WEIGHT + learned.score(word)
                    }
                }
                scored.merge(word, cost, ::min)
            }
        }

        return scored.entries
            .sortedWith(compareBy({ it.value }, { it.key.length }, { it.key }))
            .take(LIMIT)
            .map { matchCase(typed, it.key) }
    }

    /**
     * Edit distance, giving up once it passes [limit].
     *
     * A substitution for a key that sits next to the one typed is half the price of any other, and two letters
     * swapped count as one mistake rather than two, because those are the mistakes hands actually make. The whole
     * matrix is never needed, so only three rows of it are kept.
     */
    internal fun distance(a: String, b: String, limit: Int, proximity: Proximity?): Int {
        if (abs(a.length - b.length) > limit) return limit + 1
        val scale = 2                       // everything is doubled so a near miss can cost half
        val cap = limit * scale
        var before = IntArray(b.length + 1)
        var previous = IntArray(b.length + 1) { it * scale }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i * scale
            var best = current[0]
            for (j in 1..b.length) {
                val substitution = when {
                    a[i - 1] == b[j - 1] -> 0
                    proximity?.neighbours(a[i - 1])?.contains(b[j - 1]) == true -> 1
                    else -> scale
                }
                var value = min(
                    min(previous[j] + scale, current[j - 1] + scale),
                    previous[j - 1] + substitution,
                )
                // Two letters the wrong way round is one mistake, not two. It is what fast hands do more than
                // anything else, and counting it twice puts "the" out of reach of "teh" - which is the whole job.
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    value = min(value, before[j - 2] + scale)
                }
                current[j] = value
                if (value < best) best = value
            }
            if (best > cap) return limit + 1
            val spare = before
            before = previous
            previous = current
            current = spare
        }
        val cost = previous[b.length]
        return if (cost > cap) limit + 1 else (cost + scale - 1) / scale
    }

    /**
     * A suggestion wears the case of what was typed.
     *
     * Someone typing `Teh` wants `The`, not `the`, and someone shouting in capitals wants the capitals kept.
     */
    internal fun matchCase(typed: String, candidate: String): String = when {
        typed.length > 1 && typed.all { it.isUpperCase() } -> candidate.uppercase()
        typed.firstOrNull()?.isUpperCase() == true ->
            candidate.replaceFirstChar { it.uppercaseChar() }
        else -> candidate
    }

    /** Below this there is not enough typed for "wrong" to mean anything. */
    internal const val SHORTEST_CORRECTABLE = 3
    /** Bigger than any commonness score, so no amount of being common beats being a further edit away. */
    private const val DISTANCE_WEIGHT = 1000
}
