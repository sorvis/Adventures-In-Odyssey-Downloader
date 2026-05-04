#!/usr/bin/env bash
# Mirror of scripts/check-coverage.sh but for the archive-service Python
# pytest suite. Compares .tools/coverage/server-current.txt against
# archive-service/coverage-baseline.txt with the same ratchet semantics:
#
#   current  < baseline → exit 1, refuse to push
#   current == baseline → pass
#   current  > baseline → overwrite baseline, exit 1 so user commits the bump
#
# Caller (pre-push hook) invokes archive-service/scripts/run-tests.sh
# first to populate server-current.txt.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

CURRENT_FILE="$ROOT/.tools/coverage/server-current.txt"
BASELINE_FILE="$ROOT/archive-service/coverage-baseline.txt"

if [[ ! -f "$CURRENT_FILE" ]]; then
  echo "error: $CURRENT_FILE not found — run archive-service/scripts/run-tests.sh first" >&2
  exit 2
fi
if [[ ! -f "$BASELINE_FILE" ]]; then
  echo "error: $BASELINE_FILE not found — bootstrap by copying current to baseline" >&2
  exit 2
fi

CURRENT_PCT=$(tr -d '[:space:]' <"$CURRENT_FILE")
BASELINE_PCT=$(tr -d '[:space:]' <"$BASELINE_FILE")

CMP=$(python3 -c "
cur = float('$CURRENT_PCT')
base = float('$BASELINE_PCT')
if cur < base - 0.01:
    print('regressed')
elif cur > base + 0.01:
    print('improved')
else:
    print('unchanged')
")

case "$CMP" in
  regressed)
    printf '\033[1;31m✘ server coverage regressed:\033[0m %s%% → %s%% (baseline: %s%%)\n' \
      "$BASELINE_PCT" "$CURRENT_PCT" "$BASELINE_PCT" >&2
    exit 1
    ;;
  improved)
    printf '\033[1;32m↑ server coverage improved:\033[0m %s%% → %s%%\n' "$BASELINE_PCT" "$CURRENT_PCT"
    printf '%s\n' "$CURRENT_PCT" >"$BASELINE_FILE"
    printf '   updated %s — commit the bump before pushing.\n' "$BASELINE_FILE"
    exit 1
    ;;
  unchanged)
    printf '\033[1;32m✔ server coverage holds at %s%%\033[0m\n' "$CURRENT_PCT"
    exit 0
    ;;
esac
