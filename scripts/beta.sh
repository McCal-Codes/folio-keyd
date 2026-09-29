#!/usr/bin/env bash
# One Keyd beta, from a clean branch to McCal's phone:
#
#   scripts/beta.sh            the next beta of the version being worked on (0.3.2-beta.1 -> 0.3.2-beta.2)
#   scripts/beta.sh 0.3.2      the first beta of 0.3.2, when the branch still says a released version
#
# It bumps the version, then tests, builds and signs (release-signed.sh, which asks the Keychain for the password),
# and only once all of that passed does it commit "0.3.2 beta 2" and tag v0.3.2-beta.2. A failed test leaves no
# commit and no tag behind, and the version goes back to what it was. Then it installs Keyd Dev on the phone.
# Nothing is pushed: tags and branches go up when McCal says so.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd -P)
cd "$root"
gradle=app/build.gradle.kts

[[ -z "$(git status --porcelain)" ]] || { echo "beta: commit or stash your changes first." >&2; exit 1; }
current=$(sed -n 's/^val keysVersion = "\(.*\)"$/\1/p' "$gradle")
if [[ $# -ge 1 ]]; then
    next="$1-beta.1"
elif [[ $current == *-beta.* ]]; then
    next="${current%-beta.*}-beta.$(( ${current##*-beta.} + 1 ))"
else
    echo "beta: $current is a release. Say which version this is a beta of: scripts/beta.sh 0.3.2" >&2; exit 1
fi
base=${next%-beta.*}
grep -q "^## \[$base\]" CHANGELOG.md || {
    echo "beta: CHANGELOG.md needs a \"## [$base] - Unreleased\" section first, with what this beta adds." >&2; exit 1
}
git rev-parse -q --verify "refs/tags/v$next" >/dev/null && { echo "beta: tag v$next already exists." >&2; exit 1; }

echo "beta: $current -> $next"
sed -i '' "s/^val keysVersion = \".*\"$/val keysVersion = \"$next\"/" "$gradle"
restore() { git checkout -- "$gradle"; echo "beta: nothing committed, version is back to $current." >&2; }
trap restore ERR
rm -rf "dist/Keyd-$next"
scripts/release-signed.sh
trap - ERR

git commit -q -am "$base beta ${next##*-beta.}"
git tag "v$next"
echo "beta: committed and tagged v$next"
scripts/phone.sh install "dist/Keyd-$next/Keyd-Dev-$next.apk" ||
    echo "beta: built and tagged, but not installed. Connect the phone and run: scripts/phone.sh install dist/Keyd-$next/Keyd-Dev-$next.apk"
