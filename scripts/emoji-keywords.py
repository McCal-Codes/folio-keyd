#!/usr/bin/env python3
"""
Builds the names and search words for the emoji Keyd ships.

    python3 scripts/emoji-keywords.py

Writes app/src/main/assets/emoji/<tag>.tsv for each of Keyd's languages, one line per emoji, in the order Emoji.kt
lists them:

    glyph<TAB>name<TAB>keyword,keyword,...

The name is the one TalkBack reads out ("red heart"), and the keywords are what search matches against as well as
the name. Both come from Unicode CLDR 48.2, pinned to its release tag so a rebuild gives the same files: the
`annotations` file has most emoji, and `annotationsDerived` has the ones built from others, like flags. CLDR is
Unicode-3.0 licensed, and its notice is in THIRD_PARTY_NOTICES.md.

Only the emoji Keyd ships are kept. CLDR describes a few thousand, and a keyboard that searches for an emoji it cannot
show would find a box. CLDR also writes every emoji without U+FE0F, the character that asks for the colorful form,
so that is ignored when matching.

The script fails rather than writing a partial file if any shipped emoji has no name, so a new emoji added to
Emoji.kt without a CLDR entry is caught here and not on someone's phone.
"""
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ElementTree

RELEASE = "release-48-2"
BASE = f"https://raw.githubusercontent.com/unicode-org/cldr/{RELEASE}/common"

# CLDR's locale for each of Keyd's Language tags (Language.kt). Portuguese is CLDR's "pt", which is written for
# Brazil; its emoji names are the ones most Portuguese speakers see on every other phone.
LANGUAGES = {
    "en-US": "en",
    "es-ES": "es",
    "fr-FR": "fr",
    "de-DE": "de",
    "it-IT": "it",
    "pt-PT": "pt",
}

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EMOJI_KT = os.path.join(ROOT, "app/src/main/java/com/mccal/folio/keys/Emoji.kt")
OUT = os.path.join(ROOT, "app/src/main/assets/emoji")

VARIATION = "️"


def shipped():
    """The emoji in Emoji.kt, in its order: every string after the name and tab of each Category(...)."""
    with open(EMOJI_KT, encoding="utf-8") as source:
        text = source.read()
    found = []
    for block in re.findall(r"Category\(\s*\n(.*?)\n\s*\)", text, re.S):
        strings = re.findall(r'"((?:[^"\\]|\\.)*)"', block)
        for glyph in "".join(strings[2:]).split(" "):
            if glyph and glyph not in found:
                found.append(glyph)
    if not found:
        sys.exit("Found no emoji in Emoji.kt; has its shape changed?")
    return found


def fetch(path):
    url = f"{BASE}/{path}"
    with urllib.request.urlopen(url) as response:
        return response.read()


def annotations(locale):
    """{glyph without FE0F: (name, [keywords])} from both CLDR files. The main file wins where both have one."""
    names, keywords = {}, {}
    for folder in ("annotationsDerived", "annotations"):
        tree = ElementTree.fromstring(fetch(f"{folder}/{locale}.xml"))
        for node in tree.iter("annotation"):
            cp = node.get("cp").replace(VARIATION, "")
            value = (node.text or "").strip()
            if not value:
                continue
            if node.get("type") == "tts":
                names[cp] = value
            else:
                keywords[cp] = [word.strip() for word in value.split("|") if word.strip()]
    return names, keywords


def clean(value):
    """Tabs, commas and line breaks are the file's own separators, so none may appear inside a value."""
    return re.sub(r"[\t\n\r,]+", " ", value).strip()


def main():
    emoji = shipped()
    os.makedirs(OUT, exist_ok=True)
    for tag, locale in LANGUAGES.items():
        names, keywords = annotations(locale)
        missing = [glyph for glyph in emoji if glyph.replace(VARIATION, "") not in names]
        if missing:
            sys.exit(f"{locale}: no CLDR name for {' '.join(missing)}")
        lines = []
        for glyph in emoji:
            key = glyph.replace(VARIATION, "")
            words = []
            for word in keywords.get(key, []):
                word = clean(word)
                if word and word not in words:
                    words.append(word)
            lines.append(f"{glyph}\t{clean(names[key])}\t{','.join(words)}")
        path = os.path.join(OUT, f"{tag}.tsv")
        with open(path, "w", encoding="utf-8", newline="\n") as out:
            out.write("\n".join(lines) + "\n")
        print(f"{path}: {len(lines)} emoji")


if __name__ == "__main__":
    main()
