package com.mccal.folio.keys

/** Shift's three states: off, on for one letter, or locked until it's tapped again. */
enum class Shift { OFF, ONCE, LOCKED }

/** Editing that works on text rather than on views, kept here so it can be tested without a phone. */
object Words {

    /**
     * How many characters a swipe on the backspace should take: the run of spaces before the cursor, and then the word
     * before that. Swiping at the end of "hello world " removes "world " and leaves "hello ".
     */
    fun charsToRemoveForWord(before: CharSequence): Int {
        if (before.isEmpty()) return 0
        var cut = before.length
        while (cut > 0 && before[cut - 1].isWhitespace()) cut--
        while (cut > 0 && !before[cut - 1].isWhitespace()) cut--
        return before.length - cut
    }
}

/** What a key is read as by a screen reader. Two keys must never read the same, or they can't be told apart. */
object Spoken {
    fun name(key: Key, shift: Shift): String = when (key.kind) {
        KeyKind.BACKSPACE -> "Backspace, swipe left to delete a word"
        KeyKind.SHIFT -> when (shift) {
            Shift.OFF -> "Shift"
            Shift.ONCE -> "Shift, on for one letter"
            Shift.LOCKED -> "Caps lock"
        }
        KeyKind.SPACE -> "Space, swipe to move the cursor"
        KeyKind.GLOBE -> "Switch keyboard"
        KeyKind.LAYER -> when (key.output) {
            Layer.LETTERS.name -> "Letters"
            Layer.SYMBOLS.name -> "More symbols"
            else -> "Numbers and symbols"
        }
        KeyKind.HIDE -> "Hide the keyboard"
        KeyKind.SELECT_ALL -> "Select all"
        KeyKind.COPY -> "Copy"
        KeyKind.PASTE -> "Paste"
        KeyKind.ACTION, KeyKind.CHAR -> key.label
    }
}
