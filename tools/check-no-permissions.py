#!/usr/bin/env python3
"""Keyd asks for no permissions at all, and CI checks it.

The README and Folio's help page both say Keyd asks for no permissions, so with no INTERNET permission nothing it sees
can leave the phone. A promise like that is only worth making if a machine keeps it. This reads the *merged* manifest
the build produces, not the source one, because a library can add a permission to the app without it ever appearing in
app/src/main/AndroidManifest.xml.

    python3 tools/check-no-permissions.py [path/to/AndroidManifest.xml]

With no argument it looks under app/build/intermediates/merged_manifests/ and fails if it finds nothing, so it cannot
pass by checking nothing. Run it after `./gradlew :app:assembleDebug`.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANDROID = "{http://schemas.android.com/apk/res/android}name"
ASKS = ("uses-permission", "uses-permission-sdk-23", "permission")


def manifests() -> list[Path]:
    if len(sys.argv) > 1:
        return [Path(sys.argv[1])]
    return sorted((ROOT / "app/build/intermediates/merged_manifests").glob("**/AndroidManifest.xml"))


def main() -> int:
    found = manifests()
    if not found:
        print("No merged manifest found. Run ./gradlew :app:assembleDebug first; refusing to pass on nothing.")
        return 1
    problems = []
    for path in found:
        for el in ET.parse(path).getroot():
            if el.tag in ASKS:
                problems.append(f"{path.name} ({path.parent.parent.name}): <{el.tag}> {el.get(ANDROID)}")
    if problems:
        print("Keyd says it asks for no permissions, but the build declares:")
        for problem in problems:
            print(f"  {problem}")
        return 1
    print(f"No permissions in {len(found)} merged manifest(s): {', '.join(p.parent.parent.name for p in found)}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
