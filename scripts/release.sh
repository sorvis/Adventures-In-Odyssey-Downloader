#!/usr/bin/env bash
# Cut a new Android release end-to-end.
#
# Usage:
#   scripts/release.sh <version> [release notes…]
#   scripts/release.sh v0.1.2 "fix Check now race"
#   scripts/release.sh v0.1.2 < notes.md
#
# Steps (each idempotent — safe to re-run after a partial failure):
#   1. Run JVM tests (scripts/run-jvm-tests.sh). Aborts on failure.
#   2. Bump versionCode/versionName in android/app/build.gradle.kts to
#      match <version>. Commits + pushes if anything changed.
#   3. Create and push the <version> tag if it doesn't exist remotely.
#   4. Poll GitHub Actions until the tag-triggered workflow finishes.
#   5. Fetch the release via the GitHub API; assert an .apk asset is
#      attached.
#   6. Print the release URL + APK download URL on success.
#
# Designed to need no Personal Access Token or `gh` CLI — git-over-SSH
# triggers the release; the GitHub REST API is queried unauthenticated
# (sufficient for a public repo's read-only endpoints).
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

# --------------------- args ---------------------
if [[ $# -lt 1 ]]; then
  echo "usage: $0 <version> [release notes…]" >&2
  echo "       $0 v0.1.2 \"one-line summary\"" >&2
  exit 64
fi

VERSION="$1"; shift
if [[ ! "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "error: version must look like v0.1.2 (got: $VERSION)" >&2
  exit 64
fi
VERSION_NAME="${VERSION#v}"   # strip leading v

# Notes: remaining args joined, OR stdin if no remaining args and stdin is a pipe.
if [[ $# -gt 0 ]]; then
  NOTES="$*"
elif [[ ! -t 0 ]]; then
  NOTES="$(cat)"
else
  NOTES="Release ${VERSION}"
fi

step() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[fail]\033[0m %s\n' "$*" >&2; exit 1; }

# --------------------- repo coords ---------------------
REMOTE_URL=$(git remote get-url origin)
# git@github.com:sorvis/repo.git → sorvis/repo
# https://github.com/sorvis/repo.git → sorvis/repo
REPO="$(echo "$REMOTE_URL" | sed -E 's#^(git@github\.com:|https://github\.com/)##; s#\.git$##')"
[[ "$REPO" == */* ]] || fail "couldn't parse owner/name from origin: $REMOTE_URL"

# --------------------- step 1: tests ---------------------
step "Running JVM tests"
"$ROOT/scripts/run-jvm-tests.sh" >/tmp/odyssey-release-tests.log 2>&1 \
  || { tail -40 /tmp/odyssey-release-tests.log >&2; fail "tests failed; see /tmp/odyssey-release-tests.log"; }
echo "    tests: ok ($(grep -oE 'OK \([0-9]+ tests?\)' /tmp/odyssey-release-tests.log || echo 'see log'))"

# --------------------- step 2: version bump ---------------------
GRADLE="android/app/build.gradle.kts"
CURRENT_NAME=$(grep -oE 'versionName *= *"[^"]+"' "$GRADLE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
CURRENT_CODE=$(grep -oE 'versionCode *= *[0-9]+' "$GRADLE" | head -1 | awk '{print $NF}')

if [[ "$CURRENT_NAME" == "$VERSION_NAME" ]]; then
  step "Version already at $VERSION_NAME (code=$CURRENT_CODE) — no bump needed"
else
  NEW_CODE=$((CURRENT_CODE + 1))
  step "Bumping version: $CURRENT_NAME (code $CURRENT_CODE) → $VERSION_NAME (code $NEW_CODE)"
  # Replace versionCode and versionName lines in place.
  sed -i -E "s/versionCode *= *[0-9]+/versionCode = $NEW_CODE/" "$GRADLE"
  sed -i -E "s/versionName *= *\"[^\"]+\"/versionName = \"$VERSION_NAME\"/" "$GRADLE"

  if ! git diff --quiet "$GRADLE"; then
    git add "$GRADLE"
    git commit -m "Bump version to $VERSION_NAME for $VERSION release"
    step "Pushing version bump to master"
    git push origin master
  fi
fi

# --------------------- step 3: tag ---------------------
if git rev-parse "$VERSION" >/dev/null 2>&1; then
  warn "tag $VERSION already exists locally"
else
  step "Creating tag $VERSION"
  git tag -a "$VERSION" -m "$NOTES"
fi

if git ls-remote --tags origin "refs/tags/$VERSION" | grep -q "$VERSION"; then
  warn "tag $VERSION already pushed to origin — skipping push"
else
  step "Pushing tag $VERSION"
  git push origin "$VERSION"
fi

# --------------------- step 4: poll CI ---------------------
TAG_SHA=$(git rev-parse "$VERSION^{commit}")
step "Waiting for tag-triggered CI on $TAG_SHA (this usually takes 5-7 min)"

API="https://api.github.com/repos/$REPO/actions/runs?per_page=20"
DEADLINE=$(( $(date +%s) + 1200 ))   # 20-minute hard cap

while :; do
  STATUS=$(curl -fsS "$API" | python3 -c "
import sys, json
data = json.load(sys.stdin)
runs = [r for r in data.get('workflow_runs', [])
        if r['head_sha'] == '$TAG_SHA' and r.get('event') == 'push' and r.get('head_branch') == '${VERSION#v} '.strip().rstrip()]
# Match by tag ref OR head_branch == 'v...'
if not runs:
    runs = [r for r in data.get('workflow_runs', [])
            if r['head_sha'] == '$TAG_SHA' and r.get('head_branch') == '$VERSION']
if not runs:
    print('PENDING|no run for tag yet')
    sys.exit(0)
r = runs[0]
print(f\"{r['status']}|{r['conclusion'] or '-'}|{r['html_url']}\")
")
  IFS='|' read -r WF_STATUS WF_CONCLUSION WF_URL <<<"$STATUS"

  case "$WF_STATUS" in
    completed)
      if [[ "$WF_CONCLUSION" == "success" ]]; then
        echo "    CI: success ($WF_URL)"
        break
      else
        fail "CI ended with conclusion=$WF_CONCLUSION ($WF_URL)"
      fi
      ;;
    PENDING|in_progress|queued|requested|waiting|pending)
      printf '\r    waiting… %s' "${WF_STATUS:-pending}"
      ;;
    *)
      printf '\r    waiting… %s' "$WF_STATUS"
      ;;
  esac

  if (( $(date +%s) > DEADLINE )); then
    fail "timed out waiting for CI after 20 min ($WF_URL)"
  fi
  sleep 20
done
echo

# --------------------- step 5: verify release ---------------------
step "Verifying release asset"
RELEASE_JSON=$(curl -fsS "https://api.github.com/repos/$REPO/releases/tags/$VERSION") \
  || fail "release $VERSION not found on GitHub yet"

ASSET=$(echo "$RELEASE_JSON" | python3 -c "
import sys, json
d = json.load(sys.stdin)
apks = [a for a in d.get('assets', []) if a['name'].endswith('.apk')]
if not apks:
    sys.exit(1)
a = apks[0]
print(f\"{a['name']}|{a['size']}|{a['browser_download_url']}\")
") || fail "release $VERSION exists but has no .apk asset"

IFS='|' read -r APK_NAME APK_SIZE APK_URL <<<"$ASSET"
RELEASE_URL=$(echo "$RELEASE_JSON" | python3 -c 'import sys,json; print(json.load(sys.stdin)["html_url"])')

# --------------------- done ---------------------
cat <<EOF

\033[1;32m✔ release $VERSION published\033[0m

  release : $RELEASE_URL
  apk     : $APK_URL
            ($APK_NAME, $APK_SIZE bytes)

Obtainium on the phone will pick this up on its next refresh.
EOF
