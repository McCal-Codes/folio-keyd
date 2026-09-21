#!/usr/bin/env bash
# Builds a signed Keyd release and points Keyd's Folio source at it.
#
#   dist/Keyd-<version>/Keyd-<version>.apk      the build, signed with the same key as Folio
#   dist/Keyd-<version>/SHA256SUMS.txt          its checksum, for the release notes
#   dist/Keyd-<version>/signing-certificate.txt the certificate digest
#   source/packages/keyd/app.json               where the release asset will be, and what it hashes to
#
# Needs FOLIO_RELEASE_STORE_FILE (outside the repo), FOLIO_RELEASE_STORE_PASSWORD, FOLIO_RELEASE_KEY_ALIAS and
# FOLIO_RELEASE_KEY_PASSWORD. Upload the APK to a GitHub release tagged v<version> with exactly that file name, or the
# address in app.json leads nowhere.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd -P)
version=$(sed -n 's/^val keysVersion = "\(.*\)"$/\1/p' "$root/app/build.gradle.kts")
[[ -n "$version" ]] || { echo "Could not read keysVersion from app/build.gradle.kts." >&2; exit 1; }
out="$root/dist/Keyd-$version"
repo="McCal-Codes/folio-keyd"

for name in FOLIO_RELEASE_STORE_FILE FOLIO_RELEASE_STORE_PASSWORD FOLIO_RELEASE_KEY_ALIAS FOLIO_RELEASE_KEY_PASSWORD; do
    [[ -n "${!name:-}" ]] || { echo "Missing $name." >&2; exit 1; }
done
case "$(cd "$(dirname "$FOLIO_RELEASE_STORE_FILE")" && pwd -P)/" in
    "$root"/*) echo "The keystore must live outside the repository." >&2; exit 1 ;;
esac
[[ ! -e "$out" ]] || { echo "Refusing to replace $out. Delete it to build again." >&2; exit 1; }

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk\.dir=//p' "$root/local.properties" 2>/dev/null)}}
apksigner=$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
[[ -n "$apksigner" ]] || { echo "apksigner not found. Set ANDROID_HOME." >&2; exit 1; }
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}

(cd "$root" && ./gradlew -q :app:testDebugUnitTest :app:assembleRelease)
apk="$root/app/build/outputs/apk/release/app-release.apk"
[[ -f "$apk" ]] || { echo "No signed APK was produced: are all four signing variables right?" >&2; exit 1; }

mkdir -p "$out"
name="Keyd-$version.apk"
cp -p "$apk" "$out/$name"
"$apksigner" verify --print-certs "$out/$name" > "$out/signing-certificate.txt"
(cd "$out" && shasum -a 256 "$name" > SHA256SUMS.txt)

sha=$(cut -d' ' -f1 "$out/SHA256SUMS.txt")
size=$(stat -f%z "$out/$name" 2>/dev/null || stat -c%s "$out/$name")
cat > "$root/source/packages/keyd/app.json" <<JSON
{
  "url": "https://github.com/$repo/releases/download/v$version/$name",
  "sha256": "$sha",
  "size": $size
}
JSON

echo "Signed release: $out"
grep "SHA-256" "$out/signing-certificate.txt" || true
echo "source/packages/keyd/app.json now points at v$version. Commit it after the release is up."
