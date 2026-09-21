package com.mccal.folio.keys

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * The keys, and the rules the field sets for them.
 *
 * A layout is plain data: rows of [Key]. Nothing here knows how to draw or what a press does, so the same rows can be
 * measured for a phone, a split half or a one-handed keyboard without a second copy.
 */
enum class KeyKind {
    CHAR, SHIFT, BACKSPACE, LAYER, SPACE, ACTION, GLOBE, HIDE, SELECT_ALL, COPY, PASTE, CLIPBOARD, EMOJI,
    SUGGESTION,
}

data class Key(
    val label: String,
    val kind: KeyKind = KeyKind.CHAR,
    /** Share of the row's width, relative to a letter key. */
    val weight: Float = 1f,
    val output: String = label,
    /** Printed small in the key's corner: the long-press alternate, the way AOSP and HeliBoard do it. */
    val hint: String? = null,
)

typealias Row = List<Key>

enum class Layer { LETTERS, NUMBERS, SYMBOLS }

enum class FieldKind { TEXT, EMAIL, URL, NUMBER, PHONE, SEARCH }

/**
 * What the field being edited asks of the keyboard. Android hands this over in [EditorInfo]; a keyboard that ignores
 * it is the reason people type an email address with a keyboard that has no @.
 */
data class FieldRules(
    val kind: FieldKind = FieldKind.TEXT,
    val actionLabel: String = "return",
    /** Password fields: no learning, no suggestions, no key preview. Not a setting — the field decides. */
    val password: Boolean = false,
    /** The app asked for nothing to be remembered from this field (IME_FLAG_NO_PERSONALIZED_LEARNING). */
    val noLearning: Boolean = false,
    val multiline: Boolean = false,
) {
    /** True when nothing typed here may be kept, whatever the user's own settings say. */
    val ephemeral: Boolean get() = password || noLearning
}

/**
 * What the space bar says: the language, when the bar is wide enough to read it, and "space" when it isn't.
 *
 * Keyd types in six languages and, until now, nothing on screen said which one was on — the globe key switches
 * them and the only way to find out was to type a word and see what got corrected. Gboard and Samsung both put the
 * language here, which is where people already look for it.
 *
 * Narrow bars keep the plain word rather than shrinking "Português" to something unreadable: a label nobody can read
 * is worse than a label that says less.
 */
internal fun spaceLabel(widthDp: Float, language: Language, plain: String): String =
    if (widthDp >= SPACE_NAME_MIN_DP && language.ownName.length <= SPACE_NAME_MAX_CHARS) language.ownName else plain

/** Below this the language name crowds the keys either side of it. */
internal const val SPACE_NAME_MIN_DP = 108f

/** A name longer than this is a language Keyd doesn't ship yet; the plain word is the safe answer. */
internal const val SPACE_NAME_MAX_CHARS = 12

object Layouts {
    /** English's arrangement. Every other language brings its own, in [Language]. */
    private val LETTER_ROWS = Language.ENGLISH.rows
    private val NUMBER_ROWS = listOf("1234567890", "-/:;()$&@\"", ".,?!'")
    private val SYMBOL_ROWS = listOf("[]{}#%^*+=", "_\\|~<>€£¥•", ".,?!'")

    /** The number a top-row key gives on a long press or a downward flick, printed in its corner. */
    private const val CORNER_DIGITS = "1234567890"

    /**
     * A row of digits above the letters, for people who would rather not go through the 123 key.
     *
     * The digits carry no corner hint, because the hint on a letter key is the digit it gives when held - and a
     * digit key holding a digit would be telling you something you can already see.
     */
    private val DIGITS: Row = "1234567890".map { Key(it.toString()) }

    fun rows(
        layer: Layer,
        shifted: Boolean,
        rules: FieldRules,
        numberRow: Boolean = false,
        language: Language = Language.ENGLISH,
    ): List<Row> {
        if (rules.kind == FieldKind.NUMBER || rules.kind == FieldKind.PHONE) return numberPad(rules)
        val source = when (layer) {
            Layer.LETTERS -> language.rows
            Layer.NUMBERS -> NUMBER_ROWS
            Layer.SYMBOLS -> SYMBOL_ROWS
        }
        val letters = layer == Layer.LETTERS
        val rows = source.mapIndexed { index, line ->
            val keys = line.mapIndexed { position, char ->
                val shown = if (letters && shifted) char.uppercaseChar() else char
                // The digit in the corner belongs to the position on the top row, not to the letter: on AZERTY
                // the first key is "a" and still gives 1, because that is where 1 is.
                val hint = if (letters && index == 0) CORNER_DIGITS.getOrNull(position) else null
                Key(shown.toString(), hint = hint?.toString())
            }
            if (index < source.lastIndex) keys else bottomOfLetters(keys, layer)
        }
        val top = if (numberRow && letters) listOf(DIGITS) else emptyList()
        return top + rows + listOf(spaceRow(layer, rules))
    }

