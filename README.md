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
