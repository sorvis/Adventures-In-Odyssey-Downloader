#!/usr/bin/env bash
# One-shot status of the release pipeline. Replaces ad-hoc inspection
# (ps + cat lockfile + tail log) with a single bare invocation.
#
# Usage:
#   scripts/release-status.sh           # print state, exit 0
#   scripts/release-status.sh --cancel  # kill in-flight release + clear lock
#   scripts/release-status.sh --clear   # remove stale lock without killing
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
LOCK="$ROOT/.tools/release.lock"
LOG="${ODYSSEY_RELEASE_LOG:-/tmp/odyssey-release.log}"

# --------------------- flags ---------------------
case "${1:-}" in
  --cancel)
    if [[ -f "$LOCK" ]]; then
      pid="$(cat "$LOCK" 2>/dev/null || true)"
      if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
        echo "killing release.sh pid $pid"
        kill "$pid"
        # Give it a beat to clean up its trap; force if still alive.
        sleep 2
        if kill -0 "$pid" 2>/dev/null; then
          kill -9 "$pid" 2>/dev/null || true
        fi
      fi
      # Also sweep any orphan gradle daemons release.sh forked.
      pkill -f 'gradle' 2>/dev/null || true
      rm -f "$LOCK"
      echo "cleared $LOCK"
    else
      echo "no release in flight"
    fi
    exit 0
    ;;
  --clear)
    if [[ -f "$LOCK" ]]; then
      rm -f "$LOCK"
      echo "cleared $LOCK"
    else
      echo "no lock file to clear"
    fi
    exit 0
    ;;
  "")
    : # default: status
    ;;
  *)
    echo "usage: $0 [--cancel | --clear]" >&2
    exit 2
    ;;
esac

# --------------------- status read-only ---------------------
last_tag="$(git tag --sort=-v:refname --list 'v*' 2>/dev/null | head -1)"
last_tag="${last_tag:-none}"

if [[ ! -f "$LOCK" ]]; then
  printf 'release: idle\n'
  printf 'last tag: %s\n' "$last_tag"
  exit 0
fi

pid="$(cat "$LOCK" 2>/dev/null || true)"
if [[ -z "$pid" ]] || ! kill -0 "$pid" 2>/dev/null; then
  printf 'release: STALE LOCK (pid %s not running)\n' "${pid:-empty}"
  printf '         clear with: scripts/release-status.sh --clear\n'
  printf 'last tag: %s\n' "$last_tag"
  exit 0
fi

# Live: extract the most recent meaningful step from the log. The script
# uses `==> StepName` headers, so the last one is the current step.
step="$(grep -E '^\\?\[[0-9;]*m?==>' "$LOG" 2>/dev/null | tail -1 \
        | sed -E 's/^\\?\[[0-9;]*m?==>\\?\[[0-9;]*m? //' \
        | head -c 80)"
step="${step:-?}"

# Elapsed: ps's etime is friendlier than parsing log timestamps.
elapsed="$(ps -p "$pid" -o etime= 2>/dev/null | xargs || echo ?)"

printf 'release: in flight\n'
printf '   pid : %s   elapsed: %s\n' "$pid" "$elapsed"
printf '   step: %s\n' "$step"
printf '   log : %s\n' "$LOG"
printf '   tail: scripts/release-status.sh-cancel to kill\n'
printf 'last tag: %s\n' "$last_tag"
