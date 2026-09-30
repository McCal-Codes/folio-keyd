#!/usr/bin/env bash
# A Keyd release, in the two steps that need a person between them:
#
#   scripts/release.sh prepare "One sentence for the top of the release notes and the Market."
#       On the branch the betas came from: the version drops its -beta.N, CHANGELOG.md gets today's date, then the
#       tests run and both APKs are signed (release-signed.sh, password from the Keychain). Only if that all passed
#       is it committed as "Keyd X.Y.Z", pushed to release/X.Y.Z and opened as a pull request into main. Nothing
#       anyone else can see has changed yet.
#
#   scripts/release.sh publish
#       Once McCal says ship: waits for CI, squash-merges the pull request, creates the vX.Y.Z release on GitHub with
#       Keyd only (notes from the CHANGELOG section, the checksum and the signer), checks the uploaded file hashes the
#       same, then points Keyd's Folio source at it (app.json, manifest version, a depiction changelog entry), merges
#       that, and waits until the live index lists the new version.
#
# Keyd Dev is never published; after prepare it is in dist/ for scripts/phone.sh.
set -Eeuo pipefail
root=$(cd "$(dirname "$0")/.." && pwd -P)
cd "$root"
repo="McCal-Codes/folio-keyd"
gradle=app/build.gradle.kts
index="https://mccal-codes.github.io/folio-keyd/index.json"
signer="bad8e099557b70c690e71a3fffe13561c8662c20612047ce1e2559a1c14d8441"

die() { echo "release: $*" >&2; exit 1; }
version() { sed -n 's/^val keysVersion = "\(.*\)"$/\1/p' "$gradle"; }
# The bullets of one version's CHANGELOG section, headings and all.
section() { awk -v v="$1" '$0 ~ "^## \\[" v "\\]" { on = 1; next } /^## \[/ { on = 0 } on' CHANGELOG.md; }
wait_checks() {
    local pr=$1 out
    for _ in $(seq 1 60); do
        out=$(gh pr checks "$pr" -R "$repo" 2>&1 || true)
        if grep -q fail <<< "$out"; then echo "$out" >&2; die "a check failed on #$pr."; fi
        grep -q pass <<< "$out" && ! grep -q pending <<< "$out" && return 0
        sleep 15
    done
    die "checks on #$pr did not finish in 15 minutes."
}

