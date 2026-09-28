package com.mccal.folio.keys

/**
 * The apostrophe people leave out, put back.
 *
 * Nobody types the apostrophe in "don't" on a phone, and every keyboard since the first one has put it in for them.
 * The frequency lists make it harder than it should be: they are built from subtitles with the apostrophes stripped,
 * so "dont", "youre" and "thats" arrive as words in their own right, and a word the dictionary knows is a word the
 * corrector leaves alone. So this is a table, written by hand and closed, rather than a rule.
 *
 * Two kinds of entry, because not every missing apostrophe is a mistake:
 *
 * * **Sure** ones are not words without it. Nobody means "dont" or "im", so these are fixed as the word is finished,
 *   exactly as a typo is: only with autocorrect on, and one backspace away from being put back.
 * * **Maybe** ones are real words either way - "its", "were", "well", "lets", "ill". Those are only ever offered in
 *   the strip, where tapping is a choice. The one exception is "wont", which almost nobody means: see [fix].
 *
 * English has the most. French has a handful of elisions that are never words without the apostrophe ("cest",
 * "jai"). Spanish, Portuguese and German barely use the apostrophe, and in Italian the common cases ("po", "ce",
 * "dell") are real words or real prefixes, so those four have none: guessing there would be the keyboard arguing.
 */
object Contractions {

    class Table(val sure: Map<String, String>, val maybe: Map<String, String>) {
        /** Every word this table would rather see with its apostrophe, so the strip stops offering the bare one. */
        fun replaces(word: String) = word in sure || word in maybe
    }

    private val ENGLISH_SURE = mapOf(
        "dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't",
        "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't", "aint" to "ain't",
        "cant" to "can't", "couldnt" to "couldn't", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't",
        "mustnt" to "mustn't", "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't",
        "couldve" to "could've", "wouldve" to "would've", "shouldve" to "should've", "mightve" to "might've",
        "im" to "I'm", "ive" to "I've",
        "youre" to "you're", "youve" to "you've", "youll" to "you'll", "youd" to "you'd",
        "theyre" to "they're", "theyve" to "they've", "theyll" to "they'll", "theyd" to "they'd",
        "weve" to "we've", "hes" to "he's", "shes" to "she's", "hed" to "he'd",
        "thats" to "that's", "whats" to "what's", "theres" to "there's", "heres" to "here's",
        "wheres" to "where's", "whos" to "who's", "thatll" to "that'll", "itll" to "it'll",
        "yall" to "y'all", "alot" to "a lot",
    )

    private val ENGLISH_MAYBE = mapOf(
        "wont" to "won't", "its" to "it's", "lets" to "let's", "ill" to "I'll", "id" to "I'd",
        "were" to "we're", "well" to "we'll", "hell" to "he'll", "shell" to "she'll", "wed" to "we'd",
        "shed" to "she'd", "hows" to "how's",
    )

    private val FRENCH_SURE = mapOf(
        "cest" to "c'est", "cetait" to "c'était", "jai" to "j'ai", "jetais" to "j'étais", "jaime" to "j'aime",
        "daccord" to "d'accord", "aujourdhui" to "aujourd'hui", "quil" to "qu'il", "quon" to "qu'on",
    )

    private val NONE = Table(emptyMap(), emptyMap())
    private val ENGLISH = Table(ENGLISH_SURE, ENGLISH_MAYBE)
    private val FRENCH = Table(FRENCH_SURE, emptyMap())

    fun of(language: Language): Table = when (language) {
        Language.ENGLISH -> ENGLISH
        Language.FRENCH -> FRENCH
        else -> NONE
    }

    /**
     * "wont" is a word - "as was his wont" - but only after a handful of words, and everywhere else it is "won't"
     * with the apostrophe missed. So it is fixed unless the word before it is one of those.
     */
    private val WONT_IS_MEANT_AFTER = setOf(
        "was", "were", "is", "are", "as", "be", "been", "his", "her", "their", "its", "my", "your", "our", "the",
    )

    /** The contraction to put in unasked for [typed], or null. [previous] is the word before, lowercase, or "". */
    fun fix(typed: String, table: Table, previous: String = ""): String? {
        val lower = typed.lowercase()
        val found = table.sure[lower]
            ?: table.maybe[lower]?.takeIf { lower == "wont" && previous !in WONT_IS_MEANT_AFTER }
            ?: return null
        return wearCase(typed, found)
    }

    /** What to offer in the strip for [typed], sure or not, or null. */
    fun offer(typed: String, table: Table): String? {
        val lower = typed.lowercase()
        val found = table.sure[lower] ?: table.maybe[lower] ?: return null
        return wearCase(typed, found)
    }

    /**
     * "i" on its own is "I", and so is the start of "i'm", "i've", "i'll" and "i'd".
     *
     * English only, and not a correction: it is capitalization, so it follows the capitals setting rather than the
     * autocorrect one. Null when there is nothing to change.
     */
    fun capitalI(typed: String, language: Language): String? {
        if (language != Language.ENGLISH) return null
        return when (typed) {
            "i", "i'm", "i've", "i'll", "i'd" -> "I" + typed.substring(1)
            else -> null
        }
    }

    /** "Dont" becomes "Don't", "DONT" becomes "DON'T", and "im" still becomes "I'm". */
    private fun wearCase(typed: String, found: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> found.uppercase()
        typed.first().isUpperCase() -> found.replaceFirstChar { it.uppercaseChar() }
        else -> found
    }
}
