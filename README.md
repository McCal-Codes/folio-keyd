# Folio Keys

A keyboard for [Folio](https://github.com/McCal-Codes/folio). Separate app, because Android requires a keyboard to be
its own input method service — the launcher can't contain one.

**Version 0.1.0 — early. It types; that's about it.** What's here is the skeleton the rest is built on: a QWERTY that
follows the field it's editing, shift, symbols, backspace that takes a word on a swipe, a space bar that moves the
cursor, and the window rules a keyboard has to respect.

## Why another keyboard

Not to beat Gboard at autocorrect — that's years of data. The things it can do that Gboard has chosen not to:

- **It reads the window, not the phone.** A book fold splits the keyboard around the crease so no key sits on the
  hinge; unfolded it splits with the middle holding what thumbs can't reach.
- **No internet permission.** Not a promise in a privacy policy: the app asks for no permissions at all, so the system
  enforces it.
- **A profile per app** — theme, layout, shelf, gestures and learning, decided per app from `EditorInfo.packageName`,
  which needs no accessibility service.
- **Per-key gestures**, printed on the keycap so nothing is hidden.
- **It learns what you fix**, and shows you: the words you correct most, the keys you miss, and one tap to make a rule.
- **Swearing is never corrected into another word.**

## Building

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug
```

Run the checks with `./gradlew :app:testDebugUnitTest`: 62 of them. They cover the field rules, where the keys land at
every window size a phone can give us, word deletion, what a screen reader reads and the contrast of the colours that
ship - and, through Robolectric, the keyboard being typed on: real touch sequences including two fingers at once, the
repeat on a held backspace, swipes that cancel a press, and what reaches the text field on the other side.

None of it replaces typing on a real phone, which is still the test that matters.

Install it, then open Folio Keys — it walks through the two steps Android makes you take, and explains the warning on
the second one.

The debug build installs as `com.mccal.folio.keys.dev` so it sits beside a release rather than replacing the keyboard
you rely on.

## What's next

In order, and honestly:

1. **The typing engine.** Reuse AOSP LatinIME (Apache-2.0): its dictionary, its spatial model, its corrections. This is
   the part that decides whether the keyboard is worth using; everything below is easy by comparison.
2. Suggestion strip, autocorrect, and undo-with-backspace.
3. Split, one-handed and floating, chosen by the window.
4. Key gestures, the action shelf, clipboard with snippets.
5. Per-app profiles, typing insights, correction rules.
6. Themes and layouts as signed Folio Market packages.

Design, decisions and the practices checklist: `docs/folio-keys.md` in the Folio repository.

## Licence

MIT. See [LICENSE](LICENSE).

### The word list

### English

The US English dictionary in `app/src/main/assets/words-en-US.txt` is two sources in one file:

- the **words** are [SCOWL](http://wordlist.aspell.net/) 2020.12.07, cut to its "size 40" band — about 57,000 of
  them. SCOWL is permissively licensed (BSD-style).
- the **commonness score** on each word is the OpenSubtitles frequency list from
  [FrequencyWords](https://github.com/hermitdave/FrequencyWords), log-scaled, MIT licensed. SCOWL's own bands are
  tiers rather than frequencies — its top tier holds 4,434 words, so within it "the" ties with "tea", and a
  keyboard that cannot tell those apart offers the wrong one.

Both notices ship verbatim beside the lists as `words-COPYING.txt` and must stay there, which is the whole of what
either licence asks.

### Everything else

Spanish, French, German, Italian and Portuguese have only the second source. SCOWL is English, and the open word
lists for most other languages are GPL, which this app cannot use — so those five are built from the OpenSubtitles
frequency data alone, with `tools/build-dictionary.py --spoken-only`.

That is a real difference in kind, and worth saying plainly: an English word is in the list because a dictionary
says it is a word, while a Spanish one is in the list because it was said often enough in subtitles. The frequency
data contains misspellings, and some of them are said often. The cut is therefore tighter for those languages
(30,000 rather than the full list) and anything below it is dropped rather than kept at a low score — a rare real
word costs someone one correction, whereas a common misspelling promoted into a dictionary poisons every
suggestion near it.

Nothing is sent anywhere to produce a suggestion: the list is on the phone, the lookup is on the phone, and the app
still holds no permissions at all — including no Internet permission.
