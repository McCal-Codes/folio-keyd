# Changelog

All notable changes to Keyd. Versions follow [Semantic Versioning](https://semver.org), and this file follows
[Keep a Changelog](https://keepachangelog.com). Keyd shows the newest section after an update, and every version
under Settings › About › What's New.

## [0.3.0] - 2026-09-28

### Added
- **Emoji search:** a magnifier in the emoji panel. Type "heart" or "cat" and pick from what comes up, in all six languages.
- **What it fixes:** Keyd counts the typos it fixes and the fixes you undo. After three, the strip asks once whether to keep a word or always fix it, and Settings shows both lists.
- **Per-app settings:** different suggestions, fixing, learning and number row for each app you type in.
- **Swipe up to edit:** on Z, X, C, V and A a swipe up does undo, cut, copy, paste and select all.
- **Slide from shift to select**, the way the space bar moves the cursor.
- **Your toolbar:** Undo is on it now, and Settings lets you choose and order up to seven buttons, including Redo, Cut and Select all.
- **Selected text:** the toolbar counts characters and words, and Style turns the selection bold, italic, script or monospace.
- **One-handed:** a narrower keyboard against either edge, with a rail to switch sides or go back to full width.
- **Feel:** vibration strength, no key clicks while Bluetooth audio is connected, and pure black in dark mode.

- **Next word:** after a space the strip suggests the next word, in all six languages, and the word before helps pick the right fix.
- **Contractions:** dont, im, youre and about 40 more get their apostrophe back, and "i" becomes "I".

### Changed
- Fewer wrong fixes: slang like btw, idk and wifi is left alone and learned, possessives stay out of the strip unless you type an apostrophe, and a word is never shown twice.
- Missing accents in German, Spanish, French, Italian and Portuguese are offered first in the strip, and a real word is never swapped for its accented twin.
- "thisis" becomes "this is".
- A word you leave as typed is learned after three times on two different days, not the first time.

### Fixed
- Search and Go no longer cancel when a thumb rolls off the corner.
- Web and email addresses are left as typed: nothing corrected, learned or given a double-space period.
- The split keyboard's keys are one size in both halves, and the space bar says its language once.
- Keyd no longer learns typos of fairly common words, like "updste" for "update".
- Making a shortcut from a pinned clip goes back to the app you were typing in.

## [0.2.0] - 2026-09-26

### Added
- **Cursor pad:** a toolbar key that swaps the letters for arrows, word jumps, line start and end, select, cut and select all.
- **Voice key:** a mic on the toolbar, and at the end of the suggestion strip while you type. It hands you to your phone's own voice keyboard, which is not Keyd and does use the Internet. It only shows when your phone has one.
- **Key styles:** Folio, Material or Samsung, under Look and size.
- **Pinned clip to shortcut:** hold a pinned clip to make it a text shortcut.
- **Report a problem:** three questions and a preview of every line before anything is shared. After a crash, Settings offers to send one. Never what you type.
- **Move to a new phone:** export your learned words and shortcuts to a file, and import them on the next phone. No permission needed.
- **Diagnostic log:** off unless you turn it on, and off again by itself after 24 hours.

### Fixed
- The keyboard could get stuck taking the whole screen after you hid it, until the app was closed. Thanks to Roby for the report and the video.
- Rotating or unfolding the phone dropped a chosen light or dark look, and high contrast, until the next text field.
- The emoji and clipboard panels vibrated even with Vibration turned off.
- A held key kept repeating if the keyboard was hidden while it was down.

## [0.1.1] - 2026-09-21

### Added
- **Knows whether it's on:** Settings opens with Keyd's version and whether it's turned on and chosen as your keyboard, with the one button that fixes whichever step is missing.
- **What's New and About:** the version, these notes, and a way to report a problem. Reporting opens GitHub in your browser; Keyd itself still has no internet access.
- **Keyd Dev:** a separate build for testers, with a Developer page: detailed logging that never records what you type, recent errors and a report to share. Expect more bugs than Keyd.

### Changed
- The gear beside Keyd in Android's keyboard list opens Settings instead of the setup screen.

## [0.1.0] - 2026-09-21

### Added
- **Typing:** a QWERTY that follows the field it's editing, shift with caps lock, symbols, an optional number row, and accents on a long press with the alternate printed on the key.
- **Corrections:** suggestions, a spell check, and autocorrect for clear typos only. It never changes a real word, and one backspace undoes it.
- **Six languages:** English, German, Spanish, French, Italian and Portuguese, each with its own keys, accents and word list.
- **Split for the fold:** on a book fold the keyboard splits around the crease, so no key sits on the hinge.
- **Clipboard history:** never from a password field, and forgotten after an hour unless you pin it.
- **Shortcuts and emoji:** "omw" for "on my way" and your own, and emoji in your phone's font.
- **No permissions:** not even the Internet, so nothing Keyd sees can leave the phone.