    /** The third row carries shift and backspace at its ends, wider than a letter so they're easy to hit. */
    private fun bottomOfLetters(keys: List<Key>, layer: Layer): Row {
        val left = when (layer) {
            Layer.LETTERS -> Key("⇧", KeyKind.SHIFT, weight = 1.5f)
            Layer.NUMBERS -> Key("#+=", KeyKind.LAYER, weight = 1.5f, output = Layer.SYMBOLS.name)
            Layer.SYMBOLS -> Key("123", KeyKind.LAYER, weight = 1.5f, output = Layer.NUMBERS.name)
        }
        return listOf(left) + keys + Key("⌫", KeyKind.BACKSPACE, weight = 1.5f)
    }

    /**
     * The bottom row is where the field shows: an email keyboard has @ and .com, a web address has / and .com, and the
     * action key says what the app asked for rather than always "return".
     */
    private fun spaceRow(layer: Layer, rules: FieldRules): Row {
        val toLetters = layer != Layer.LETTERS
        val layerKey = Key(
            if (toLetters) "ABC" else "123", KeyKind.LAYER, weight = 1.5f,
            output = (if (toLetters) Layer.LETTERS else Layer.NUMBERS).name,
        )
        // The symbol layers already have . and , in their own row; repeating them here would put the same key on
        // screen twice. Punctuation keeps a full key's width, so it stays above the 24 dp a fingertip needs.
        val punctuation = layer == Layer.LETTERS
        val extras = when {
            // The numbers and symbols layers already carry @ and /, so the field's own keys belong to the letters.
            !punctuation -> emptyList()
            rules.kind == FieldKind.EMAIL -> listOf(Key("@"), Key(".com", weight = 1.4f))
            rules.kind == FieldKind.URL -> listOf(Key("/"), Key(".com", weight = 1.4f))
            else -> listOf(Key(","))
        }
        val tail = when {
            rules.kind == FieldKind.EMAIL || rules.kind == FieldKind.URL -> emptyList()
            punctuation -> listOf(Key("."))
            else -> emptyList()
        }
        return listOf(layerKey, Key("🌐", KeyKind.GLOBE)) + extras.take(1) +
            listOf(Key("space", KeyKind.SPACE, weight = 4.2f, output = " ")) +
            extras.drop(1) + tail + Key(rules.actionLabel, KeyKind.ACTION, weight = 1.5f)
    }

    /** A number field has no letters to offer, so the pad carries no key that would do nothing if pressed. */
    private fun numberPad(rules: FieldRules): List<Row> = listOf(
        listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"),
    ).map { row -> row.map { Key(it) } } + listOf(
        listOf(Key(","), Key("0"), Key("⌫", KeyKind.BACKSPACE)),
        listOf(Key(rules.actionLabel, KeyKind.ACTION)),
    )

    /**
     * The same rows, cut into two halves for a wide window.
     *
     * A phone keyboard stretched across an unfolded Fold gives 90 dp keys that no thumb can reach: Samsung splits,
     * Gboard splits, every keyboard that takes foldables seriously splits. Each row is divided near its middle by
     * weight, and a space bar that would straddle the gap becomes one space bar per half.
     */
    fun split(rows: List<Row>): Pair<List<Row>, List<Row>> {
        val left = ArrayList<Row>()
        val right = ArrayList<Row>()
        for (row in rows) {
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            val l = ArrayList<Key>()
            val r = ArrayList<Key>()
            var used = 0f
            for (key in row) {
                val straddles = key.kind == KeyKind.SPACE && used < total / 2 && used + key.weight > total / 2
                when {
                    // A space bar across the gap becomes one on each side, so both thumbs have one.
                    straddles -> {
                        l += key.copy(weight = key.weight / 2)
                        r += key.copy(weight = key.weight / 2)
                    }
                    used + key.weight / 2 <= total / 2 -> l += key
                    else -> r += key
                }
                used += key.weight
            }
            left += if (l.isEmpty()) listOf(row.first()) else l
            right += if (r.isEmpty()) listOf(row.last()) else r
        }
        return left to right
    }

    /** Reads the field. Everything the keyboard changes for a field is decided here, in one place. */
    fun rulesFor(info: EditorInfo?): FieldRules {
        if (info == null) return FieldRules()
        return rules(info.inputType, info.imeOptions, info.actionLabel?.toString())
    }

    /**
     * The same reading, from the three numbers Android actually gives us. Kept separate from [EditorInfo] so the rules
     * can be tested without a phone — this is the logic that decides whether a password field behaves itself.
     */
    fun rules(inputType: Int, imeOptions: Int, actionLabel: String? = null): FieldRules {
        val klass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val password = when (klass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        val kind = when {
            klass == InputType.TYPE_CLASS_NUMBER -> FieldKind.NUMBER
            klass == InputType.TYPE_CLASS_PHONE -> FieldKind.PHONE
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL
            variation == InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URL
            imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEARCH -> FieldKind.SEARCH
            else -> FieldKind.TEXT
        }
        val multiline = klass == InputType.TYPE_CLASS_TEXT && inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        return FieldRules(
            kind = kind,
            actionLabel = actionLabel?.takeIf { it.isNotBlank() } ?: actionFor(imeOptions, multiline),
            password = password,
            noLearning = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0,
            multiline = multiline,
        )
    }

    /** The action the app asked for, and "return" when it asked for nothing or the field takes more than one line. */
    private fun actionFor(imeOptions: Int, multiline: Boolean): String {
        if (multiline) return "return"
        return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            else -> "return"
        }
    }
}
