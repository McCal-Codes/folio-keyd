#!/usr/bin/env python3
"""
Builds the keyboard's word list from its two sources.

    python3 tools/build-dictionary.py <scowl-dir> <en_50k.txt> app/src/main/assets/words-en-US.txt
    python3 tools/build-dictionary.py --spoken-only <es_50k.txt> app/src/main/assets/words-es-ES.txt

**Words** come from SCOWL (http://wordlist.aspell.net/), US English, cut at its "size 40" band. SCOWL's bands are
tiers of commonness rather than frequencies, and its top tier alone holds four thousand words - inside it "the" ties
with "tea" - so they cannot rank anything on their own.

**Scores** come from the OpenSubtitles frequency list (https://github.com/hermitdave/FrequencyWords), log-scaled.
That list is conversational English, which is what people type on a phone.

The frequency list also **adds** words: anything common in speech that a formal dictionary leaves out. That is not a
nicety. A word missing from the dictionary is a word autocorrect will quietly replace with something else, and the
gaps are exactly the words nobody wants replaced - "arse", "bollocks", "wanker" were all missing, and all three
would have been turned into something the person did not type.

Both licences are permissive and both notices ship beside the list as `words-en-us-COPYING.txt`.

**Other languages have only the second source.** SCOWL is English, and the open word lists for most other
languages are GPL, which this app cannot use. So `--spoken-only` builds a list from the frequency data alone.

That is a real difference in kind and worth being honest about: an English word is in the list because a
dictionary says it is a word, while a Spanish one is in the list because it was said often in subtitles. The
frequency list contains misspellings, and some of them are said often. The cut is therefore tighter for these
languages, and anything below it is dropped rather than kept at a low score - a rare real word costs someone one
correction, whereas a common misspelling promoted into a dictionary corrupts every suggestion near it.
"""
import math
import os
import re
import sys

# The frequency list has had its apostrophes stripped, so "didn't" arrives as "didn". These stems are not words and
# must not become dictionary entries. English has a closed set of them, so this is a list rather than a guess.
NOT_WORDS = {
    "didn", "doesn", "isn", "wasn", "wouldn", "couldn", "shouldn", "aren", "weren", "hasn", "hadn", "haven",
    "ain", "mustn", "needn", "daren", "shan", "oughtn", "mightn", "usedn",
}

# Letters of any alphabet, not just the twenty-six English happens to use.
WORD = re.compile("^[^\\W\\d_][^\\W\\d_']*[']?[^\\W\\d_]*$", re.UNICODE)
SCOWL_BANDS = [10, 20, 35, 40]
SCOWL_KINDS = [
    "english-words", "american-words", "english-contractions", "american-contractions",
    "english-upper", "american-upper",
]
SPOKEN_CUTOFF = 20_000        # past this the frequency list is mostly noise and misspellings

# For a language with no dictionary to check against, the tail is riskier: nothing rules out a common misspelling.
SPOKEN_ONLY_CUTOFF = 30_000
SPOKEN_ONLY_SHORTEST = 2      # "yo", "tu", "je", "il" are words; single letters mostly are not


def scowl_words(final_dir):
    """Every US English word SCOWL lists up to the size-40 band, with the band it first appeared in."""
    found = {}
    for band, size in enumerate(SCOWL_BANDS):
        for kind in SCOWL_KINDS:
            path = os.path.join(final_dir, f"{kind}.{size}")
            if not os.path.exists(path):
                continue
            for line in open(path, encoding="iso-8859-1"):
                word = line.strip()
                if word and WORD.match(word) and len(word) <= 20 and word not in found:
                    found[word] = band
    return found


def frequencies(path):
    """Word to its position in the frequency list; position 1 is the commonest word in the language."""
    positions = {}
    for position, line in enumerate(open(path, encoding="utf-8"), start=1):
        word = line.split(" ")[0].strip().lower()
        if word and word not in positions:
            positions[word] = position
    return positions


def score(position, band):
    """0 is the commonest word there is. Anything with no frequency data sits below everything that has some."""
    if position:
        return min(49, int(10 * math.log10(position)))
    return min(99, 60 + band * 10)


def position_of(word, positions):
    """
    Where a word sits in the frequency list, looking past the apostrophe problem.

    The list has had its apostrophes stripped, so "don't" is not in it under that spelling - it was counted as
    "don". Without this, every contraction in English scores as a word nobody has ever used, and "don't", "can't"
    and "I'm" are never suggested and never used to fix anything. The stem's count came from the contraction, so
    the stem's position is the contraction's, give or take the handful of times the stem stands alone.
    """
    direct = positions.get(word)
    if direct:
        return direct
    if "'" in word:
        stem = word.split("'")[0]
        if stem:            # "I'm" has a one-letter stem, and is not a rare word
            return positions.get(stem)
    return None


def spoken_only(frequency_file, destination):
    """
    A word list built from frequency data alone, for a language with no usable dictionary.

    Everything here earns its place by being said, which is the best evidence available and not as good as a
    dictionary. Letters only, so numbers, timestamps and subtitle artefacts do not become words.
    """
    positions = frequencies(frequency_file)
    entries = {}
    for word, position in positions.items():
        if position > SPOKEN_ONLY_CUTOFF:
            continue
        if not WORD.match(word) or not (SPOKEN_ONLY_SHORTEST <= len(word) <= 20):
            continue
        entries[word] = score(position, 0)
    with open(destination, "w", encoding="utf-8") as out:
        for word, value in sorted(entries.items(), key=lambda kv: kv[0].lower()):
            out.write(f"{word}:{value:02d}\n")
    print(f"{len(entries)} words from the spoken list alone -> {destination} "
          f"({os.path.getsize(destination)} bytes)")


def main(final_dir, frequency_file, destination):
    words = scowl_words(final_dir)
    positions = frequencies(frequency_file)
    entries = {word: score(position_of(word.lower(), positions), band) for word, band in words.items()}

    spoken = 0
    have = {word.lower() for word in entries}
    for word, position in positions.items():
        if position > SPOKEN_CUTOFF or word in NOT_WORDS or word in have:
            continue
        if not WORD.match(word) or not (2 < len(word) <= 20):
            continue
        entries[word] = score(position, 0)
        spoken += 1

    with open(destination, "w", encoding="utf-8") as out:
        for word, value in sorted(entries.items(), key=lambda kv: kv[0].lower()):
            out.write(f"{word}:{value:02d}\n")
    print(f"{len(words)} from SCOWL, {spoken} more that only the spoken list had, {len(entries)} in total")
    print(f"{os.path.getsize(destination)} bytes -> {destination}")


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "--spoken-only":
        spoken_only(sys.argv[2], sys.argv[3])
    elif len(sys.argv) == 4:
        main(*sys.argv[1:])
    else:
        sys.exit(__doc__)
