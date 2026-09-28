# Third-party notices

Keyd is MIT licensed (see [LICENSE](LICENSE)). It ships some data made by others, listed here with the notices
their licenses ask for. The word lists' notices are in `app/src/main/assets/words-COPYING.txt`.

## FrequencyWords: word lists and commonness scores

Word frequencies from [FrequencyWords](https://github.com/hermitdave/FrequencyWords) by Hermit Dave, built from the
OpenSubtitles corpus. Its README licenses the project as "MIT License for code. CC-by-sa-4.0 for content", and the
frequency lists are content, so the data is licensed under
[Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)](https://creativecommons.org/licenses/by-sa/4.0/).

Used in `app/src/main/assets/words-*.txt`: the commonness score on every word, and for German, Spanish, French,
Italian and Portuguese the word list itself. Built by `tools/build-dictionary.py`.

The lists were modified: filtered (cut by frequency and length, with stray apostrophe stems and junk entries
removed), merged with SCOWL for English, and re-ranked onto a 0-99 commonness scale. The modified lists in
`app/src/main/assets/` are shared under the same license, CC BY-SA 4.0. That covers the word list data only: Keyd's
code stays under the MIT License.

## Tatoeba: next-word lists

The lists of which words usually follow which, in `app/src/main/assets/next-*.txt`, are counted from the example
sentences of [Tatoeba](https://tatoeba.org), whose contributors release them under
[Creative Commons Attribution 2.0 France (CC BY 2.0 FR)](https://creativecommons.org/licenses/by/2.0/fr/).

Built by `scripts/bigrams.py` from Tatoeba's per-language sentence exports of 2026-09-26
(`https://downloads.tatoeba.org/exports/per_language/<code>/<code>_sentences.tsv.bz2` for eng, deu, spa, fra, ita
and por; the script pins each file's SHA-256). The sentences themselves are not shipped: only word pairs counted from
nine in ten of them, kept to about twenty thousand per language, and limited to words in Keyd's own word lists. The
changes made are exactly that counting and cutting, done by the script.

## Unicode CLDR: emoji names and search keywords

Emoji names and search keywords from Unicode CLDR 48.2. Copyright © 1991-2026 Unicode, Inc. Licensed under the
Unicode License v3 (Unicode-3.0).

Used in `app/src/main/assets/emoji/`, built by `scripts/emoji-keywords.py` from CLDR's `annotations` and
`annotationsDerived` files at the `release-48-2` tag, kept only for the emoji Keyd ships.

```
UNICODE LICENSE V3

COPYRIGHT AND PERMISSION NOTICE

Copyright © 1991-2026 Unicode, Inc.

NOTICE TO USER: Carefully read the following legal agreement. BY
DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.

Permission is hereby granted, free of charge, to any person obtaining a
copy of data files and any associated documentation (the "Data Files") or
software and any associated documentation (the "Software") to deal in the
Data Files or Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, and/or sell
copies of the Data Files or Software, and to permit persons to whom the
Data Files or Software are furnished to do so, provided that either (a)
this copyright and permission notice appear with all copies of the Data
Files or Software, or (b) this copyright and permission notice appear in
associated Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
THIRD PARTY RIGHTS.

IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder shall
not be used in advertising or otherwise to promote the sale, use or other
dealings in these Data Files or Software without prior written
authorization of the copyright holder.
```
