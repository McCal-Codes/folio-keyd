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

    /** A settings glyph and tile colour for a feature, from words in its title, the way Folio picks its symbols. */
    fun glyph(title: String): Pair<SettingsIcon.Glyph, String> {
        val t = title.lowercase(java.util.Locale.ROOT)
        return when {
            "dev" in t -> SettingsIcon.Glyph.KEYS to "#B44A0C"
            "whether" in t || "setting" in t || "status" in t -> SettingsIcon.Glyph.TYPING to "#248A3D"
            "new" in t || "about" in t -> SettingsIcon.Glyph.SHORTCUTS to "#0071E3"
            "language" in t -> SettingsIcon.Glyph.LANGUAGES to "#0071E3"
            "shortcut" in t || "emoji" in t -> SettingsIcon.Glyph.SHORTCUTS to "#5E5CE6"
            "clipboard" in t -> SettingsIcon.Glyph.CLIPBOARD to "#636366"
            "permission" in t || "privacy" in t -> SettingsIcon.Glyph.PRIVACY to "#1B7A33"
            "split" in t || "fold" in t || "look" in t -> SettingsIcon.Glyph.LOOK to "#8944AB"
            "correct" in t || "typing" in t -> SettingsIcon.Glyph.TYPING to "#248A3D"
            else -> SettingsIcon.Glyph.KEYS to "#C93400"
        }
    }

    private const val PREFS = "whats_new"
    private const val SEEN = "seen_version"
}
