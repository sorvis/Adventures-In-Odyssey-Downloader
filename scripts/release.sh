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

# Singleton lock — concurrent release.sh runs deadlock on the gradle
# daemon and the shared release log. fd 9 holds an advisory flock for
# the lifetime of the script; second invocation while one is in flight
# fails fast with the holder's pid so the caller can decide to wait or
# kill it instead of starting a doomed parallel run.
mkdir -p "$ROOT/.tools"
LOCK_FILE="$ROOT/.tools/release.lock"
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  holder="$(cat "$LOCK_FILE" 2>/dev/null || echo unknown)"
  printf 'error: another scripts/release.sh is already running (pid %s)\n' "$holder" >&2
  printf '       check progress: tail -f %s\n' "${ODYSSEY_RELEASE_LOG:-/tmp/odyssey-release.log}" >&2
  printf '       cancel it     : kill %s && rm -f %s\n' "$holder" "$LOCK_FILE" >&2
  exit 1
fi
echo "$$" >"$LOCK_FILE"
trap 'rm -f "$LOCK_FILE"' EXIT

# Mirror all stdout+stderr through tee to a stable log path so callers
# (and AIs) can run `scripts/release.sh` with the same command shape
# every time and read the result from a known location. Path is the same
# whether release.sh is run in foreground or background.
RELEASE_LOG="${ODYSSEY_RELEASE_LOG:-/tmp/odyssey-release.log}"
exec > >(tee "$RELEASE_LOG") 2>&1
printf '\033[2m(logging to %s)\033[0m\n' "$RELEASE_LOG"

# --------------------- args ---------------------
# Auto-bump support: if the first argument doesn't look like a version,
# infer the next one from `git tag --list 'v*'`. Bumps patch by default;
# pass --minor or --major to bump those instead.
#
# Usage:
#   scripts/release.sh                              # auto-bump patch, default notes
#   scripts/release.sh "fix the save-loop crash"    # auto-bump patch, custom notes
#   scripts/release.sh --minor "library tab + …"    # bump minor, reset patch to 0
#   scripts/release.sh --major "complete rewrite"   # bump major, reset minor+patch
#   scripts/release.sh v0.2.0 "explicit version"    # explicit override

bump_part="patch"
if [[ "${1:-}" == "--minor" ]]; then
  bump_part="minor"; shift
elif [[ "${1:-}" == "--major" ]]; then
  bump_part="major"; shift
fi

# Decide whether arg #1 is an explicit version or the start of release notes.
if [[ "${1:-}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  VERSION="$1"; shift
else
  # Auto-bump from latest `v*` tag (works on any host with the repo's
  # tag history fetched — release.sh's prior step pushes tags).
  LATEST=$(git tag --list 'v*' --sort=-v:refname | head -1)
  if [[ -z "$LATEST" ]]; then
    VERSION="v0.1.0"
  else
    IFS='.' read -r MAJ MIN PAT <<<"${LATEST#v}"
    case "$bump_part" in
      patch) VERSION="v${MAJ}.${MIN}.$((PAT + 1))" ;;
      minor) VERSION="v${MAJ}.$((MIN + 1)).0" ;;
      major) VERSION="v$((MAJ + 1)).0.0" ;;
    esac
  fi
  printf 'auto-bump (%s): latest %s → next %s\n' "$bump_part" "${LATEST:-none}" "$VERSION"
fi
VERSION_NAME="${VERSION#v}"   # strip leading v

# Notes resolution:
#   1. Explicit args: scripts/release.sh "notes string"
#   2. Stdin pipe:    scripts/release.sh < notes.md
#   3. Auto-derive:   bullet list of commit subjects since the last tag
#                     (semantic-release-style, but reading the git log
#                     directly — no Conventional Commits required).
#   4. Fallback:      "Release vX.Y.Z" when there's no prior tag and
#                     no explicit notes.
if [[ $# -gt 0 ]]; then
  NOTES="$*"
elif [[ ! -t 0 ]]; then
  NOTES="$(cat)"
elif [[ -n "${LATEST:-}" ]]; then
  DERIVED=$(git log --no-merges --pretty=format:"- %s" "${LATEST}..HEAD" 2>/dev/null || true)
  if [[ -n "$DERIVED" ]]; then
    NOTES="$DERIVED"
    printf 'auto-derived %s commit subjects since %s\n' "$(echo "$NOTES" | wc -l | tr -d ' ')" "$LATEST"
  else
    NOTES="Release ${VERSION}"
  fi
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
