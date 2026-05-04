#!/usr/bin/env bash
# Run the archive-service pytest suite with line coverage. Writes
# .tools/coverage/server-current.txt for the pre-push gate to consume,
# in the same shape as the Android JVM lane's current.txt.
#
# Usage:
#   archive-service/scripts/run-tests.sh
set -euo pipefail

cd "$(dirname "$0")/.."
SVC="$PWD"
ROOT="$(cd .. && pwd)"

VENV="$SVC/.venv"
COV_DIR="$ROOT/.tools/coverage"

step() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }

# ---------- 1. venv ----------
if [[ ! -x "$VENV/bin/python" ]]; then
  step "Creating Python venv at $VENV"
  python3 -m venv --copies "$VENV"
fi

step "Installing requirements"
"$VENV/bin/pip" install --quiet -r requirements.txt

# ---------- 2. run tests under coverage ----------
mkdir -p "$COV_DIR"
COV_FILE="$COV_DIR/server.exec"
COV_OUT="$COV_DIR/server-current.txt"

step "Running pytest with coverage"
COVERAGE_FILE="$COV_FILE" "$VENV/bin/python" -m coverage run \
  --source=app -m pytest tests/ -q

# ---------- 3. compute % ----------
# `coverage report --format=total` prints a single integer percent.
TOTAL=$(COVERAGE_FILE="$COV_FILE" "$VENV/bin/python" -m coverage report --format=total)
# Use Python for floor formatting to match the Android side's two-decimal precision.
PCT=$(printf '%s' "$TOTAL" | "$VENV/bin/python" -c '
import sys
v = sys.stdin.read().strip()
# `coverage` total can be int or float-ish ("87"); normalize to "87.00".
print(f"{float(v):.2f}")
')
echo "$PCT" >"$COV_OUT"
printf '\n    server coverage: %s%% lines  (written to %s)\n' "$PCT" "$COV_OUT"
