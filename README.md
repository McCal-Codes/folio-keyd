# Keyd

A keyboard for [Folio](https://github.com/McCal-Codes/folio). A separate app, because Android requires a keyboard to
be its own input method service — the launcher can't contain one.

**Early, and further along than it sounds.** It types, corrects, learns, suggests, expands shortcuts, does emoji,
splits around a fold, and speaks six languages. What it doesn't have yet is listed at the bottom, honestly.

## What it does today

**Typing.** A QWERTY that follows the field it's editing, shift with a caps lock, symbols, a number row if you want
one, accents on a long press with the alternate printed in the corner of the keycap, backspace that takes a word on a
swipe and repeats when held, and a space bar that moves the cursor.

**Words.** A 57,000-word English list with a commonness score on each word, a spell check, autocorrection for clear
typos only — never a real word, and always one backspace from undone — a suggestion strip that agrees with what
autocorrect would do, and learning: words you type that no dictionary knows are remembered on the phone, never from a
password field and never from an app that asks it not to.

**Shortcuts.** Short things that stand for long things: `omw` for "on my way", `addr` for where you live. Made and
unmade on their own screen.

**Six languages**, each with its own keys, accents and word list: English, German, Spanish, French, Italian,
Portuguese.

**The window.** A book fold splits the keyboard around the crease so no key sits on the hinge, automatically or
always. Height in three sizes, the room left under the keys, and a check that the line you're typing on hasn't gone
behind the keyboard.

**Gestures.** Flick a key up or down for what's printed on it, swipe the space bar to move the cursor, swipe backspace
to take a word, double-space for a full stop — each one a switch, because everyone's thumbs disagree.

**How it looks.** Light, dark or the system's, a high-contrast option, and every colour pair checked against WCAG AA
for the label that sits on it. The click and the haptic are yours to turn off.

## Why another keyboard

Not to beat Gboard at autocorrect — that's years of data. The things it can do that Gboard has chosen not to:

- **It reads the window, not the phone.** The split comes from the fold, not from a device list.
- **No internet permission.** Not a promise in a privacy policy: the app asks for **no permissions at all**, so the
  system enforces it. Nothing it sees can leave the phone, because it has no way to send anything.
- **Nothing is sent to produce a suggestion.** The word list is on the phone and the lookup is on the phone.
- **Swearing is never corrected into another word.**
- **The alternate is printed on the keycap**, so nothing is hidden behind a long press you had to guess at.

## Building

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug
```

The checks: `./gradlew :app:testDebugUnitTest` — **289 of them**, and CI runs them on every push and pull request.
They cover the field rules, where the keys land at every window size a phone can give us, word deletion, the
dictionary and the corrections, every language's word list and its order, what a screen reader reads, the contrast of
the colours that ship — and, through Robolectric, the keyboard being typed on: real touch sequences including two
fingers at once, the repeat on a held backspace, swipes that cancel a press, and what reaches the text field on the
other side.

CI runs on Linux on purpose. A Mac can't tell `words-en-us.txt` from `words-en-US.txt`, and that difference once cost
this repository its entire English dictionary everywhere except the machine it was written on.

None of it replaces typing on a real phone, which is still the test that matters.

The debug build installs as `com.mccal.folio.keys.dev` so it sits beside a release rather than replacing the keyboard
you rely on. Install it, then open Keyd — it walks through the two steps Android makes you take, and explains the
warning on the second one.

## What's next

In order, and honestly. Two things the earlier version of this list promised are worth correcting: **the typing
engine is written** — its own dictionary, corrections and learning, rather than AOSP's LatinIME — and **per-app
profiles don't exist yet**, though the keyboard already knows which app it's typing into.

1. **Clipboard history.** The paste glyph is drawn and nothing is behind it. A keyboard may read the clipboard while
   it's focused, so this costs no permission — and a pinned clip becoming a shortcut is one step from what Shortcuts
   already does.
2. **Per-app profiles.** Theme, layout, shelf, gestures and learning, decided per app from `EditorInfo.packageName`,
   which needs no accessibility service. The thing no other keyboard offers, and the reason to write this one.
3. **Typing insights, and rules from them.** The words you correct most and the keys you miss, shown back to you,
   with one tap to make a rule. Learning already collects what this would show.
4. **One-handed and floating.** Split solves the reach problem on a fold; a tall slab is the case this would answer.
5. **Glide typing.** The feature that decides whether anyone switches keyboard, and a month of work rather than an
   afternoon.
6. **Themes and layouts as signed Folio Market packages**, once the Market ships in Folio 0.7.0.

Design, decisions and the practices checklist: `docs/folio-keys.md` in the Folio repository — which is currently
missing and needs reconstructing from the code.

## Licence

MIT. See [LICENSE](LICENSE).

### The word lists

In `app/src/main/assets`, one per language, built by `tools/build-dictionary.py`. Their notices ship beside them as
`words-COPYING.txt` and must stay there, which is the whole of what either licence asks.

**English** is two sources in one file:

- the **words** are [SCOWL](http://wordlist.aspell.net/) 2020.12.07, cut to its "size 40" band — about 57,000 of
  them. SCOWL is permissively licensed (BSD-style).
- the **commonness score** on each word is the OpenSubtitles frequency list from
  [FrequencyWords](https://github.com/hermitdave/FrequencyWords), log-scaled, MIT licensed. SCOWL's own bands are
  tiers rather than frequencies — its top tier holds 4,434 words, so within it "the" ties with "tea", and a keyboard
  that cannot tell those apart offers the wrong one.

**The other five languages use the frequency list alone**, and that is a deliberate licence decision rather than a
shortcut: the open word lists for most languages are GPL, which this app cannot use. Building from spoken frequency
data keeps Keyd MIT — at the cost of a tighter cut, since a frequency list contains misspellings and some of them are
said often.
