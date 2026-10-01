package com.mccal.folio.keys

import android.content.Context

/** One version's notes from CHANGELOG.md: its number, date, and bullet points grouped by heading (Added, Changed, Fixed). */
data class ReleaseNotes(val version: String, val date: String?, val sections: List<Pair<String, List<String>>>)

/** A changelog line split into its bold title ("**Split for the fold:** …") and the rest; [title] is null for plain lines. */
data class NoteItem(val title: String?, val detail: String)

/**
 * Keyd's What's New, read the same way as Folio's: the changelog is bundled into the app at build time, the newest
 * section is shown once after an update, and every older one is under Version History. Same file format, same
 * parsing, so a line reads the same in both apps.
 */
internal object WhatsNew {
    /** "**Title:** detail" → title and detail; other lines keep their text (with any stray bold markers removed). */
    fun split(item: String): NoteItem {
        val m = Regex("""^\*\*(.+?):?\*\*:?\s*(.*)$""").find(item.trim())
            ?: return NoteItem(null, item.replace("**", "").trim())
        val detail = m.groupValues[2].trim().replaceFirstChar { it.uppercase() }
        return NoteItem(m.groupValues[1].trim().removeSuffix(":"), detail)
    }

    /** Parses Keep a Changelog sections ("## [1.2.0] - date", "### Added", "- item"); stops at non-version headings. */
    fun parse(markdown: String): List<ReleaseNotes> {
        val releases = mutableListOf<ReleaseNotes>()
        var version: String? = null
        var date: String? = null
        val sections = mutableListOf<Pair<String, MutableList<String>>>()
        fun flush() {
            version?.let { v -> releases += ReleaseNotes(v, date, sections.filter { it.second.isNotEmpty() }.map { it.first to it.second.toList() }) }
        }
        markdown.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            val heading = Regex("""^## \[(\d+\.\d+\.\d+[^\]]*)](?:\s*-\s*(.+))?""").find(line)
            when {
                heading != null -> { flush(); version = heading.groupValues[1]; date = heading.groupValues[2].ifBlank { null }; sections.clear() }
                line.startsWith("## ") -> { flush(); version = null; sections.clear() }
                version != null && line.startsWith("### ") -> sections += line.removePrefix("### ").trim() to mutableListOf()
                version != null && line.startsWith("- ") -> {
                    if (sections.isEmpty()) sections += "" to mutableListOf()
                    sections.last().second += line.removePrefix("- ").trim()
                }
            }
        }
        flush()
        return releases
    }

    fun notes(context: Context): List<ReleaseNotes> =
        runCatching { context.assets.open("CHANGELOG.md").bufferedReader().use { parse(it.readText()) } }.getOrDefault(emptyList())

    /** The version this build is, without a pre-release suffix, since the changelog has one section per release. */
    fun releaseVersion(versionName: String) = versionName.substringBefore('-')

    /** True once per version: the first time Settings opens after an update, not on a fresh install. */
    fun shouldShow(context: Context, versionName: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getString(SEEN, null)
        prefs.edit().putString(SEEN, versionName).apply()
        return seen != null && seen != versionName
    }

    /** A settings glyph and tile color for a feature, from words in its title, the way Folio picks its symbols. */
    fun glyph(title: String): Pair<SettingsIcon.Glyph, String> = candidates(title).first()

    /**
     * Glyphs for the features one release shows, in order, no two alike. Each takes the first of its own matches
     * that no earlier feature took, then the first unused one from [SPARE], so two features about typing don't
     * both get the text cursor and read as one thing twice.
     */
    fun glyphs(titles: List<String>): List<Pair<SettingsIcon.Glyph, String>> {
        val used = mutableSetOf<SettingsIcon.Glyph>()
        return titles.map { title ->
            val pick = (candidates(title) + SPARE).firstOrNull { it.first !in used } ?: glyph(title)
            used += pick.first
            pick
        }
    }

    /** Every glyph whose words are in [title], best first, ending with the shift arrow everything falls back on. */
    private fun candidates(title: String): List<Pair<SettingsIcon.Glyph, String>> {
        val t = title.lowercase(java.util.Locale.ROOT)
        return MATCHERS.filter { (words, _) -> words.any { it.containsMatchIn(t) } }.map { it.second } +
            (SettingsIcon.Glyph.KEYS to "#C93400")
    }

    /**
     * True when [word] is in [title] as a word of its own, or with a plural "s" or "es": "tip" is in "Gesture tips"
     * but not in "multiple", and "dev" is not in "device" or "word" in "password".
     */
    internal fun mentions(title: String, word: String): Boolean = matcher(word).containsMatchIn(title.lowercase(java.util.Locale.ROOT))

    private fun matcher(word: String) = Regex("""(?<![\p{L}\p{N}])""" + Regex.escape(word) + """(?:e?s)?(?![\p{L}\p{N}])""")

    private val RULES: List<Pair<List<String>, Pair<SettingsIcon.Glyph, String>>> = listOf(
        listOf("dev") to (SettingsIcon.Glyph.KEYS to "#B44A0C"),
        listOf("what's new", "what’s new") to (SettingsIcon.Glyph.SPARKLES to "#0071E3"),
        // Features that have a picture of their own come first, so no two neighbors share the shift arrow.
        listOf("search") to (SettingsIcon.Glyph.SEARCH to "#0071E3"),
        listOf("choice", "one row", "one tap") to (SettingsIcon.Glyph.CHOICES to "#248A3D"),
        listOf("undo", "undone") to (SettingsIcon.Glyph.UNDO to "#C93400"),
        listOf("emoji") to (SettingsIcon.Glyph.EMOJI to "#5E5CE6"),
        listOf("tip") to (SettingsIcon.Glyph.TIP to "#9A5200"),
        listOf("period", "symbol") to (SettingsIcon.Glyph.SYMBOLS to "#8944AB"),
        listOf("delete", "deleted", "deleting") to (SettingsIcon.Glyph.BACKSPACE to "#D70015"),
        listOf("delay") to (SettingsIcon.Glyph.TIMER to "#0A6E75"),
        listOf("select", "selected", "selecting", "selection") to (SettingsIcon.Glyph.SELECT to "#0071E3"),
        listOf("toolbar", "cursor", "one-handed") to (SettingsIcon.Glyph.MOVE to "#636366"),
        listOf("gesture", "swipe", "flick") to (SettingsIcon.Glyph.HAND to "#C93400"),
        listOf("style", "theme", "color") to (SettingsIcon.Glyph.PALETTE to "#8944AB"),
        listOf("report", "problem") to (SettingsIcon.Glyph.REPORT to "#9A5200"),
        listOf("per-app", "apps") to (SettingsIcon.Glyph.APPS to "#C75C00"),
        listOf("feel", "sound", "vibration") to (SettingsIcon.Glyph.SOUND to "#D70015"),
        listOf("smart", "autocorrect") to (SettingsIcon.Glyph.WAND to "#248A3D"),
        listOf("fix", "fixed", "word", "contraction") to (SettingsIcon.Glyph.TYPING to "#248A3D"),
        listOf("whether", "setting", "status") to (SettingsIcon.Glyph.TYPING to "#248A3D"),
        listOf("new", "about") to (SettingsIcon.Glyph.SHORTCUTS to "#0071E3"),
        listOf("language") to (SettingsIcon.Glyph.LANGUAGES to "#0071E3"),
        listOf("shortcut") to (SettingsIcon.Glyph.SHORTCUTS to "#5E5CE6"),
        listOf("clipboard") to (SettingsIcon.Glyph.CLIPBOARD to "#636366"),
        listOf("permission", "privacy", "password") to (SettingsIcon.Glyph.PRIVACY to "#1B7A33"),
        listOf("letters") to (SettingsIcon.Glyph.LETTERS to "#6E5DC6"),
        // 0.4.1's: the strip's width, taking a word out of it, and the fields that aren't learned from.
        listOf("width") to (SettingsIcon.Glyph.MOVE to "#0A6E75"),
        listOf("remove") to (SettingsIcon.Glyph.TRASH to "#D70015"),
        listOf("learning") to (SettingsIcon.Glyph.NOT_LEARNING to "#636366"),
        listOf("split", "fold", "look") to (SettingsIcon.Glyph.LOOK to "#8944AB"),
        listOf("correct", "corrected", "correction", "typing") to (SettingsIcon.Glyph.TYPING to "#248A3D"),
    )

    /** [RULES] with each word made a whole-word pattern, once. */
    private val MATCHERS = RULES.map { (words, glyph) -> words.map(::matcher) to glyph }

    /** What a feature gets once its own matches are taken: pictures that stand for nothing in particular first. */
    private val SPARE: List<Pair<SettingsIcon.Glyph, String>> = listOf(
        SettingsIcon.Glyph.SPARKLES to "#0071E3", SettingsIcon.Glyph.WAND to "#248A3D",
        SettingsIcon.Glyph.HAND to "#C93400", SettingsIcon.Glyph.PALETTE to "#8944AB",
        SettingsIcon.Glyph.TIMER to "#0A6E75", SettingsIcon.Glyph.MOVE to "#636366",
        SettingsIcon.Glyph.TIP to "#9A5200", SettingsIcon.Glyph.SELECT to "#0071E3",
        SettingsIcon.Glyph.TYPING to "#248A3D", SettingsIcon.Glyph.KEYS to "#C93400",
        SettingsIcon.Glyph.SHORTCUTS to "#5E5CE6", SettingsIcon.Glyph.LOOK to "#8944AB",
        SettingsIcon.Glyph.SOUND to "#D70015", SettingsIcon.Glyph.APPS to "#C75C00",
    ) + SettingsIcon.Glyph.entries.filter { it != SettingsIcon.Glyph.WARNING }.map { it to "#636366" }

    private const val PREFS = "whats_new"
    private const val SEEN = "seen_version"
}
