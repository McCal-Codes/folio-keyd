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

In order, and honestly, with why each one sits where it does. Clipboard history came off the top of this list and is
in; what follows is ordered by what someone would notice first, not by what is most interesting to write.

**Now — small, and closing gaps someone meets this week**

1. **A voice key.** There is no microphone anywhere in the source, and every other keyboard has one, so its absence
   reads as missing rather than principled. It hands typing to the phone's own voice keyboard, which is not Keyd and
   does use the network — so the key has to say so plainly rather than quietly become the exception to the promise.
2. **A pinned clip becomes a shortcut.** `Shortcuts` and pinned clips both exist and know nothing about each other.
   One step, and the two features are better for it.
3. **Clear all should ask first.** It takes pinned clips with it, and a pin is someone saying they meant to keep it.

**Next — the reason to write this keyboard rather than use Gboard**

4. **Per-app profiles.** Theme, layout, shelf, gestures and learning, decided per app from `EditorInfo.packageName`,
   which needs no accessibility service. Nothing else offers it, the keyboard already knows which app it is typing
   into, and it is the line in *Why another keyboard* that is still a promise.
5. **Export and import what it learned.** `Learned` and `Shortcuts` live on one phone only, so a new phone starts
   from nothing and nobody notices until it happens to them. A document picker needs no permission, so this can be
   done without touching the promise.

**Then — the quality that decides whether anyone keeps it**

6. **Context in the corrections.** `Suggestions` already costs edits against where the finger really was, which is
   the hard half; nothing knows the *previous word*, which is the half people feel. "in a nin" cannot prefer "min"
   over "nib" without it. The code is the easy part: the word lists are built from a unigram frequency list, so this
   starts with an hour spent finding out whether a permissively licensed bigram source exists that is small enough to
   ship. If it doesn't, that answers the question.
7. **Typing insights, and rules from them.** The words you correct most and the keys you miss, shown back to you,
   with one tap to make a rule. `Learned` already collects what this would show, and `Shortcuts` can hold the rule.

**Later — bigger, blocked, or both**

8. **Emoji search.** The panel has categories and recents, and the emoji carry no names or keywords at all — so this
   is a data job before it is a search box.
9. **A cursor pad.** Arrows and select-word, finishing the editing the toolbar started with select all, copy and
   paste.
10. **Multilingual typing.** Two dictionaries at once rather than one subtype at a time, which is what bilingual
    typing actually needs.
11. **One-handed and floating.** Split solves the reach problem on a fold; a tall slab is the case this answers.
12. **Glide typing.** The feature that decides whether anyone switches keyboard, and a month of work rather than an
    afternoon. Worth doing; not worth starting casually.
13. **Themes and layouts as signed Folio Market packages**, once the Market ships in Folio 0.7.0.

**Not planned, and not an oversight:** stickers, GIFs, and any syncing of what the keyboard learned. Each one needs
the network or a permission, and an app that asks for neither is the only claim here that cannot be made twice.

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
