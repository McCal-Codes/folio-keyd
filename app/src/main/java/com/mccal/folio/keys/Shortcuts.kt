package com.mccal.folio.keys

/**
 * Short things that stand for long things: `omw` for "on my way", `addr` for where you live.
 *
 * Samsung's keyboard calls these text shortcuts and Gboard calls them personal dictionary entries; both work the
 * same way, and both are among the most useful things a keyboard does for anyone who types the same sentence twice.
 *
 * **Where this differs from theirs.** Samsung expands a shortcut by putting the phrase in the suggestion strip for
 * you to tap, which is right, but it will also quietly expand as you type in some places. Here it is only ever
 * offered — first in the strip, where you take it or ignore it — for the same reason nothing else in this keyboard
 * replaces a word on its own. A shortcut is a word you chose to type, and the keyboard has no business deciding you
 * meant something longer.
 *
 * They are also kept out of the way of real words: a shortcut only fires on an exact match of the whole word, so
 * setting one up called `the` costs you nothing, and one called `omw` cannot fire halfway through `omwards`.
 */
class Shortcuts(private val entries: MutableMap<String, String> = LinkedHashMap()) {

    val size: Int get() = entries.size

    /** Every shortcut, in the order they were added, as pairs of what you type and what it stands for. */
    fun all(): List<Pair<String, String>> = entries.entries.map { it.key to it.value }

    /** What this word stands for, or null. Matched whole and without regard to case. */
    fun expand(word: String): String? = entries[word.lowercase()]

    /**
     * Adds one, replacing any with the same trigger.
     *
     * Returns false when there is nothing worth storing: an empty trigger, an empty phrase, or a phrase that is
     * the same as the trigger, which would expand a word into itself.
     */
    fun add(trigger: String, phrase: String): Boolean {
        val key = trigger.trim().lowercase()
        val value = phrase.trim()
        if (key.isEmpty() || value.isEmpty() || key == value.lowercase()) return false
        if (key.any { it.isWhitespace() }) return false   // a trigger is one word, or it can never be typed
        if (entries.size >= LIMIT && key !in entries) return false
        entries[key] = value
        return true
    }

    fun remove(trigger: String) {
        entries.remove(trigger.trim().lowercase())
    }

    /** Stored one per line as `trigger<tab>phrase`; a tab cannot appear in either half. */
    fun encode(): String = entries.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    companion object {
        /** Enough for anyone's set of them, few enough to scan on every keystroke without noticing. */
        const val LIMIT = 200

        fun decode(stored: String?): Shortcuts {
            val entries = LinkedHashMap<String, String>()
            for (line in stored.orEmpty().split("\n")) {
                val tab = line.indexOf('\t')
                if (tab <= 0 || tab == line.lastIndex) continue
                entries[line.substring(0, tab).lowercase()] = line.substring(tab + 1)
            }
            return Shortcuts(entries)
        }
    }
}
