#!/usr/bin/env python3
"""
Builds the next-word lists: for each common word, the words that most often come after it.

    python3 scripts/bigrams.py <cache-dir> [--held-out <dir>]

**Source.** Tatoeba (https://tatoeba.org), a collection of example sentences written and checked by volunteers, one
export per language. Tatoeba releases its sentences under CC BY 2.0 FR (https://creativecommons.org/licenses/by/2.0/fr/),
which allows shipping them, or anything made from them, in an MIT app as long as they are credited. The credit is in
THIRD_PARTY_NOTICES.md. Leipzig's corpora and Google Books Ngrams were the other candidates; Leipzig's terms could not
be read (the site answers scripts with a bot check), and Google's have no Portuguese. One source for all six languages
also means the six lists are made the same way.

**Pinned.** Tatoeba only publishes its latest weekly export, so there is no URL that means one version forever. The
export these lists came from is named below by date, SHA-256 and the highest sentence id in it. A different download
is still used, with a warning, and only its sentences up to that id are read - which reproduces the lists exactly
unless sentences were since edited or deleted.

**What is kept.** Every tenth sentence (by id) is held back and never counted, so the lists can be measured on
sentences they have not seen: `--held-out` writes those out. A pair is two words with nothing but a space between
them: a comma, a bracket or a dash says too little about what follows. A sentence's first word follows ".", which
is what the keyboard calls the start of a sentence. Only words in that language's word list are offered, in the
list's own spelling ("I", "I'm"), and never Tatoeba's stock characters: "Tom" and "Mary" are in a tenth of its
English sentences and in nobody's texts.

**Shape.** One line per word, `word<TAB>next next next`, commonest first. About twenty thousand pairs per language,
which is most of what is ever typed after a common word and a few tens of kilobytes once the APK compresses it.
"""
import bz2
import collections
import hashlib
import os
import re
import sys
import urllib.request

ASSETS = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")

EXPORT_DATE = "2026-09-26"
URL = "https://downloads.tatoeba.org/exports/per_language/{code}/{code}_sentences.tsv.bz2"

# Language tag: (Tatoeba's code, SHA-256 of the export used, highest sentence id in it).
LANGUAGES = {
    "en-US": ("eng", "f0568ae34496f8a03758ce6821b68da04b1fe67a9300de8d26d50494ae5aceab", 14061020),
    "de-DE": ("deu", "304ee996534717086da8c2395db78c02e609f0b12a6a11a9beca3285afa3e2ff", 14061034),
    "es-ES": ("spa", "d742c03589841691df20ae70ec6daac865e82918b228364760e6af3187f3f986", 14061038),
    "fr-FR": ("fra", "e010464c8fd433df1e88857e2a52bf2d6681645bec820dfb15f92065a9c35dce", 14060822),
    "it-IT": ("ita", "55c220482848cb9402c94e53750902744301fb700ca0cce5a938c8fd13644ca2", 14060327),
    "pt-PT": ("por", "5b25bdcc169155d83a4aaa236f7430430ce09d9c43858a3a4ecff8c93c300fe7", 14060812),
}

SENTENCE_START = "."
HELD_OUT_EVERY = 10     # sentence ids divisible by this are never counted
PAIR_BUDGET = 20_000    # pairs per language
MOST_AFTER = 12         # next words kept per word
FEWEST_SEEN = 3         # a pair seen fewer times than this is noise

# Tatoeba's recurring cast. They are in its sentences because contributors reuse them, not because anyone types them.
CAST = {
    "tom", "mary", "john", "alice", "bob", "ken", "jack", "jim", "tony", "mike", "emily", "maria", "marie", "pierre",
    "paul", "jane", "lucy", "betty", "bill", "judy", "susan", "fadil", "layla", "sami", "dan", "linda", "ziri", "mennad",
    "baya", "rima", "yanni", "skura", "taninna", "rosa", "pedro", "juan", "tomás", "tomas", "hans", "anna", "marco",
    "luca", "paolo", "ricardo", "joão", "joao", "muiriel",
}

TOKEN = re.compile(r"(?P<word>[^\W\d_]+(?:'[^\W\d_]+)*'?)|(?P<end>[.!?…]+)|(?P<other>\S)")


