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

        /** Whether this is a word at all, which is the question that decides if it may be replaced. */
        fun contains(word: String): Boolean
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
        shortcuts: Shortcuts? = null,
    ): List<String> {
        if (typed.isEmpty()) return emptyList()
        val lower = typed.lowercase()
        val scored = HashMap<String, Int>()

        // Completions: what this word could still turn into.
        val completions = words.startingWith(lower)
        for (index in completions) {
            val word = words.word(index)
            if (word.equals(typed, ignoreCase = true)) continue
            val cost = completionCost(words.rank(index), word.length - typed.length)
            scored.merge(word, cost, ::min)
        }

        // Corrections: only worth looking for once there is enough typed to be wrong about.
        if (typed.length >= SHORTEST_CORRECTABLE) {
            val firsts = firstLetters(lower, proximity)
            val allowed = if (typed.length <= 4) 1 else 2
            for (first in firsts) {
                for (length in typed.length - 1..typed.length + 1) {
                    if (length < 1) continue
                    for (index in words.byShape(first, length)) {
                        val word = words.word(index)
                        if (word.equals(typed, ignoreCase = true)) continue
                        val edits = cost(lower, word.lowercase(), allowed, proximity)
                        if (edits > allowed * SCALE) continue
                        val cost = correctionCost(edits, words.rank(index))
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
                    lowerWord.startsWith(lower) ->
                        completionCost(learned.score(word), word.length - typed.length)
                    typed.length < SHORTEST_CORRECTABLE -> continue
                    else -> {
                        val allowed = if (typed.length <= 4) 1 else 2
                        val edits = cost(lower, lowerWord, allowed, proximity)
                        if (edits > allowed * SCALE) continue
                        correctionCost(edits, learned.score(word))
                    }
                }
                scored.merge(word, cost, ::min)
            }
        }

        val ranked = scored.entries
            .sortedWith(compareBy({ it.value }, { it.key.length }, { it.key }))
            .take(LIMIT)
            .map { matchCase(typed, it.key) }

        // A shortcut is an exact answer to exactly this word, so it goes first - ahead of anything the dictionary
        // merely thinks is likely. It is still only offered: taking it is a tap, the same as everything else here.
        val expansion = shortcuts?.expand(typed)
        return if (expansion == null) ranked else listOf(expansion) + ranked.take(LIMIT - 1)
    }

    /**
     * The one correction confident enough to make without being asked, or null.
     *
     * Everything about this is deliberately reluctant, because the cost of the two mistakes is not equal: failing to
     * fix a typo leaves a typo, and replacing a word someone meant leaves them saying something they did not say.
     *
     * So it only fires when all of these hold:
     *
     * * **What was typed is not a word.** Anything in the dictionary is left exactly alone — which is what keeps
     *   swearing, names and slang safe, and why the dictionary has the spoken vocabulary in it and not just the
     *   formal one.
     * * **It is not something already learned.** Type it twice and the keyboard stops arguing.
     * * **The correction is one mistake away** — one letter, or two swapped — not a guess two edits out.
     * * **The correction is a word people actually use**, not an obscure one that happens to be close.
     * * **Nothing else is nearly as good.** Two plausible candidates means there is no confident answer, and a
     *   coin-flip belongs in the strip where it can be tapped, not in the text.
     */
    fun correction(
        typed: String,
        words: Words,
        proximity: Proximity?,
        learned: Learned? = null,
    ): String? {
        if (typed.length < SHORTEST_CORRECTABLE) return null
        val lower = typed.lowercase()
        if (words.contains(lower) || (learned?.count(lower) ?: 0) > 0) return null
        // Halfway through a longer word is not a mistake. "keyb" is not a word, and "key" is one letter away, but
        // replacing it would take the keyboard off the person typing "keyboard". Where a word could still be
        // finished, finishing it is the strip's job and there is nothing here to put right.
        if (words.startingWith(lower).any { words.rank(it) <= COMMON_ENOUGH }) return null

        var best: String? = null
        var bestCost = Int.MAX_VALUE
        var runnerUp = Int.MAX_VALUE
        val firsts = firstLetters(lower, proximity)
        for (first in firsts) {
            for (length in typed.length - 1..typed.length + 1) {
                if (length < 1) continue
                for (index in words.byShape(first, length)) {
                    val candidate = words.word(index)
                    if (candidate.equals(typed, ignoreCase = true)) continue
                    if (words.rank(index) > COMMON_ENOUGH) continue
                    val edits = cost(lower, candidate.lowercase(), 1, proximity)
                    if (edits > SCALE) continue
                    // How likely the slip was comes first; how common the word is only settles the rest.
                    val cost = edits * 100 + words.rank(index)
                    if (cost < bestCost) {
                        runnerUp = bestCost
                        bestCost = cost
                        best = candidate
                    } else if (cost < runnerUp) {
                        runnerUp = cost
                    }
                }
            }
        }
        if (best == null) return null
        if (runnerUp != Int.MAX_VALUE && runnerUp - bestCost < MARGIN) return null
        return matchCase(typed, best)
    }

    /**
     * Edit distance, giving up once it passes [limit].
     *
     * A substitution for a key that sits next to the one typed is half the price of any other, and two letters
     * swapped count as one mistake rather than two, because those are the mistakes hands actually make. The whole
     * matrix is never needed, so only three rows of it are kept.
     */
    internal fun distance(a: String, b: String, limit: Int, proximity: Proximity?): Int {
        val raw = cost(a, b, limit, proximity)
        return if (raw > limit * SCALE) limit + 1 else (raw + SCALE - 1) / SCALE
    }

    /**
     * What the edits cost, rather than how many there are.
     *
     * Not every single edit is equally likely, and treating them alike loses the difference between a slip and a
     * guess.
     *
     * Two letters the wrong way round is the strongest evidence there is - both letters are right, only the order
     * is wrong. A dropped or doubled letter is next: every letter typed is correct, one is simply missing, which
     * is why "smrt" is so obviously "smart". Then a key next to the one meant. Dearest of all is a key nowhere
     * near it, because hands do not often reach across the keyboard by accident - and pricing that the same as a
     * dropped letter was why "smrt" came out as "sort".
     */
    internal fun cost(a: String, b: String, limit: Int, proximity: Proximity?): Int {
        if (abs(a.length - b.length) > limit) return OVER
        val scale = SCALE
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
                    proximity?.neighbours(a[i - 1])?.contains(b[j - 1]) == true -> NEIGHBOUR
                    else -> scale
                }
                var value = min(
                    min(previous[j] + MISSED, current[j - 1] + MISSED),
                    previous[j - 1] + substitution,
                )
                // Two letters the wrong way round is one mistake, not two. It is what fast hands do more than
                // anything else, and counting it twice puts "the" out of reach of "teh" - which is the whole job.
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    value = min(value, before[j - 2] + SWAP)
                }
                current[j] = value
                if (value < best) best = value
            }
            if (best > cap) return OVER
            val spare = before
            before = previous
            previous = current
            current = spare
        }
        val total = previous[b.length]
        return if (total > cap) OVER else total
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

    /**
     * What a completion and a correction are worth, on one scale so they can be compared.
     *
     * Completions used to be free, which meant any word beginning with what you typed beat any correction however
     * unlikely: "smat" offered "smattering" rather than "smart". Finishing a word is still the stronger answer,
     * but each extra letter it puts in your mouth costs something, so a long rare word has to be genuinely likely
     * before it outranks the short word you probably meant.
     */
    private fun completionCost(rank: Int, extraLetters: Int) = rank + extraLetters * PER_EXTRA_LETTER

    private fun correctionCost(editCost: Int, rank: Int) = rank + editCost * PER_EDIT

    /**
     * Which letters a correction might start with.
     *
     * The typed letter and the keys around it - and the *second* letter too, because swapping the first two is a
     * real slip and it changes the first letter to something that need not be anywhere near it. Without that,
     * "hte" could never reach "the" and was corrected to "he" instead.
     */
    private fun firstLetters(lower: String, proximity: Proximity?): Set<Char> = buildSet {
        add(lower.first())
        if (lower.length > 1) add(lower[1])
        proximity?.neighbours(lower.first())?.let { addAll(it) }
    }

    /** Below this there is not enough typed for "wrong" to mean anything. */
    internal const val SHORTEST_CORRECTABLE = 3
    /** Bigger than any commonness score, so no amount of being common beats being a further edit away. */
    /** Weighed against each other rather than in separate worlds: see [completionCost]. */
    private const val PER_EXTRA_LETTER = 12
    private const val PER_EDIT = 25
    private const val DISTANCE_WEIGHT = 1000

    /**
     * A correction has to be a word people use, not merely a word that exists.
     *
     * Set by measurement, not taste: "receive" scores 33 and "keyboard" 31, so a stricter line than this refused to
     * fix "recieve" and "keybaord" - the two typos most worth fixing. Anything past 50 has no frequency data at all.
     */
    private const val COMMON_ENOUGH = 45

    /** How much better the best candidate must be than the next one before it is worth acting on alone. */
    private const val MARGIN = 6

    /** An edit is worth this much; the kinds that cost less are the kinds hands actually make. */
    internal const val SCALE = 4
    private const val NEIGHBOUR = 3       // a key next to the one meant
    private const val SWAP = 2            // two letters the wrong way round
    private const val MISSED = 3          // a letter dropped or doubled
    private const val OVER = Int.MAX_VALUE / 2
}
