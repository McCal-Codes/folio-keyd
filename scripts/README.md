# Scripts

| Script | What it does |
|---|---|
| `beta.sh` | The next beta: bump, test, sign, commit, tag, install on the phone. Nothing is pushed. |
| `release-signed.sh` | Signs Keyd and Keyd Dev into `dist/Keyd-<version>/`. A release also points `source/` at it. |
| `phone.sh` | Finds the Fold (USB, then wireless debugging), installs on it, screenshots it, taps it. |
| `emoji-keywords.py`, `bigrams.py` | Rebuild the emoji names and next-word data from their sources. |

## The keystore password, once

The signing scripts read the keystore password from the macOS Keychain, so nobody pastes it. Store it once, in
Terminal, with one of these. Each asks for the password itself; it is never on the command line or in the history.

Ask on this Mac every time something signs (the safest; you have to be at the Mac):

```bash
security add-generic-password -s folio-release-keystore -a "$USER" -T "" -w
```

Trust the signing scripts while this Mac is logged in, so a build can be signed when you are away from the Mac and
say so from your phone:

```bash
security add-generic-password -s folio-release-keystore -a "$USER" -T /usr/bin/security -w
```

To switch, delete it and add it the other way:

```bash
security delete-generic-password -s folio-release-keystore -a "$USER"
```

The keystore stays at `~/folio-release.jks` and the alias is `folio`. Set `FOLIO_RELEASE_*` yourself to use others.

## The phone

`phone.sh` tries USB first. Wireless debugging only works while the Mac and the phone are on the same Wi-Fi, turns
itself off after a while, and changes port each time; when it can't connect, the script says which of those it is.

```bash
scripts/phone.sh connect
scripts/phone.sh install dist/Keyd-0.3.2-beta.1/Keyd-Dev-0.3.2-beta.1.apk
scripts/phone.sh shot           # prints the path of a screenshot of whichever screen is on
scripts/phone.sh tap 540 960    # in the pixels of that screenshot
```
