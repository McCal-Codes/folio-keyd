package com.mccal.folio.keys

import android.content.SharedPreferences

/**
 * Settings that change with the app being typed in.
 *
 * Autocorrect is right in a message and wrong in a terminal; a number row is worth its height in a spreadsheet and
 * nowhere else. Rather than one answer for every app, an app can have its own answer to the four switches where
 * that difference is felt: suggestions, fixing typos, learning words and the number row. Anything it doesn't
 * answer, it takes from the usual settings.
 *
 * Android tells a keyboard which app a field belongs to, in the field's own description, so this costs no
 * permission. The only thing kept is the app's package name - never anything about the field or what was typed in it -
 * and only for the last [LIMIT] apps, so the list is of apps someone uses rather than every app they ever opened.
 * An app with its own settings stays on the list however long ago it was used, because dropping it would quietly
 * drop its settings too.
 */
class AppProfiles(
    recent: List<String> = emptyList(),
    profiles: Map<String, Profile> = emptyMap(),
) {

    /**
     * One app's answers. [useUsual] on means none of the rest apply; a null means "as usual" for that one switch,
     * so a later change to the usual settings still reaches it.
     */
    data class Profile(
        val useUsual: Boolean = true,
        val suggestions: Boolean? = null,
        val autocorrect: Boolean? = null,
        val learn: Boolean? = null,
        val numberRow: Boolean? = null,
    ) {
        /** The usual settings with this app's answers laid over them. */
        fun over(usual: Settings): Settings = if (useUsual) usual else usual.copy(
            suggestions = suggestions ?: usual.suggestions,
            autocorrect = autocorrect ?: usual.autocorrect,
            learn = learn ?: usual.learn,
            numberRow = numberRow ?: usual.numberRow,
        )

        /** Nothing here that the usual settings wouldn't already say. */
        val isEmpty: Boolean get() = useUsual && suggestions == null && autocorrect == null && learn == null && numberRow == null
    }

    /** What one app's answers change, compared with the usual settings, for the line beside it in the list. */
    enum class Change { SUGGESTIONS_OFF, SUGGESTIONS_ON, FIXING_OFF, FIXING_ON, LEARNING_OFF, LEARNING_ON, NUMBER_ROW_ON, NUMBER_ROW_OFF }

    private val recent = ArrayList(recent)
    private val profiles = LinkedHashMap(profiles.filterValues { !it.isEmpty })

    /** Every app typed in lately, newest first. */
    val recentApps: List<String> get() = recent.toList()

    /** The apps set their own way, in the order they were last typed in. */
    val changed: List<String> get() = recent.filter { profiles[it]?.useUsual == false }

    /** The rest of the list: typed in lately and using the usual settings. */
    val asUsual: List<String> get() = recent.filter { profiles[it]?.useUsual != false }

    fun profile(packageName: String): Profile = profiles[packageName] ?: Profile()

    /** The settings to type with in [packageName]. With no app, or an app with nothing of its own, the usual ones. */
    fun apply(settings: Settings, packageName: String?): Settings =
        packageName?.let { profiles[it] }?.over(settings) ?: settings

    /** How [packageName]'s own settings differ from [usual], in the order the page lists them. */
    fun changes(packageName: String, usual: Settings): List<Change> {
        val profile = profiles[packageName] ?: return emptyList()
        if (profile.useUsual) return emptyList()
        val mine = profile.over(usual)
        return buildList {
            if (mine.suggestions != usual.suggestions) add(if (mine.suggestions) Change.SUGGESTIONS_ON else Change.SUGGESTIONS_OFF)
            // Fixing is judged by what actually happens, which is nothing with the strip off.
            val fixes = Settings.correcting(mine)
            if (fixes != Settings.correcting(usual) && mine.suggestions == usual.suggestions) {
                add(if (fixes) Change.FIXING_ON else Change.FIXING_OFF)
            }
            if (mine.learn != usual.learn) add(if (mine.learn) Change.LEARNING_ON else Change.LEARNING_OFF)
            if (mine.numberRow != usual.numberRow) add(if (mine.numberRow) Change.NUMBER_ROW_ON else Change.NUMBER_ROW_OFF)
        }
    }

    fun set(packageName: String, profile: Profile) {
        if (profile.isEmpty) profiles.remove(packageName) else profiles[packageName] = profile
        if (packageName !in recent) recent.add(0, packageName)
    }

    /**
     * Notes that a field opened in [packageName]. Returns true when the list changed, so the caller only writes when
     * there is something new: a keyboard opens on every field, and most of them are in the app it opened in last.
     *
     * Keyd's own settings screen is never listed: it is the one place its own settings are already the ones in use.
     */
    fun typedIn(packageName: String?, ownPackage: String): Boolean {
        if (packageName.isNullOrEmpty() || packageName == ownPackage || !valid(packageName)) return false
        if (recent.firstOrNull() == packageName) return false
        recent.remove(packageName)
        recent.add(0, packageName)
        trim()
        return true
    }

    /** Gone from the list, and whatever it was set to with it. */
    fun forget(packageName: String) {
        recent.remove(packageName)
        profiles.remove(packageName)
    }

    /** Every app back to the usual settings. The list of apps stays: it is not a setting. */
    fun forgetChanges() = profiles.clear()

    /** The oldest apps go first, except one with settings of its own. */
    private fun trim() {
        var over = recent.size - LIMIT
        val iterator = recent.listIterator(recent.size)
        while (over > 0 && iterator.hasPrevious()) {
            val app = iterator.previous()
            if (profiles[app]?.useUsual == false) continue
            iterator.remove()
            profiles.remove(app)
            over--
        }
    }

    fun encodeRecent(): String = recent.joinToString("\n")

    /** One app a line: `package|usual|suggestions|autocorrect|learn|numberRow`, each 1, 0, or - for "as usual". */
    fun encodeProfiles(): String = profiles.entries.joinToString("\n") { (app, p) ->
        listOf(app, flag(p.useUsual), flag(p.suggestions), flag(p.autocorrect), flag(p.learn), flag(p.numberRow))
            .joinToString("|")
    }

    fun save(prefs: SharedPreferences) {
        prefs.edit().putString(RECENT, encodeRecent()).putString(PROFILES, encodeProfiles()).apply()
    }

    companion object {
        /** How many apps the list remembers. Enough for the ones someone uses in a week. */
        const val LIMIT = 30

        const val RECENT = "appRecents"
        const val PROFILES = "appProfiles"

        private val PACKAGE = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")

        /** A package name and nothing else: whatever reaches this list can only ever be one. */
        fun valid(packageName: String) = packageName.length <= 255 && PACKAGE.matches(packageName)

        private fun flag(value: Boolean?) = when (value) { true -> "1"; false -> "0"; null -> "-" }

        private fun unflag(text: String): Boolean? = when (text) { "1" -> true; "0" -> false; else -> null }

        fun decode(recent: String?, profiles: String?): AppProfiles {
            val apps = recent.orEmpty().split("\n").filter { it.isNotEmpty() && valid(it) }.distinct()
            val answers = LinkedHashMap<String, Profile>()
            for (line in profiles.orEmpty().split("\n")) {
                val parts = line.split("|")
                if (parts.size != 6 || !valid(parts[0])) continue
                answers[parts[0]] = Profile(
                    useUsual = unflag(parts[1]) ?: true,
                    suggestions = unflag(parts[2]),
                    autocorrect = unflag(parts[3]),
                    learn = unflag(parts[4]),
                    numberRow = unflag(parts[5]),
                )
            }
            // An app with settings of its own is always listed, or there would be no way to find and change them.
            val listed = apps + answers.keys.filter { it !in apps }
            return AppProfiles(listed, answers)
        }

        fun load(prefs: SharedPreferences): AppProfiles =
            decode(prefs.getString(RECENT, null), prefs.getString(PROFILES, null))
    }
}