prepare() {
    local summary=${1:-}
    [[ -n "$summary" ]] || die 'prepare needs the one-sentence summary: scripts/release.sh prepare "Keyd X.Y.Z is ..."'
    [[ -z "$(git status --porcelain)" ]] || die "commit or stash your changes first."
    local current next today
    current=$(version); next=${current%%-*}; today=$(date +%F)
    [[ "$(git rev-parse --abbrev-ref HEAD)" != main ]] || die "run this on the branch the betas came from, not main."
    grep -q "^## \[$next\] - Unreleased" CHANGELOG.md || die "CHANGELOG.md has no \"## [$next] - Unreleased\" section."
    git rev-parse -q --verify "refs/tags/v$next" >/dev/null && die "v$next is already tagged."
    [[ -e "dist/Keyd-$next" ]] && die "dist/Keyd-$next exists from an earlier try. Delete it to build again."

    echo "release: $current -> $next, dated $today"
    sed -i '' "s/^val keysVersion = \".*\"$/val keysVersion = \"$next\"/" "$gradle"
    sed -i '' "s/^## \[$next\] - Unreleased$/## [$next] - $today/" CHANGELOG.md
    trap 'git checkout -- "$gradle" CHANGELOG.md source/ 2>/dev/null; echo "release: nothing committed, put back." >&2' ERR
    scripts/release-signed.sh
    trap - ERR
    # app.json belongs to the source pull request after the release is up, not to this one.
    git checkout -- source/
    grep -q "$signer" "dist/Keyd-$next/signing-certificate.txt" || die "dist/Keyd-$next is not signed by Keyd's key."
    printf '%s\n' "$summary" > "dist/Keyd-$next/summary.txt"

    git commit -q -am "Keyd $next"
    git push -q origin "HEAD:release/$next"
    local body
    body="$summary

$(section "$next")

## Checked
- The unit tests, in release-signed.sh, before signing. CI runs them again on this pull request.
- Signed by \`$signer\`, the key every Keyd release has."
    gh pr create -R "$repo" --base main --head "release/$next" --title "Keyd $next" --body "$body"
    echo "release: prepared. Keyd Dev $next for your phone: scripts/phone.sh install dist/Keyd-$next/Keyd-Dev-$next.apk"
    echo "release: when you are ready to ship, run: scripts/release.sh publish"
}

publish() {
    local v pr merged apk sha size summary notes
    v=$(version); [[ $v != *-* ]] || die "$v is a beta. Run prepare first."
    apk="dist/Keyd-$v/Keyd-$v.apk"; [[ -f "$apk" ]] || die "$apk is missing. Run prepare first."
    summary=$(cat "dist/Keyd-$v/summary.txt")
    pr=$(gh pr list -R "$repo" --head "release/$v" --state open --json number -q '.[0].number')
    [[ -n "$pr" ]] || die "no open pull request from release/$v."
    wait_checks "$pr"
    gh pr merge "$pr" -R "$repo" --squash >/dev/null
    merged=$(gh pr view "$pr" -R "$repo" --json mergeCommit -q .mergeCommit.oid)
    git fetch -q origin
    [[ -z "$(git diff "HEAD" "$merged" -- app)" ]] || die "main's app differs from the signed build. Stop and look."
    sha=$(shasum -a 256 "$apk" | cut -d' ' -f1)
    size=$(stat -f%z "$apk")

    notes=$(mktemp)
    { echo "$summary"; echo; echo "## What's new"; section "$v" | sed 's/^### Added$//'
      echo; echo "Keyd still asks for no permissions at all, including no Internet permission."
      echo; echo "## Verify"; echo; echo '```'
      echo "SHA-256  $sha  Keyd-$v.apk"; echo "Signer   $signer"; echo '```'
      echo; echo "Signed with the same key as every Folio release."; } > "$notes"
    gh release create "v$v" -R "$repo" --target "$merged" --title "Keyd $v" --notes-file "$notes" "$apk" >/dev/null
    local got
    got=$(curl -sL "https://github.com/$repo/releases/download/v$v/Keyd-$v.apk" | shasum -a 256 | cut -d' ' -f1)
    [[ "$got" == "$sha" ]] || die "the uploaded Keyd-$v.apk hashes to $got, not $sha."
    echo "release: v$v is up, and the download matches."

    # The source, from a clean copy of main so nothing else rides along.
    local tree; tree=$(mktemp -d)/source
    git worktree add -q -b "source-$v" "$tree" origin/main
    (
        cd "$tree"
        cat > source/packages/keyd/app.json <<JSON
{
  "url": "https://github.com/$repo/releases/download/v$v/Keyd-$v.apk",
  "sha256": "$sha",
  "size": $size
}
JSON
        python3 - "$v" "$summary" <<'PY'
import json, sys
v, summary = sys.argv[1], sys.argv[2]
m = 'source/packages/keyd/manifest.json'
d = json.load(open(m)); d['version'] = v
open(m, 'w').write(json.dumps(d, indent=2, ensure_ascii=False) + '\n')
p = 'source/assets/keyd/depiction.json'
d = json.load(open(p))
log = next(b for b in d['blocks'] if b.get('type') == 'changelog')
if not any(e['version'] == v for e in log['entries']):
    log['entries'].insert(0, {'version': v, 'notes': {'en': summary}})
open(p, 'w').write(json.dumps(d, indent=2, ensure_ascii=False) + '\n')
PY
        git commit -q -am "Keyd $v in the source"
        git push -q origin "HEAD:source-$v"
        gh pr create -R "$repo" --base main --head "source-$v" --title "Keyd $v in the source" \
            --body "Points Keyd's Folio source at v$v: SHA-256 \`$sha\`, $size bytes, checked against the uploaded file." >/dev/null
    )
    local spr; spr=$(gh pr list -R "$repo" --head "source-$v" --state open --json number -q '.[0].number')
    wait_checks "$spr"
    gh pr merge "$spr" -R "$repo" --squash >/dev/null
    git worktree remove --force "$tree"
    for _ in $(seq 1 40); do
        curl -s "$index" | grep -q "Keyd-$v.apk" && { echo "release: the live index lists Keyd $v. Shipped."; return 0; }
        sleep 15
    done
    die "the source merged, but the live index doesn't list $v after 10 minutes. Check the source workflow."
}

case "${1:-}" in
    prepare) shift; prepare "$@" ;;
    publish) publish ;;
    *) die 'use: scripts/release.sh prepare "summary"   or   scripts/release.sh publish' ;;
esac
