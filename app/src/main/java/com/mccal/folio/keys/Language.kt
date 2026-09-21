package com.mccal.folio.keys

/**
 * A language the keyboard can be in.
 *
 * A language is not a translation. Nothing on this keyboard is a caption to be swapped out; what changes is the
 * arrangement of the letters, which letters exist at all, what the accents behind each key are, and which words are
 * words. A French keyboard with English letters on it is not a French keyboard.
 *
 * So each one carries its own [rows] - AZERTY really is a different keyboard from QWERTY, not QWERTY with different
 * labels - its own [dictionary], and its own set of accents, which are looked up per language in [Alternates].
 *
 * The digits printed in the corners of the top row stay where they are in every language, because they are digits.
 */
enum class Language(
    /** The BCP 47 tag Android knows this subtype by, and the name of its word list in the assets. */
    val tag: String,
    /** What it is called in its own language, for the space bar and the language list. */
    val ownName: String,
    val rows: List<String>,
) {
    ENGLISH("en-US", "English", listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")),

    /** Spanish adds a letter rather than hiding it behind a long press: ñ sits at the end of the home row. */
    SPANISH("es-ES", "Español", listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")),

    /** French is AZERTY, which moves six letters and makes the home row one longer. */
    FRENCH("fr-FR", "Français", listOf("azertyuiop", "qsdfghjklm", "wxcvbn")),

    /** German is QWERTZ: y and z change places, which matters because z is common in German and y is not. */
    GERMAN("de-DE", "Deutsch", listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")),

    ITALIAN("it-IT", "Italiano", listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")),

    /**
     * Portuguese gets the same treatment as Spanish: ç is a letter people type constantly — não, começar, ação —
     * not an accent worth a long press. Android's own pt-PT layout puts it at the end of the home row too.
     */
    PORTUGUESE("pt-PT", "Português", listOf("qwertyuiop", "asdfghjklç", "zxcvbnm"));

    /** The word list that ships for this language. */
    val dictionary: String get() = "words-$tag.txt"

    companion object {
        /**
         * The language for a subtype's tag, falling back to English.
         *
         * Matched on the language half alone: Android may hand back `fr-CA` or just `fr`, and a French keyboard is
         * the right answer to all of them even when the word list was built from the French of France.
         */
        fun of(tag: String?): Language {
            val wanted = tag?.substringBefore('-')?.lowercase() ?: return ENGLISH
            return entries.firstOrNull { it.tag.substringBefore('-').lowercase() == wanted } ?: ENGLISH
        }
    }
}
