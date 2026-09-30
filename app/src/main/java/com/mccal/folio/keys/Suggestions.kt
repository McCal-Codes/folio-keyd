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

    /**
     * How many go in the strip beside the literal, at most: enough for the widest strip, which has room for five
     * words. A narrower one shows the first of them ([KeyboardView.wordSlots]).
     */
    const val LIMIT = 4

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
        contractions: Contractions.Table? = null,
        previous: String = "",
        next: NextWords? = null,
    ): List<String> {
        if (typed.isEmpty()) return emptyList()
        val lower = typed.lowercase()
        val scored = HashMap<String, Int>()
        val possessive = '\'' in typed

        // Completions: what this word could still turn into.
        val completions = words.startingWith(lower)
        for (index in completions) {
            val word = words.word(index)
            if (word.equals(typed, ignoreCase = true)) continue
            if (words.rank(index) == Dictionary.KNOWN_ONLY) continue
            if (!possessive && isPossessive(word)) continue
            // Past the apostrophe, what is left is an ending - "re", "ll", "d" - and which one is meant is a question
            // of how common each is, not of how many letters it has: "you'" is "you're" far more often than "you'd".
            val extra = if (possessive) 0 else word.length - typed.length
            val cost = completionCost(words.rank(index), extra)
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
                        if (words.rank(index) == Dictionary.KNOWN_ONLY || word.endsWith('\'')) continue
                        if (!possessive && isPossessive(word)) continue
                        val edits = cost(lower, word.lowercase(), allowed, proximity)
                        if (edits > allowed * SCALE) continue
                        // Only the accents missing is not a mistake in the word, so it is priced as the word itself:
                        // "familia" offers "família" ahead of "familiar".
                        val cost = if (edits == ACCENT) words.rank(index) else correctionCost(edits, words.rank(index))
                        scored.merge(word, cost, ::min)
                    }
                }
            }
        }

        // What someone has typed before, judged on the same scale: a name they use constantly should beat a word
        // the language happens to contain but they never write.
        if (learned != null) {
            for (word in learned.unordered) {
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

        // The word before says which of these is likelier: after "I", "want" rather than "wait".
        if (next != null && previous.isNotEmpty()) {
            for (entry in scored.entries) entry.setValue(entry.value - context(next, previous, entry.key))
        }

        // "dont" and "youre" are in the word list only because subtitles lost their apostrophes. With the table
        // there to put them back, the bare forms are never worth offering.
        if (contractions != null) scored.keys.removeAll { contractions.sure.containsKey(it.lowercase()) }

        // "May" the name and "may" the word are one suggestion once they wear the case of what was typed, and
        // the strip is too short to spend two of its places on it.
        val ranked = scored.entries
            .sortedWith(compareBy({ it.value }, { it.key.length }, { it.key }))
            .map { matchCase(typed, it.key) }
            .distinctBy { it.lowercase() }
            .take(LIMIT)

        // A shortcut is an exact answer to exactly this word, so it goes first - ahead of anything the dictionary
        // merely thinks is likely. A missing apostrophe is the next most certain answer there is. Both are still
        // only offered: taking one is a tap, the same as everything else here.
        // And two words with the space missed, when this is not on its way to being a longer word.
        val split = if (typed.length >= SHORTEST_CORRECTABLE && completions.none { words.rank(it) <= COMMON_ENOUGH } &&
            !known(lower, words)
        ) spaceSlip(lower, words)?.let { matchCase(typed, it.words) } else null
        val first = listOfNotNull(
            shortcuts?.expand(typed)?.let { matchCase(typed, it) },
            contractions?.let { Contractions.offer(typed, it) },
            split,
        ).distinct()
        return (first + ranked.filter { it !in first }).take(LIMIT)
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
        contractions: Contractions.Table? = null,
        previous: String = "",
        compounds: Boolean = false,
        next: NextWords? = null,
    ): String? {
        val lower = typed.lowercase()
        // Something kept on purpose is never argued with, apostrophe or not.
        if ((learned?.count(lower) ?: 0) > 0) return null
        // Ahead of the dictionary check, because the dictionary knows "dont": see [Contractions].
        if (contractions != null) Contractions.fix(typed, contractions, previous)?.let { return it }
        if (typed.length < SHORTEST_CORRECTABLE) return null
        if (known(lower, words)) return null
        // Halfway through a longer word is not a mistake. "keyb" is not a word, and "key" is one letter away, but
        // replacing it would take the keyboard off the person typing "keyboard". Where a word could still be
        // finished, finishing it is the strip's job and there is nothing here to put right. The exception is a word
        // that is only missing its accent: "accion" could go on to be "acciones", but typed and finished it is
        // "acción", and nothing else is that close.
        if (words.startingWith(lower).any { words.rank(it) <= COMMON_ENOUGH }) return accented(typed, lower, words)

        val possessive = '\'' in typed
        val short = typed.length <= SHORT_WORD
        var best: String? = null
        var bestRank = 0
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
                    // "jusqu'" and "ma'" are halves of words. Put in on their own they cut the word in two.
                    if (candidate.endsWith('\'')) continue
                    if (!possessive && isPossessive(candidate)) continue
                    val edits = cost(lower, candidate.lowercase(), 1, proximity)
                    if (edits > SCALE) continue
                    // How likely the slip was comes first; how common the word is, and how often it follows the word
                    // before, only settle the rest.
                    val cost = edits * 100 + words.rank(index) -
                        (if (next != null && previous.isNotEmpty()) context(next, previous, candidate) else 0)
                    if (cost < bestCost) {
                        runnerUp = bestCost
                        bestCost = cost
                        best = candidate
                        bestRank = words.rank(index)
                    } else if (cost < runnerUp) {
                        runnerUp = cost
                    }
                }
            }
        }
        // Two words with the space missed: "thisis", "tothe". Every letter is right, so it is better evidence than a
        // guess one letter out - "thesis" and "tote" were what these used to become. Not in a language that writes
        // its compounds as one word, where two common words run together is how a third one is spelled.
        val slip = if (compounds) null else spaceSlip(lower, words)
        if (slip != null && slip.sure && slip.rank <= SPLIT_SURE) {
            // It still has to beat a correction, and a letter pressed twice is good evidence too: "allso" is "also",
            // not "all so". So the pair wins over a real slip only when both its words are far commoner than the
            // one the slip would make.
            if (best == null || (bestCost / 100 >= MISSED && slip.rank + SPLIT_EDGE <= bestRank)) {
                return matchCase(typed, slip.words)
            }
        }
        if (best == null) return null
        if (runnerUp != Int.MAX_VALUE && runnerUp - bestCost < MARGIN) return null
        // A short word is too easily something typed on purpose - "idk", "wifi", "bday" - so it is only fixed for
        // the slips a thumb makes. A letter from across the keyboard is as likely to be meant.
        if (short && !thumbSlip(lower, best.lowercase(), proximity)) return null
        // No vowel at all is how "btw", "smh" and "thx" are spelled on purpose. It is also "thw", "the" with the e
        // missed for the key beside it, and "bck", "back" with the vowel left out. Those two slips are let through,
        // and only to a common word.
        if (lower.none { it in VOWELS } && !(bestRank <= VOWEL_SLIP_COMMON && lostVowel(lower, best.lowercase(), proximity))) {
            return null
        }
        return matchCase(typed, best)
    }

    /**
     * Whether a word is in the list, either whole or as an elision and a word: "l'homme", "c'était", "dell'anno".
     *
     * French and Italian glue the article to the next word with an apostrophe, and the lists keep the two halves
     * apart - "l'" is a word in them, and so is "homme" - so the whole was never found, and every one of those was
     * underlined as if it were misspelled.
     */
    fun known(word: String, words: Words): Boolean {
        if (words.contains(word)) return true
        val apostrophe = word.indexOf('\'')
        if (apostrophe <= 0 || apostrophe > MOST_ELIDED || apostrophe == word.length - 1) return false
        if (!words.contains(word.substring(0, apostrophe + 1))) return false
        val tail = word.substring(apostrophe + 1)
        // "jusqu'à", "m'a", "dov'è", "qu'y": the word after is often one letter, and the lists leave those out.
        return (tail.length == 1 && tail[0] in ONE_LETTER_WORDS) || known(tail, words)
    }

    /**
     * The common word that is [lower] with its accents put on, or null. Held to the same margin as any correction:
     * "für" is 14 and "fûr", a subtitle typo, is 44, which is no contest; two real words close in rank are.
     */
    private fun accented(typed: String, lower: String, words: Words): String? {
        val first = bare(lower.first())
        var found: String? = null
        var foundRank = Int.MAX_VALUE
        var runnerUp = Int.MAX_VALUE
        for (letter in listOf(first) + ACCENTED[first]?.toList().orEmpty()) {
            for (index in words.byShape(letter, lower.length)) {
                val rank = words.rank(index)
                if (rank > COMMON_ENOUGH) continue
                val candidate = words.word(index).lowercase()
                if (candidate == lower || candidate == found || candidate.indices.any { bare(candidate[it]) != lower[it] }) continue
                if (rank < foundRank) {
                    runnerUp = foundRank
                    foundRank = rank
                    found = candidate
                } else if (rank < runnerUp) {
                    runnerUp = rank
                }
            }
        }
        if (found == null || runnerUp - foundRank < MARGIN) return null
        return matchCase(typed, found)
    }

    /**
     * What might come next, after a space: the words that most often follow [previous], in the case [shift] asks
     * for. Empty when nothing is known about the word before.
     */
    fun predict(previous: String, next: NextWords?, shift: Shift = Shift.OFF): List<String> {
        if (next == null || previous.isEmpty()) return emptyList()
        return next.after(previous).take(LIMIT).map {
            when (shift) {
                Shift.OFF -> it
                Shift.ONCE -> it.replaceFirstChar { first -> first.uppercaseChar() }
                Shift.LOCKED -> it.uppercase()
            }
        }
    }

    /**
     * The emoji to offer at the end of the strip for [typed], or null.
     *
     * An exact match only, from the names Unicode gives emoji in this language: see [EmojiSearch.exact]. Not for the
     * commonest words, the "the", "with" and "you" that emoji names and keywords are full of; an emoji offered after
     * every one of those is noise, and nobody reaches for one there.
     */
    fun emoji(typed: String, words: Words?, names: EmojiSearch?): String? {
        if (names == null || typed.length < 2) return null
        if (words != null && (rankOf(typed.lowercase(), words) ?: Int.MAX_VALUE) <= GRAMMAR) return null
        return names.exact(typed)
    }

    /**
     * How much likelier the word before makes [word]: nothing if it is not among what follows [previous], most for
     * the commonest follower. Less than any one mistyped letter is worth - a neighbouring key costs 75 in the strip
     * and 300 when correcting - so it chooses between words about as close to what was typed, and never makes a
     * worse match into a better one.
     */
    private fun context(next: NextWords, previous: String, word: String): Int {
        val place = next.place(previous, word) ?: return 0
        return CONTEXT_BONUS - place
    }

    /** Two words where one was typed, and whether that is the only way to read it. */
    class SpaceSlip(val words: String, val sure: Boolean, val rank: Int)

    /**
     * [lower] as two common words with the space between them missed, or null.
     *
     * Both halves have to be common - "this" and "is", not "the" and "sis" - which is also what keeps a long word
     * that happens to contain two short ones from being taken apart. Where there is more than one way to cut it,
     * the commoner pair is offered and none of them is sure.
     */
    internal fun spaceSlip(lower: String, words: Words): SpaceSlip? {
        var found: String? = null
        var bestScore = Int.MAX_VALUE
        var rarer = 0
        var ways = 0
        // Two letters each at least: "a" and "i" are words, but "aable" is "able" with the a pressed twice, not "a able".
        for (at in 2..lower.length - 2) {
            val left = rankOf(lower.substring(0, at), words) ?: continue
            if (left > SPLIT_COMMON) continue
            val right = rankOf(lower.substring(at), words) ?: continue
            if (right > SPLIT_COMMON) continue
            ways++
            if (left + right < bestScore) {
                bestScore = left + right
                rarer = maxOf(left, right)
                found = lower.substring(0, at) + " " + lower.substring(at)
            }
        }
        return found?.let { SpaceSlip(it, sure = ways == 1, rank = rarer) }
    }

    /** How common a word is, the commonest reading if it comes in more than one case, or null if it is not a word. */
    private fun rankOf(word: String, words: Words): Int? {
        var best: Int? = null
        for (index in words.startingWith(word)) {
            if (words.word(index).length != word.length) break
            if (!words.word(index).equals(word, ignoreCase = true)) continue
            best = minOf(best ?: Int.MAX_VALUE, words.rank(index))
        }
        return best
    }

    /** "wont's" and "bib's" are words, but not what someone typing "wont" or "bib" is after. */
    private fun isPossessive(word: String) = word.endsWith("'s")

    /**
     * Whether [typed] is [word] with one of the slips a thumb makes: a neighbouring key, two letters swapped, a
     * letter left out, a key pressed twice, or an extra key beside the one meant. What is left is a letter from
     * across the keyboard, in place of one or on top of it, and in a short word that is as likely to be a word
     * being spelled on purpose.
     */
    internal fun thumbSlip(typed: String, word: String, proximity: Suggestions.Proximity?): Boolean {
        if (typed.length == word.length) {
            // A missing accent is not a slip of the thumb at all, but it is as sure a sign of one word as any.
            val differ = typed.indices.filter { bare(typed[it]) != bare(word[it]) }
            return when (differ.size) {
                0 -> true
                1 -> proximity?.neighbours(typed[differ[0]])?.contains(word[differ[0]]) == true
                2 -> differ[1] == differ[0] + 1 && typed[differ[0]] == word[differ[1]] && typed[differ[1]] == word[differ[0]]
                else -> false
            }
        }
        if (typed.length + 1 == word.length) return true
        if (typed.length != word.length + 1) return false
        for (at in typed.indices) {
            if (typed.removeRange(at, at + 1) != word) continue
            val extra = typed[at]
            val beside = listOfNotNull(typed.getOrNull(at - 1), typed.getOrNull(at + 1))
            if (extra in beside) return true
            if (beside.any { proximity?.neighbours(it)?.contains(extra) == true }) return true
        }
        return false
    }

    /** [word] is [typed] with its one vowel back: missed for the key beside it, or left out altogether. */
    private fun lostVowel(typed: String, word: String, proximity: Suggestions.Proximity?): Boolean {
        val vowels = word.count { it in VOWELS }
        if (vowels != 1) return false
        return when (word.length) {
            typed.length -> thumbSlip(typed, word, proximity)
            typed.length + 1 -> word.indices.any { word[it] in VOWELS && word.removeRange(it, it + 1) == typed }
            else -> false
        }
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
        // Accents left off are one habit, not several mistakes: "deja" is as near "déjà" as "déja" is, and the
        // commoner word should win rather than the one with fewer marks to add.
        if (a.length == b.length && a != b && a.indices.all { bare(a[it]) == bare(b[it]) }) return ACCENT
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
                    bare(a[i - 1]) == bare(b[j - 1]) -> ACCENT
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
        // "ecole" is "école": the accent is on the first letter, and a word starting with é is filed under é.
        ACCENTED[bare(lower.first())]?.let { accented -> accented.forEach { add(it) } }
    }

    /**
     * The letter with its accent taken off, or the letter itself.
     *
     * Leaving an accent off is the commonest way to misspell in every language that has them - the accent is a long
     * press away - and it is not a mistyped letter at all: the letter is right and only the mark is missing. So it
     * costs a quarter of an edit rather than a whole one, which is what lets "accion" reach "acción" ahead of every
     * other word one letter from it.
     */
    internal fun bare(char: Char): Char = if (char.code < BARE.size) BARE[char.code] else char

    /** Looked up in every cell of every comparison, so a table rather than a map. */
    private val BARE = CharArray(0x180) { it.toChar() }.also { table ->
        for ((plain, marked) in ACCENTS) for (it in marked) table[it.code] = plain
    }

    private val ACCENTED: Map<Char, String> = ACCENTS.toMap()

    private val ACCENTS
        get() = listOf(
            'a' to "àáâäãå", 'e' to "èéêë", 'i' to "ìíîï", 'o' to "òóôöõ", 'u' to "ùúûü",
            'c' to "ç", 'n' to "ñ", 'y' to "ýÿ",
        )

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

    /** Each half of a missed space has to be at least this common: "this" is 12, "is" 10, "sis" 36. */
    private const val SPLIT_COMMON = 30

    /**
     * Each half common enough for the space to be put in unasked. Stricter than the strip's bar, because a word the
     * list lacks is often two words joined on purpose - "airsick", "voiceless" - and those halves are rarer.
     */
    private const val SPLIT_SURE = 20

    /** How much commoner than a correction both halves of a missed space must be to win: ten points is ten times. */
    private const val SPLIT_EDGE = 10

    /** The one-letter words an elision can come before, in French and Italian. */
    private const val ONE_LETTER_WORDS = "aàeèéoyiìuù"

    /** The longest article or pronoun that elides: "quelqu'" and "lorsqu'" are the long end of it. */
    private const val MOST_ELIDED = 8

    /** At or under this many letters, only a thumb slip is corrected unasked: see [thumbSlip]. */
    private const val SHORT_WORD = 4

    /** Common enough to be what a word with no vowels meant: "bath" for "bqth" is 33, "tux" for "thx" is 41. */
    private const val VOWEL_SLIP_COMMON = Dictionary.COMMON

    /** A word with none of these has no vowel to have mistyped, and is an abbreviation rather than a slip. */
    private const val VOWELS = "aeiouyàáâäãåèéêëìíîïòóôöõùúûüýÿœæ"

    /**
     * What following the word before is worth to the commonest follower; each place further down is worth one less.
     * Set by measuring on sentences the lists were not built from: 16 moved little, and past 40 nothing improved.
     */
    private const val CONTEXT_BONUS = 40

    /**
     * As common as this and a word is grammar, not a thing: "you", "the", "and", "with", "not", "like". Set by
     * reading which English words emoji names and keywords share with the top of the word list.
     */
    private const val GRAMMAR = 16

    /** How much better the best candidate must be than the next one before it is worth acting on alone. */
    private const val MARGIN = 6

    /** An edit is worth this much; the kinds that cost less are the kinds hands actually make. */
    internal const val SCALE = 4
    private const val ACCENT = 1          // the right letter, with its accent left off
    private const val NEIGHBOUR = 3       // a key next to the one meant
    private const val SWAP = 2            // two letters the wrong way round
    private const val MISSED = 3          // a letter dropped or doubled
    private const val OVER = Int.MAX_VALUE / 2
}
