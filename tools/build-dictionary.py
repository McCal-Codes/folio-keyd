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

SCOWL is permissive; FrequencyWords' data is CC BY-SA 4.0 (its code is MIT, its content is not), so the lists this
builds are shared under CC BY-SA 4.0. Both notices ship beside the list as `words-COPYING.txt`.

**Other languages have only the second source.** SCOWL is English, and the open word lists for most other
languages are GPL, which this app cannot use. So `--spoken-only` builds a list from the frequency data alone.

That is a real difference in kind and worth being honest about: an English word is in the list because a
dictionary says it is a word, while a Spanish one is in the list because it was said often in subtitles. The
frequency list contains misspellings, and some of them are said often. The cut is therefore tighter for these
languages, and anything below it is dropped rather than kept at a low score - a rare real word costs someone one
correction, whereas a common misspelling promoted into a dictionary corrupts every suggestion near it.
"""
import bisect
import math
import os
import re
import statistics
import sys
import unicodedata

# The frequency list has had its apostrophes stripped, so "didn't" arrives as "didn". These stems are not words and
# must not become dictionary entries. English has a closed set of them, so this is a list rather than a guess.
NOT_WORDS = {
    "didn", "doesn", "isn", "wasn", "wouldn", "couldn", "shouldn", "aren", "weren", "hasn", "hadn", "haven",
    "ain", "mustn", "needn", "daren", "shan", "oughtn", "mightn", "usedn",
}

# Things the spoken list counts as words that are not: two words run together by whoever typed the subtitle
# ("ofthe", "foryou"), and scanning slips where a lowercase l was read as an i ("iike", "couid"). Each one in the list
# is worse than missing: a word the dictionary knows is a word autocorrect leaves alone, and a word it ranks common
# is one it offers, so "couid" was the answer to "could" typed with one key off. Closed, like NOT_WORDS, and read
# before adding anything here: "nevermind", "whatnot" and "daycare" look like the same thing and are not.
JUNK = {
    "ofthe", "forthe", "ifyou", "foryou", "thankyou", "areyou", "doyou", "ofyou", "ifwe", "everytime", "allright",
    "iike", "iot", "iet", "iove", "couid", "iife", "iast", "iong", "ieast", "ifl", "nder",
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

# How much commoner the accented form must be before the bare one is only a typo of it: ten points is ten times.
LOST_ACCENT_GAP = 10

# The score for a word the keyboard should know but never offer: see mark_lost_accents. Nothing else scores 98.
KNOWN_ONLY = 98


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


def counts_of(path):
    """Word to how many times it was said. The same list as [frequencies], with the numbers kept."""
    counts = {}
    for line in open(path, encoding="utf-8"):
        parts = line.split(" ")
        word = parts[0].strip().lower()
        if word and word not in counts and len(parts) > 1:
            counts[word] = int(parts[1])
    return counts


def score(position, band):
    """0 is the commonest word there is. Anything with no frequency data sits below everything that has some."""
    if position:
        return min(49, int(10 * math.log10(position)))
    return min(99, 60 + band * 10)


def position_of(word, positions, apostrophes=None):
    """
    Where a word sits in the frequency list, looking past the apostrophe problem.

    The list has had its apostrophes stripped, so "don't" is not in it under that spelling - it was counted as
    "don" and "'t". Without this, every contraction in English scores as a word nobody has ever used, and "don't",
    "can't" and "I'm" are never suggested and never used to fix anything.

    [apostrophes] is where [apostrophe_positions] puts each contraction by its own count. Anything it does not
    cover falls back on its stem: "abbey's" is as common as "abbey", which is a guess, but not a harmful one.
    """
    direct = positions.get(word)
    if direct:
        return direct
    if "'" in word:
        if apostrophes is not None and word in apostrophes:
            return apostrophes[word]
        stem = word.split("'")[0]
        if stem:            # "I'm" has a one-letter stem, and is not a rare word
            return positions.get(stem)
    return None


# The endings the frequency list split off as words of their own: "you're" is there as "you" and "'re". Every one
# of these is a contraction; "'s" is too, but it is also every possessive in English, so only the contractions the
# keyboard's own table knows are counted as "'s" ones.
CONTRACTION_ENDINGS = {"t", "re", "ll", "ve", "d", "m"}

CONTRACTIONS_KT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "java", "com",
                               "mccal", "folio", "keys", "Contractions.kt")


def english_contractions():
    """
    The keyboard's own table of contractions, read from Contractions.kt so the two can never disagree.

    Returns the bare spellings that are never words on their own ("dont", "youre") and every contraction the table
    puts back, lowercase.
    """
    source = open(CONTRACTIONS_KT, encoding="utf-8").read()

    def block(name):
        body = re.search(name + r" = mapOf\((.*?)\n    \)", source, re.S).group(1)
        return dict(re.findall(r'"([^"]+)" to "([^"]+)"', body))

    sure = block("ENGLISH_SURE")
    maybe = block("ENGLISH_MAYBE")
    return set(sure), {value.lower() for value in list(sure.values()) + list(maybe.values()) if "'" in value}


def apostrophe_positions(words, counts, found):
    """
    Where each English contraction belongs in the frequency list, from what the list does say about it.

    Ranking a contraction by its stem, as this used to, made "you're", "you'll", "you'd" and "you've" all exactly as
    common as "you", the commonest word in the list, so they tied and the strip put them in alphabetical order:
    "you'd" first. The list has three honest things to say about each one instead, and a contraction is the
    smallest of them:

    * **Its stem.** "you'll" can be no commoner than "you", which was counted every time it was said.
    * **Its ending.** Every "'ll" said was one of the "'ll" contractions, so none of them is commoner than all of
      them together. For "'m" that is exact: "I'm" is the only one.
    * **Its spelling without the apostrophe**, where that is never a word: "youre" is what subtitlers typed when
      they left it out, and they left it out about as often for one contraction as another. How often is measured
      on the ones whose stem is not a word either - "didn" said is "didn't" said - and it comes to about seven
      hundred times the bare count. A bare spelling missing from the list was said less than its last word was.

    A contraction whose bare spelling is a word too - "its", "well", "ill" - cannot use the third, so those share
    what their ending has left once the others are counted, in proportion to how often each one's stem is said:
    "'ll" goes mostly to "I'll" because "I" is said far more than "she". Where nothing is left over, the ending
    was already used up and all that is known is the first two.

    A possessive of a stem that also has contractions - "Don's", "you's" - is left with no count at all. Its stem's
    count is mostly the contractions ("don" was said four million times, nearly all of them in "don't"), so ranking
    it by its stem put "Don's" level with "don't" and "you's" ahead of "you're". So is a letter's plural - "I's",
    "A's" - as the list's "i" and "a" are the pronoun and the article.

    [found] is what [contraction_counts] worked out.
    """
    contracted = {word.partition("'")[0] for word in found}
    positions = {word: rank_of(count, counts) for word, count in found.items()}
    for word in words:
        lower = word.lower()
        stem, _, ending = lower.partition("'")
        if ending != "s" or lower in found:
            continue
        if len(stem) == 1 or stem in contracted:
            positions[lower] = None
    return positions


def contraction_counts(words, counts):
    """How many times each English contraction was said, worked out as [apostrophe_positions] describes."""
    bare_only, known = english_contractions()
    floor = min(counts.values())
    ratio = statistics.median(counts[stem] / counts[stem + "t"] for stem in NOT_WORDS
                              if stem in counts and stem + "t" in counts)
    groups = {}
    for word in words:
        lower = word.lower()
        stem, _, ending = lower.partition("'")
        if not ending or stem not in counts:
            continue
        if ending in CONTRACTION_ENDINGS or lower in known:
            groups.setdefault(ending, set()).add(lower)

    found = {}
    for ending, members in groups.items():
        whole = counts.get("'" + ending, math.inf)

        def most(word):
            return min(counts[word.partition("'")[0]], whole)

        spelled = {word for word in members if word.replace("'", "") in bare_only}
        for word in spelled:
            found[word] = min(most(word), counts.get(word.replace("'", ""), floor) * ratio)
        rest = members - spelled
        left = whole - sum(found[word] for word in spelled) if whole != math.inf else 0
        said = sum(counts[word.partition("'")[0]] for word in rest)
        for word in rest:
            share = left * counts[word.partition("'")[0]] / said if left > 0 else math.inf
            found[word] = min(most(word), share)

    print(f"contractions ranked by their own counts: {len(found)} (bare spelling said {ratio:.0f} times less)")
    return found


def rank_of(count, counts):
    """Where a word said [count] times would sit in the frequency list."""
    negated = sorted(-said for said in counts.values())
    return bisect.bisect_left(negated, -count) + 1


def stem_positions(words, counts, found):
    """
    Where the stem of an "n't" contraction sits as a word of its own, when it is one: "don", "won", "can", "haven".

    The list counted every "don't" as a "don", so "don" came out the thirty-first word in English and the strip
    offered it for "do", and "won" did the same for "wo". The stems that are never words are left out altogether
    (NOT_WORDS), but these are real words and have to stay known.

    Where the contraction accounts for the stem's whole count, as it does for "don", "won" and "haven", the list has
    nothing to say about the word on its own, so it ranks as a word the list never saw: known, so never corrected
    away, but not offered ahead of the words people do type. Where some of the count is left over, as for "can", the
    stem keeps its rank. The contraction's count is an estimate, and "can" said on its own is common enough that
    taking the estimate away would say more about the estimate than about the word.
    """
    have = {word.lower() for word in words}
    positions = {}
    for word, count in found.items():
        stem, _, ending = word.partition("'")
        if ending == "t" and stem in have and stem in counts and count >= counts[stem]:
            positions[stem] = None
    return positions


def fold(word):
    """The word with its accents taken off: "acción" is "accion", "für" is "fur"."""
    return "".join(c for c in unicodedata.normalize("NFD", word) if unicodedata.category(c) != "Mn")


def mark_lost_accents(entries, positions):
    """
    Marks a word that looks like its accented twin typed without the accent as known but never offered.

    Subtitles are typed by people, and people leave accents off: "accion" is in the Spanish list, and "fur" in the
    German one, because someone typed "acción" and "für" that way often enough to count. Offered in the strip they
    are noise. But many are also real words - "papa" and "papá", "cote" and "côté", "Ware" and "Wäre", "Caracas" -
    and nothing here can tell which, so none of them is dropped: a word dropped from the list is one autocorrect
    replaces, and replacing a real word is the one mistake a keyboard must not make.

    So where the accented form is ten times as common (ten points on this log scale), the bare one is kept at
    KNOWN_ONLY: known, so never corrected and never underlined; never suggested; and the accented twin is still
    offered in the strip, one tap away. The same goes for a bare form further down the frequency list than the cut,
    so a real word just past it ("facas" beside "faças") is not corrected either. Where the two are close, both are
    ordinary words - "acabo" and "acabó" - and both stay as they are.
    """
    twins = {}
    for word, value in entries.items():
        bare = fold(word)
        if bare != word:
            twins[bare] = min(value, twins.get(bare, 99))
    marked = 0
    for word in list(entries):
        if word in twins and twins[word] <= entries[word] - LOST_ACCENT_GAP:
            entries[word] = KNOWN_ONLY
            marked += 1
    for word in positions:
        if word in twins and word not in entries and WORD.match(word):
            entries[word] = KNOWN_ONLY
            marked += 1
    return entries, marked


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
    entries, lost = mark_lost_accents(entries, positions)
    print(f"{lost} words that may only be a lost accent kept as known but never offered")
    with open(destination, "w", encoding="utf-8") as out:
        for word, value in sorted(entries.items(), key=lambda kv: kv[0].lower()):
            out.write(f"{word}:{value:02d}\n")
    print(f"{len(entries)} words from the spoken list alone -> {destination} "
          f"({os.path.getsize(destination)} bytes)")


def main(final_dir, frequency_file, destination):
    words = scowl_words(final_dir)
    positions = frequencies(frequency_file)
    counts = counts_of(frequency_file)
    found = contraction_counts(words, counts)
    apostrophes = apostrophe_positions(words, counts, found)
    # The n't stems are ranked by what they were said as on their own, not by the contractions counted under them.
    ranked = {**positions, **stem_positions(words, counts, found)}
    entries = {word: score(position_of(word.lower(), ranked, apostrophes), band) for word, band in words.items()}

    spoken = 0
    have = {word.lower() for word in entries}
    for word, position in positions.items():
        if position > SPOKEN_CUTOFF or word in NOT_WORDS or word in JUNK or word in have:
            continue
        if not WORD.match(word) or not (2 < len(word) <= 20):
            continue
        entries[word] = score(position, 0)
        spoken += 1

    # English ships without its borrowed accents ("café", "cliché"): ninety-odd words, and the list has always been
    # plain ASCII. Kept that way so this reproduces the list that ships, byte for byte.
    entries = {word: value for word, value in entries.items() if word.isascii()}

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
