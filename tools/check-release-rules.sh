#!/usr/bin/env bash
# Checks a pull request against the two release rules a machine can see. The same rules, by the same numbers, as
# Folio's tools/check-release-rules.sh, so one habit covers both repositories:
#
#   bash tools/check-release-rules.sh [base-ref]        # default base: origin/main
#
#   REL-5   No AI attribution: no co-author, credit line or robot footer in a commit or the description, and no
#           branch named after a tool. Not waivable.
#   REL-7   A change to what ships (app/src/main) adds its own line to the Unreleased section of CHANGELOG.md, in the
#           same pull request. A release pull request, which moves keysVersion, is exempt: its notes are the section.
#
# Folio's third rule, a version bump on its own, doesn't fit Keyd: a Keyd release is one pull request that brings the
# betas' work and the version together.
#
# REL-7 can be waived with the no-changelog label, for a change nobody using Keyd would see. Locally, set it as an
# environment variable: PR_LABELS="no-changelog" bash tools/check-release-rules.sh
set -uo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null)" 2>/dev/null || cd "$(dirname "$0")/.."

base=${1:-origin/main}
labels=${PR_LABELS:-}
failed=0

fail() { printf '\n\033[31mFAILED\033[0m  %s\n' "$1"; failed=1; }
pass() { printf '\033[32mok\033[0m      %s\n' "$1"; }
skip() { printf '\033[33mskipped\033[0m %s\n' "$1"; }

if ! git rev-parse --verify --quiet "$base" >/dev/null; then
    echo "Base ref '$base' not found. Fetch it first, or pass one that exists." >&2
    exit 2
fi
merge_base=$(git merge-base "$base" HEAD)

# REL-5. Narrow on purpose, as in Folio: a credit line, a co-author or an agent-named branch, not the word itself.
ai_credit='co-authored-by:.*(anthropic|openai|claude|copilot|codex|chatgpt|gemini|cursor)|generated (with|by) \[?(claude|chatgpt|copilot|codex|cursor|gemini)|claude\.com/claude-code|claude\.ai/code|🤖'
credited=$(git log --format='%h %B' "$merge_base..HEAD" | grep -iE "$ai_credit" || true)
branch=${PR_BRANCH:-$(git rev-parse --abbrev-ref HEAD)}
if [[ -n "$credited" ]]; then
    fail "REL-5  a commit credits an AI tool:
$(echo "$credited" | head -5 | sed 's/^/          /')
        Reword the commit without it (git commit --amend, or rebase and reword)."
elif grep -qiE '^(claude|codex|copilot|cursor)[/-]|(^|[/-])agent-' <<< "$branch"; then
    fail "REL-5  the branch '$branch' is named after a tool or an agent. Name it after the change."
elif grep -qiE "$ai_credit" <<< "${PR_BODY:-}"; then
    fail "REL-5  the pull request description credits an AI tool. Edit it out."
else
    pass "REL-5  no AI credit in the commits, the branch name or the description"
fi

changed=$(git diff --name-only "$merge_base" HEAD)
[[ -n "$changed" ]] || { echo "Nothing changed against $base."; exit $failed; }
has_label() { [[ ",$labels," == *",$1,"* ]]; }

# Read each file into a variable before matching: `grep -q` closing the pipe kills `git show` with SIGPIPE, which
# pipefail would report as a failure.
changelog_at() { git show "$1:CHANGELOG.md" 2>/dev/null || true; }
unreleased_bullets() {
    awk '/^## \[/ { inside = ($0 ~ /Unreleased/); next } inside && /^- / { count++ } END { print count + 0 }' \
        <<< "$(changelog_at "$1")"
}
has_unreleased() { grep -qE '^## \[[^]]+\] - Unreleased' <<< "$(changelog_at "$1")"; }
version_at() { git show "$1:app/build.gradle.kts" 2>/dev/null | sed -n 's/^val keysVersion = "\(.*\)"$/\1/p'; }

old_version=$(version_at "$merge_base")
new_version=$(version_at HEAD)
# A beta bump (0.3.2-beta.1 to -beta.2) is still work in progress; only a move to a plain version is a release.
release=no
[[ -n "$new_version" && "$old_version" != "$new_version" && "$new_version" != *-* ]] && release=yes

ships=$(echo "$changed" | grep -E '^app/src/main/' || true)
if [[ -z "$ships" ]]; then
    skip "REL-7  nothing under app/src/main changed"
elif has_label no-changelog; then
    skip "REL-7  waived by the no-changelog label"
elif [[ $release == yes ]]; then
    skip "REL-7  this is the $new_version release; its notes are the changelog section"
elif ! has_unreleased HEAD; then
    fail "REL-7  CHANGELOG.md has no '## [x.y.z] - Unreleased' section to add to.
        Open one for the next version and put this change's line in it."
else
    before=$(unreleased_bullets "$merge_base")
    after=$(unreleased_bullets HEAD)
    if (( after > before )); then
        pass "REL-7  $((after - before)) line(s) added to the Unreleased notes"
    else
        fail "REL-7  this changes what ships but adds no line to the Unreleased notes.
        Changed: $(echo "$ships" | head -3 | tr '\n' ' ')$([[ $(echo "$ships" | wc -l) -gt 3 ]] && echo '…')
        Write what a person will see, or label the pull request no-changelog if they will see nothing."
    fi
fi

exit $failed
