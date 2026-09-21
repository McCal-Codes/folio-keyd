#!/usr/bin/env bash
# Builds a signed Keyd release, and Keyd Dev beside it, and points Keyd's Folio source at both.
#
#   dist/Keyd-<version>/Keyd-<version>.apk          Keyd, signed with the same key as Folio
#   dist/Keyd-<version>/Keyd-Dev-<version>.apk      Keyd Dev (com.mccal.keyd.dev): the same code with its Developer page
#   dist/Keyd-<version>/SHA256SUMS.txt              both checksums, for the release notes
#   dist/Keyd-<version>/signing-certificate.txt     the certificate digest (the script checks both use one key)
#   source/packages/keyd/app.json, keyd-dev/app.json where each release asset will be, and what it hashes to
#
# Needs FOLIO_RELEASE_STORE_FILE (outside the repo), FOLIO_RELEASE_STORE_PASSWORD, FOLIO_RELEASE_KEY_ALIAS and
# FOLIO_RELEASE_KEY_PASSWORD. Upload both APKs to a GitHub release tagged v<version> with exactly those file names,
# or the addresses in app.json lead nowhere.
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
# A JAVA_HOME that isn't a folder (some shells export a message there) would stop Gradle, so only a real one is kept.
[[ -d "${JAVA_HOME:-}" ]] || export JAVA_HOME=/opt/homebrew/opt/openjdk@17

(cd "$root" && ./gradlew -q :app:testDebugUnitTest :app:assembleRelease :app:assembleDev)

# Keyd and Keyd Dev ship together, at the same version and signed with the same key: Keyd Dev is the same code with
# its Developer page, under its own app id, so testers can have it beside the Keyd they rely on.
mkdir -p "$out"
: > "$out/SHA256SUMS.txt"
for variant in release dev; do
    if [[ $variant == release ]]; then name="Keyd-$version.apk"; package=keyd; else name="Keyd-Dev-$version.apk"; package=keyd-dev; fi
    apk="$root/app/build/outputs/apk/$variant/app-$variant.apk"
    [[ -f "$apk" ]] || { echo "No signed $variant APK was produced: are all four signing variables right?" >&2; exit 1; }
    cp -p "$apk" "$out/$name"
    "$apksigner" verify --print-certs "$out/$name" > "$out/signing-certificate-$variant.txt"
    (cd "$out" && shasum -a 256 "$name" >> SHA256SUMS.txt)
    sha=$(shasum -a 256 "$out/$name" | cut -d' ' -f1)
    size=$(stat -f%z "$out/$name" 2>/dev/null || stat -c%s "$out/$name")
    cat > "$root/source/packages/$package/app.json" <<JSON
{
  "url": "https://github.com/$repo/releases/download/v$version/$name",
  "sha256": "$sha",
  "size": $size
}
JSON
done
cp "$out/signing-certificate-release.txt" "$out/signing-certificate.txt"
cmp -s <(grep SHA-256 "$out/signing-certificate-release.txt") <(grep SHA-256 "$out/signing-certificate-dev.txt") ||
    { echo "Keyd and Keyd Dev came out signed by different keys." >&2; exit 1; }

echo "Signed release: $out"
grep "SHA-256" "$out/signing-certificate.txt" || true
echo "Upload both APKs to the v$version release: gh release create v$version -R $repo $out/Keyd-$version.apk $out/Keyd-Dev-$version.apk"
echo "source/packages/keyd/app.json and keyd-dev/app.json now point at v$version. Commit them after the release is up."