def download(cache, code, expected):
    path = os.path.join(cache, f"{code}_sentences.tsv.bz2")
    if not os.path.exists(path):
        print(f"downloading {URL.format(code=code)}")
        urllib.request.urlretrieve(URL.format(code=code), path)
    digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
    if digest != expected:
        print(f"  warning: {path} is not the {EXPORT_DATE} export; reading only its sentences up to the pinned id")
    return path


def sentences(path, highest):
    with bz2.open(path, "rt", encoding="utf-8") as lines:
        for line in lines:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 3:
                continue
            sentence_id = int(parts[0])
            if sentence_id <= highest:
                yield sentence_id, parts[2].replace("’", "'")


def pairs(text):
    """Consecutive words with only a space between them, the first word of a sentence after "."."""
    previous = SENTENCE_START
    for match in TOKEN.finditer(text):
        if match.group("word"):
            word = match.group("word").lower()
            if previous:
                yield previous, word
            previous = word
        elif match.group("end"):
            previous = SENTENCE_START
        else:
            previous = ""


def word_list(tag):
    """Lowercase word to the spelling the list keeps it in."""
    spelled = {}
    for line in open(os.path.join(ASSETS, f"words-{tag}.txt"), encoding="utf-8"):
        word = line.rsplit(":", 1)[0]
        lower = word.lower()
        # A lowercase entry wins: "may" the word over "May" the month.
        if lower not in spelled or word == lower:
            spelled[lower] = word
    return spelled


def offered(word, spelled):
    """The spelling to offer [word] in, or None if it is not one to offer."""
    if word in CAST:
        return None
    found = spelled.get(word)
    if found == "i" and spelled.get("i'm") == "I'm":
        return "I"          # English lists "i" too, for the letter, and the word is never written that way
    if found is None:
        # "l'homme": an elision and a word, the way the keyboard reads it too.
        head, apostrophe, tail = word.partition("'")
        if apostrophe and tail and (head + "'") in spelled and tail in spelled:
            return word
        return None
    # A word the list only has with a capital is a name, apart from English's I.
    if found != word and not (found == "I" or found.startswith("I'")):
        return None
    return found


def build(tag, code, expected, highest, cache, held_out):
    path = download(cache, code, expected)
    spelled = word_list(tag)
    counts = collections.defaultdict(collections.Counter)
    seen = collections.Counter()
    held = []
    for sentence_id, text in sentences(path, highest):
        if sentence_id % HELD_OUT_EVERY == 0:
            held.append(text)
            continue
        for previous, word in pairs(text):
            next_word = offered(word, spelled)
            if next_word is None:
                continue
            counts[previous][next_word] += 1
            seen[previous] += 1
    # The commonest words first, each with its commonest followers, until the budget is spent.
    lines = []
    budget = PAIR_BUDGET
    for previous, _ in seen.most_common():
        if budget <= 0:
            break
        if previous != SENTENCE_START and (previous in CAST or offered(previous, spelled) is None):
            continue
        after = [word for word, n in counts[previous].most_common(MOST_AFTER) if n >= FEWEST_SEEN]
        if not after:
            continue
        after = after[:budget]
        budget -= len(after)
        lines.append(previous + "\t" + " ".join(after))
    lines.sort()
    destination = os.path.join(ASSETS, f"next-{tag}.txt")
    with open(destination, "w", encoding="utf-8") as out:
        out.write("\n".join(lines) + "\n")
    print(f"{tag}: {len(lines)} words, {PAIR_BUDGET - budget} pairs, {os.path.getsize(destination)} bytes -> {destination}")
    if held_out:
        with open(os.path.join(held_out, f"held-out-{tag}.txt"), "w", encoding="utf-8") as out:
            out.write("\n".join(held) + "\n")


def main(args):
    if not args:
        sys.exit(__doc__)
    cache = args[0]
    held_out = args[args.index("--held-out") + 1] if "--held-out" in args else None
    os.makedirs(cache, exist_ok=True)
    for tag, (code, expected, highest) in LANGUAGES.items():
        build(tag, code, expected, highest, cache, held_out)


if __name__ == "__main__":
    main(sys.argv[1:])
