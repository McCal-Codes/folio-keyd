package com.mccal.folio.keys

/**
 * What autocorrect got right and what it got wrong, counted.
 *
 * Two lists. **Fixed** is a correction that stood: the next word began and nobody pressed backspace to take it back.
 * **Put back** is one that did not: backspace straight after it, which puts back what was typed. Neither holds a
 * sentence or even the word before - only the word typed, and what it was changed to.
 *
 * Counting them is what lets the keyboard ask a useful question at the right moment. The third time someone puts
 * back "Folio", it is not a typo and never was; the third time "teh" becomes "the" without complaint, a rule that
 * always does it is worth offering. Each is asked in the strip, at most twice ever, and never again after No.
 *
 * The same rules as [Learned]: never from a password field, never from an app that asked not to be learned from,
 * nothing at all while learning is off - all checked in [TextActions] before anything reaches this class. It is
 * capped, the least counted go first, it stays on this phone, and one tap empties it.
 */
class Insights(
    private val fixes: MutableMap<String, Entry> = LinkedHashMap(),
    private val putBacks: MutableMap<String, Entry> = LinkedHashMap(),
) {

    /**
     * One pair, and where it stands.
     *
     * [pending] counts since the strip last asked, which is what "three more" means; [offered] is how many times it
     * has asked; [settled] is an answer given, either way, after which it never asks again.
     */
    data class Entry(
        val typed: String,
        val replacement: String?,
        val count: Int,
        val pending: Int = count,
        val offered: Int = 0,
        val settled: Boolean = false,
    )

    /** A question for the strip. [replacement] is null when the question is whether to keep [typed] as a word. */
    data class Offer(val typed: String, val replacement: String?) {
        val keep: Boolean get() = replacement == null
    }

    val size: Int get() = fixes.size + putBacks.size

    fun isEmpty() = size == 0

    /** The corrections that stood, most counted first. */
    fun fixed(): List<Entry> = fixes.values.sortedByDescending { it.count }

    /** The corrections that were undone, most counted first. */
    fun putBack(): List<Entry> = putBacks.values.sortedByDescending { it.count }

    /**
     * A correction stood. Returns the question to ask, if this is the moment for one.
     *
     * With [offering] false the count still goes up, so the lists in Settings are right; nothing is asked, and
     * nothing is marked as asked.
     */
    fun fixStood(typed: String, replacement: String, offering: Boolean): Offer? {
        val from = typed.lowercase()
        val to = replacement.lowercase()
        if (from.isEmpty() || to.isEmpty() || from == to) return null
        val key = "$from\t$to"
        // Counted in any case, but kept with the capitals the correction gave it: "ive" became "I've", and Always
        // should make a shortcut to "I've", not "i've". A capital that only follows a capital typed at the start of
        // a sentence is the sentence's, not the word's, so that one is taken off.
        val spelled = if (typed.first().isUpperCase()) replacement.replaceFirstChar { it.lowercase() } else replacement
        note(fixes, key) { Entry(from, spelled, 0) }
        if (!typed.first().isUpperCase()) fixes[key] = fixes.getValue(key).copy(replacement = replacement)
        return ask(fixes, key, offering)
    }

    /** A correction was undone. Returns the question to ask, if this is the moment for one. */
    fun undone(typed: String, offering: Boolean): Offer? {
        if (typed.isEmpty()) return null
        val key = typed.lowercase()
        // Kept as it was typed last, so the strip asks about "Folio" rather than "folio".
        note(putBacks, key) { Entry(typed, null, 0) }
        putBacks[key] = putBacks.getValue(key).copy(typed = typed)
        return ask(putBacks, key, offering)
    }

    /** An answer, Yes or No. Either way it is never asked again. */
    fun answered(offer: Offer) {
        val (map, key) = where(offer)
        map[key]?.let { map[key] = it.copy(settled = true, pending = 0) }
    }

    /**
     * An answer, and what it changes. Keep puts the word in with the learned ones, which is what stops it being
     * corrected; Always makes a text shortcut. Either way, or No, the pair is never asked about again.
     *
     * The strip and the Settings page both answer through this, so the two can never disagree about what Keep means.
     */
    fun answer(offer: Offer, accepted: Boolean, learned: Learned, shortcuts: Shortcuts) {
        answered(offer)
        if (!accepted) return
        val replacement = offer.replacement
        if (replacement == null) learned.keep(offer.typed.lowercase()) else shortcuts.add(offer.typed, replacement)
    }

    /** Whether this pair has been answered. */
    fun settled(offer: Offer): Boolean {
        val (map, key) = where(offer)
        return map[key]?.settled == true
    }

    /** Emptied, for the tap that says forget these counts. */
    fun clear() {
        fixes.clear()
        putBacks.clear()
    }

    private fun where(offer: Offer): Pair<MutableMap<String, Entry>, String> =
        if (offer.keep) putBacks to offer.typed.lowercase()
        else fixes to "${offer.typed.lowercase()}\t${offer.replacement!!.lowercase()}"

    private fun note(map: MutableMap<String, Entry>, key: String, fresh: () -> Entry) {
        val existing = map[key]
        if (existing == null && size >= LIMIT) forgetLeastCounted()
        val entry = existing ?: fresh()
        map[key] = entry.copy(
            count = minOf(entry.count + 1, MAX_COUNT),
            pending = if (entry.settled) 0 else minOf(entry.pending + 1, MAX_COUNT),
        )
    }

    private fun ask(map: MutableMap<String, Entry>, key: String, offering: Boolean): Offer? {
        val entry = map[key] ?: return null
        if (!offering || entry.settled || entry.offered >= MOST_OFFERS || entry.pending < ASK_AFTER) return null
        // Asked now, so it takes another three before it can be asked again, whatever the answer turns out to be.
        map[key] = entry.copy(pending = 0, offered = entry.offered + 1)
        return Offer(entry.typed, entry.replacement)
    }

    private fun forgetLeastCounted() {
        val fix = fixes.entries.minByOrNull { it.value.count }
        val back = putBacks.entries.minByOrNull { it.value.count }
        when {
            fix == null && back == null -> Unit
            back == null || (fix != null && fix.value.count <= back.value.count) -> fixes.remove(fix!!.key)
            else -> putBacks.remove(back.key)
        }
    }

    /**
     * One pair per line: `f` or `p`, then what was typed, what it became (empty for a put back), and the four
     * numbers. Tab-separated, because a word is letters and apostrophes and can never hold a tab.
     */
    fun encode(): String = buildList {
        fixes.values.forEach { add(line("f", it)) }
        putBacks.values.forEach { add(line("p", it)) }
    }.joinToString("\n")

    private fun line(kind: String, e: Entry) = listOf(
        kind, e.typed, e.replacement.orEmpty(), e.count, e.pending, e.offered, if (e.settled) 1 else 0,
    ).joinToString("\t")

    companion object {
        /** Enough to show what matters, small enough that nobody's typing history piles up in it. */
        const val LIMIT = 300
        const val MAX_COUNT = 9999

        /** The same fix or undo this many times, and the strip asks. */
        const val ASK_AFTER = 3

        /** It asks about one pair at most this many times, ever. */
        const val MOST_OFFERS = 2

        fun decode(stored: String?): Insights {
            val fixes = LinkedHashMap<String, Entry>()
            val putBacks = LinkedHashMap<String, Entry>()
            for (line in stored.orEmpty().split("\n")) {
                val parts = line.split("\t")
                if (parts.size != 7 || parts[1].isEmpty()) continue
                val count = parts[3].toIntOrNull()?.coerceIn(1, MAX_COUNT) ?: continue
                val entry = Entry(
                    typed = parts[1],
                    replacement = parts[2].ifEmpty { null },
                    count = count,
                    pending = parts[4].toIntOrNull()?.coerceIn(0, MAX_COUNT) ?: 0,
                    offered = parts[5].toIntOrNull()?.coerceIn(0, MOST_OFFERS) ?: 0,
                    settled = parts[6] == "1",
                )
                when (parts[0]) {
                    "f" -> if (entry.replacement != null) {
                        fixes["${entry.typed.lowercase()}\t${entry.replacement.lowercase()}"] = entry
                    }
                    "p" -> putBacks[entry.typed.lowercase()] = entry.copy(replacement = null)
                }
            }
            return Insights(fixes, putBacks)
        }
    }
}
