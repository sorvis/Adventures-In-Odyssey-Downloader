#!/usr/bin/env bash
# Stable-shape `git push` wrapper.
#
# Why: I (Claude) used to run `git push origin master 2>&1 | tail -N`
# with a different N each time, which made the harness re-prompt for
# permission on every push. This script always runs the same git push,
# captures the output to a stable log path, and prints only a tight
# summary line. Same command shape every run → permission granted once.
#
# Usage:
#   scripts/push.sh
#
# Exits non-zero on push failure; tails the log so the failure is visible.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
LOG="${ODYSSEY_PUSH_LOG:-/tmp/odyssey-push.log}"
mkdir -p "$(dirname "$LOG")"

if git push origin HEAD >"$LOG" 2>&1; then
  printf '\033[1;32m✔ push ok\033[0m   %s\n' "$(git log -1 --pretty='%h %s' HEAD)"
else
  printf '\033[1;31m✘ push failed\033[0m   log: %s\n' "$LOG" >&2
  echo "--- last 40 lines ---" >&2
  tail -40 "$LOG" >&2
  exit 1
fi
