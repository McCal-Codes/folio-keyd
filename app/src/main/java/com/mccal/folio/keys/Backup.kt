package com.mccal.folio.keys

import org.json.JSONException
import org.json.JSONObject

/**
 * Learned words, text shortcuts and the words turned down in the strip in one file, for moving to a new phone.
 *
 * Keyd has no account and no Internet, so there is nowhere for these to sync to. A file the person saves and opens
 * themselves, through Android's own file picker, needs neither a permission nor a server, and they can see where it
 * went.
 *
 * Importing only ever adds. A word already here keeps whichever count is higher, a shortcut already here keeps its
 * own phrase, and nothing missing from the file is removed: bringing an old phone's words across should never cost
 * the words typed on the new one since.
 */
internal object Backup {
    const val FORMAT = "keyd-backup"
    const val VERSION = 1

    /** What an import did, or why it did nothing. */
    sealed class Result {
        /** The merged stores, ready to save, and how many words and shortcuts are new. */
        data class Added(
            val learned: Learned,
            val shortcuts: Shortcuts,
            val words: Int,
            val shortcutsAdded: Int,
            val never: NeverSuggest = NeverSuggest(),
        ) : Result()

        data class Rejected(val reason: Reason) : Result()
    }

    enum class Reason {
        /** Not JSON, or JSON that is not a Keyd backup. */
        NOT_A_BACKUP,

        /** A backup from a later Keyd, in a shape this one does not know. */
        NEWER,
    }

    fun export(learned: Learned, shortcuts: Shortcuts, never: NeverSuggest = NeverSuggest()): String {
        val words = JSONObject()
        learned.all().forEach { words.put(it, learned.count(it)) }
        val phrases = JSONObject()
        shortcuts.all().forEach { (trigger, phrase) -> phrases.put(trigger, phrase) }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("words", words)
            .put("shortcuts", phrases)
            // Added in 0.4.1 without a new version: a Keyd before it reads the file as before and skips this.
            .put("neverSuggest", org.json.JSONArray(never.all()))
            .toString(2)
    }

    fun merge(json: String, learned: Learned, shortcuts: Shortcuts, never: NeverSuggest = NeverSuggest()): Result {
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            return Result.Rejected(Reason.NOT_A_BACKUP)
        }
        if (root.optString("format") != FORMAT) return Result.Rejected(Reason.NOT_A_BACKUP)
        val version = root.optInt("version", 0)
        if (version < 1) return Result.Rejected(Reason.NOT_A_BACKUP)
        if (version > VERSION) return Result.Rejected(Reason.NEWER)

        // Learned keeps its counts to itself, so the merge goes through its stored form: one `word:count` a line.
        val counts = LinkedHashMap<String, Int>()
        learned.all().forEach { counts[it] = learned.count(it) }
        var newWords = 0
        root.optJSONObject("words")?.let { words ->
            for (word in words.keys()) {
                val count = words.optInt(word, 0).coerceIn(0, Learned.MAX_COUNT)
                // The same test typing applies, so a file cannot teach Keyd a word it would never have learned.
                if (count < 1 || !Learned.worthLearning(word, known = false, nearMiss = false)) continue
                val existing = counts[word]
                if (existing != null) {
                    counts[word] = maxOf(existing, count)
                } else if (counts.size < Learned.LIMIT) {
                    counts[word] = count
                    newWords++
                }
            }
        }
        val mergedWords = Learned.decode(counts.entries.joinToString("\n") { "${it.key}:${it.value}" })

        val mergedShortcuts = Shortcuts.decode(shortcuts.encode())
        var newShortcuts = 0
        root.optJSONObject("shortcuts")?.let { phrases ->
            for (trigger in phrases.keys()) {
                val phrase = phrases.optString(trigger)
                // Stored one a line with a tab between the halves, so neither may hold one.
                if (listOf(trigger, phrase).any { it.contains('\n') || it.contains('\t') }) continue
                if (mergedShortcuts.expand(trigger.trim()) != null) continue   // theirs stays theirs
                if (mergedShortcuts.add(trigger, phrase)) newShortcuts++
            }
        }
        // Only ever added to, like the rest: a word turned down on either phone stays turned down.
        val mergedNever = NeverSuggest.decode(never.encode())
        root.optJSONArray("neverSuggest")?.let { list ->
            for (index in 0 until list.length()) {
                val word = list.optString(index)
                // Spaces are fine: the strip offers phrases like "on my way", and those can be turned down too.
                if (word.length <= MAX_WORD && word.none { it.isWhitespace() && it != ' ' }) mergedNever.add(word)
            }
        }
        return Result.Added(mergedWords, mergedShortcuts, newWords, newShortcuts, mergedNever)
    }

    /** Longer than any word the strip would offer: anything past it came from somewhere else. */
    private const val MAX_WORD = 64

    /** The name the file picker suggests: `keyd-backup-2026-09-26.json`. */
    fun fileName(date: java.time.LocalDate): String = "keyd-backup-$date.json"
}
