package com.mccal.folio.keys

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * What has been copied lately, so it can be put back without leaving the keyboard.
 *
 * A keyboard may read the clipboard while it is the one on screen, which is why this costs no permission: Android
 * stopped letting anything else look years ago. Everything here stays on the phone — there is no code in this app
 * that can send anything anywhere, and a history is only worth having if that stays true.
 *
 * Three rules do most of the work of keeping it honest:
 *
 * - **Nothing from a field that forbids it.** [FieldRules.ephemeral] already means "nothing typed here may be
 *   kept" - a password field, or an app that asked for no personalised learning - and a clip is no different.
 * - **Nothing Android marks sensitive.** A one-time code or a card number arrives with
 *   [ClipDescription.EXTRA_IS_SENSITIVE] set, and an app that bothered to say so deserves to be believed.
 * - **Unpinned clips are forgotten after an hour.** A clipboard history that remembers yesterday is a liability;
 *   pin the ones worth keeping and the rest expire on their own.
 */
internal object Clipboard {
    /** More than this and it stops being a history and starts being a filing cabinet. */
    const val KEPT = 20

    /** Longer than this is a document, not a clip: kept as a prefix so the list stays readable and small. */
    const val MAX_LENGTH = 4_000

    /** An unpinned clip lives an hour. Long enough to be useful in one sitting, short enough to forget. */
    const val KEEP_MILLIS = 60 * 60 * 1000L

    private const val PREFS_KEY = "clipboard"

    data class Clip(val text: String, val at: Long, val pinned: Boolean = false)

    /**
     * The clip on the system clipboard, or null when there is nothing to take: no text, a field that should not be
     * remembered, or content its own app marked sensitive.
     */
    fun readable(manager: ClipboardManager?, rules: FieldRules): String? {
        val clip = manager?.primaryClip ?: return null
        if (rules.ephemeral) return null
        val description = clip.description
        if (description?.getExtras()?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return null
        val text = (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it)?.text }?.toString()
        return text?.takeIf { it.isNotBlank() }?.take(MAX_LENGTH)
    }

    /**
     * [history] with [text] at the front.
     *
     * The same text copied twice is one clip that moved to the front, keeping its pin: copying something again is
     * not a reason to have it twice, and it is a poor reason to lose the pin it had.
     */
    fun remembering(history: List<Clip>, text: String, now: Long): List<Clip> {
        val trimmed = text.take(MAX_LENGTH)
        if (trimmed.isBlank()) return history
        val existing = history.firstOrNull { it.text == trimmed }
        val rest = history.filterNot { it.text == trimmed }
        val clip = Clip(trimmed, now, pinned = existing?.pinned == true)
        return capped(listOf(clip) + rest)
    }

    /** Pinned clips first in the list, then by how recently they arrived: the cap only ever drops the oldest unpinned. */
    private fun capped(clips: List<Clip>): List<Clip> {
        if (clips.size <= KEPT) return clips
        val pinned = clips.filter { it.pinned }
        val loose = clips.filterNot { it.pinned }
        return (pinned + loose).take(maxOf(KEPT, pinned.size))
    }

    /** What is still worth showing: pinned clips forever, the rest for an hour. */
    fun current(history: List<Clip>, now: Long): List<Clip> =
        history.filter { it.pinned || now - it.at < KEEP_MILLIS }

    fun pinning(history: List<Clip>, text: String, pinned: Boolean): List<Clip> =
        history.map { if (it.text == text) it.copy(pinned = pinned) else it }

    fun forgetting(history: List<Clip>, text: String): List<Clip> = history.filterNot { it.text == text }

    /** Everything, pins included: the button someone reaches for when they pasted the wrong thing into the list. */
    /**
     * What Clear leaves: the pinned clips. A pin is someone saying they meant to keep it, so clearing takes only
     * what came and goes on its own - the way Gboard and SwiftKey clear - and needs no "are you sure" to be safe.
     * Turning clipboard history off in Settings still forgets everything, which is what that switch says.
     */
    fun cleared(clips: List<Clip>): List<Clip> = clips.filter { it.pinned }

    fun load(prefs: SharedPreferences, now: Long): List<Clip> = runCatching {
        val array = JSONArray(prefs.getString(PREFS_KEY, null) ?: return emptyList())
        val clips = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val text = item.optString("text").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Clip(text.take(MAX_LENGTH), item.optLong("at"), item.optBoolean("pinned"))
        }
        current(clips, now)
    }.getOrDefault(emptyList())

    fun save(prefs: SharedPreferences, history: List<Clip>) {
        val array = JSONArray()
        for (clip in history) {
            array.put(JSONObject().put("text", clip.text).put("at", clip.at).put("pinned", clip.pinned))
        }
        prefs.edit().putString(PREFS_KEY, array.toString()).apply()
    }

    /** Forgets the lot, including what is on disk: Settings' own button, and what a reset has to reach. */
    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(PREFS_KEY).apply()
    }

    fun manager(context: Context): ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
}
