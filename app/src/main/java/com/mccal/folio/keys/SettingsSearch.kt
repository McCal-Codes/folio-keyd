package com.mccal.folio.keys

/**
 * One row of Settings as search sees it: what the row says, the grey line under it, the note under its group and
 * the heading over it, and the page it is on.
 *
 * The list is never written out by hand. Settings draws each page with a flag set that makes every row helper note
 * its row here instead of building it, so a row added to a page is searchable the moment it is on the page.
 */
internal data class SearchEntry(
    val title: String,
    val subtitle: String?,
    val footer: String?,
    val header: String?,
    /** The page's name in Settings' own list, to open it by. */
    val page: String,
    /** The page's title, shown under a result so it can be found without knowing which group it was put in. */
    val where: String,
    /**
     * What the row does, in words search matches but the page does not show: the explanation a row had before the
     * redesign moved it off screen. Someone searching "fold" or "squiggle" still finds the row that does it.
     */
    val keywords: String? = null,
)

internal object SettingsSearch {

    /**
     * Every row whose title, subtitle, note, heading or hidden keywords hold what was typed, anywhere in it and in any case, rows
     * whose own title matches first. Samsung's settings search works this way, and it is what someone typing "vib"
     * expects: Vibration, wherever it lives.
     */
    fun find(entries: List<SearchEntry>, query: String): List<SearchEntry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        fun has(text: String?) = text?.contains(q, ignoreCase = true) == true
        val hits = entries.filter { has(it.title) || has(it.subtitle) || has(it.footer) || has(it.header) || has(it.keywords) }
        return hits.sortedBy { if (has(it.title)) 0 else 1 }
    }
}
