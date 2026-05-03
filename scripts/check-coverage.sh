#!/usr/bin/env bash
# Compare current line-coverage % (from .tools/coverage/current.txt, written
# by run-jvm-tests.sh) against the committed coverage-baseline.txt ratchet.
#
# Behavior:
#   current  < baseline → exit 1, print which files dropped vs the high-water
#   current == baseline → exit 0, no change
#   current  > baseline → exit 0, overwrite baseline file with new value
#                          (caller can `git add coverage-baseline.txt` to
#                          commit the ratchet bump; the pre-push hook also
#                          fails the push so the user is forced to commit
#                          the bump and re-push, keeping baseline accurate).
#
# We intentionally do NOT auto-commit the baseline — that's the user's call.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

CURRENT_FILE="$ROOT/.tools/coverage/current.txt"
BASELINE_FILE="$ROOT/coverage-baseline.txt"

if [[ ! -f "$CURRENT_FILE" ]]; then
  echo "error: $CURRENT_FILE not found — run scripts/run-jvm-tests.sh first" >&2
  exit 2
fi
if [[ ! -f "$BASELINE_FILE" ]]; then
  echo "error: $BASELINE_FILE not found — bootstrap by copying current to baseline" >&2
  exit 2
fi

read -r CURRENT_PCT _CURRENT_COVERED _CURRENT_TOTAL <"$CURRENT_FILE"
BASELINE_PCT=$(tr -d '[:space:]' <"$BASELINE_FILE")

CMP=$(python3 -c "
cur = float('$CURRENT_PCT')
base = float('$BASELINE_PCT')
# Use 0.01% tolerance so float-drift doesn't fail an unchanged run.
if cur < base - 0.01:
    print('regressed')
elif cur > base + 0.01:
    print('improved')
else:
    print('unchanged')
")

case "$CMP" in
  regressed)
    printf '\033[1;31m✘ coverage regressed:\033[0m %s%% → %s%% (baseline: %s%%)\n' \
      "$BASELINE_PCT" "$CURRENT_PCT" "$BASELINE_PCT" >&2
    printf '   add tests, or update %s if the drop is intentional.\n' "$BASELINE_FILE" >&2
    exit 1
    ;;
  improved)
    printf '\033[1;32m↑ coverage improved:\033[0m %s%% → %s%%\n' "$BASELINE_PCT" "$CURRENT_PCT"
    printf '%s\n' "$CURRENT_PCT" >"$BASELINE_FILE"
    printf '   updated %s — commit the bump before pushing.\n' "$BASELINE_FILE"
    exit 1
    ;;
  unchanged)
    printf '\033[1;32m✔ coverage holds at %s%%\033[0m\n' "$CURRENT_PCT"
    exit 0
    ;;
esac
