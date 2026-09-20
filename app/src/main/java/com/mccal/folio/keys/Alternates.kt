package com.mccal.folio.keys

/**
 * The letters behind the letters.
 *
 * Holding a key offers the accented forms of it. Which ones, and in what order, is not a matter of taste: these
 * lists are Android's own per-language keyboard data, aggregated across the 28 Latin-script languages it ships
 * (fr, de, es, pt, it, pl, tr, cs, sk, sv, da, nb, fi, ro, hu, hr, sl, lt, lv, et, is, vi, ca, gl, eu, af, az, tl),
 * and ordered by how many of those languages put each character on that key. So the first alternate under "e" is
 * the one the most languages agree on, not the one that came first in the Unicode table.
 *
 * Only characters at least 3 languages use are kept, and at most 8 per key, so the row fits above the key it
 * belongs to on a phone-width screen.
 */
object Alternates {

    /** Base letter to its alternates, commonest first. Lower case; the case of the key is applied later. */
    private val LETTERS: Map<Char, String> = mapOf(
        'a' to "áâäàãæåā",   // á=26 â=26 ä=25 à=24 ã=24 æ=23 å=23 ā=21
        'c' to "ćčç",   // ć=18 č=18 ç=17
        'd' to "ďðđ",   // ď=6 ð=3 đ=3
        'e' to "éèêëęėēě",   // é=24 è=21 ê=21 ë=21 ę=19 ė=19 ē=18 ě=5
        'g' to "ğģ",   // ğ=6 ģ=4
        'i' to "íìïîįīı",   // í=22 ì=21 ï=21 î=20 į=19 ī=19 ı=6
        'k' to "ķ",   // ķ=4
        'l' to "łļĺľ",   // ł=8 ļ=4 ĺ=4 ľ=4
        'n' to "ñńňņ",   // ñ=18 ń=16 ň=5 ņ=4
        'o' to "óôòõöœøō",   // ó=25 ô=25 ò=25 õ=25 ö=24 œ=24 ø=24 ō=20
        'r' to "řŗŕ",   // ř=6 ŗ=4 ŕ=4
        's' to "šßśş",   // š=15 ß=14 ś=14 ş=7
        't' to "ťţ",   // ť=6 ţ=4
        'u' to "úüùūûůűų",   // ú=23 ü=23 ù=23 ū=23 û=22 ů=5 ű=5 ų=4
        'y' to "ýÿ",   // ý=12 ÿ=9
        'z' to "žźż",   // ž=12 ź=9 ż=9
    )

    /**
     * What holding this key should offer, in order, or empty when it offers nothing.
     *
     * [hint] is what the keycap already promises in its corner - the digit on the top row - and it always comes
     * first, so holding a key and letting go without moving still does exactly what the keycap said it would.
     */
    fun forKey(label: String, hint: String?): List<String> {
        val base = label.singleOrNull()?.lowercaseChar()
        val accents = base?.let { LETTERS[it] }.orEmpty()
        if (accents.isEmpty()) return listOfNotNull(hint)
        val upper = label.firstOrNull()?.isUpperCase() == true
        return listOfNotNull(hint) + accents.map { if (upper) it.uppercaseChar().toString() else it.toString() }
    }
}
