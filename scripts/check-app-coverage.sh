#!/usr/bin/env bash
# App-wide coverage ratchet (added 2026-05-22).
#
# Why this exists alongside the existing check-coverage.sh: that script
# measures coverage on the ~15 pure-JVM source files
# scripts/run-jvm-tests.sh hand-picks for the fast-lane compile, which
# is honest within its scope but misses entire packages (com.odyssey.nas,
# the Workers, the screens) that depend on Hilt/Room/OkHttp and only
# get tested under Robolectric. The user surfaced five releases of
# regressions in those uncovered packages.
#
# This script runs `:app:jacocoDebugReport` (defined in
# android/app/jacoco.gradle.kts) which measures coverage against the
# WHOLE app under Robolectric, then ratchets against
# `app-coverage-baseline.txt` with the same semantics as
# check-coverage.sh:
#
#   current  < baseline → exit 1, fail the push
#   current == baseline → exit 0, no change
#   current  > baseline → exit 0, write the new value, exit 1 so user
#                          commits the bump and re-pushes (forces
#                          ratchet to stay accurate)
#
# Per-package floors: app-coverage-floors.txt enumerates lower bounds
# per package — any package dropping below its floor fails the push.
# Bootstrapped at the v0.1.68 measured values so each package can only
# improve from here.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

REPORT_XML="$ROOT/android/app/build/reports/jacoco/jacocoDebugReport/jacocoDebugReport.xml"
BASELINE_FILE="$ROOT/app-coverage-baseline.txt"
FLOORS_FILE="$ROOT/app-coverage-floors.txt"

# Run the JaCoCo task if the report is missing (idempotent — gradle
# skips when up-to-date).
if [[ ! -f "$REPORT_XML" ]]; then
  printf '\n\033[1;34m==>\033[0m Running :app:jacocoDebugReport\n'
  (cd android && JAVA_HOME="${JAVA_HOME:-$PWD/../.tools/jdk}" \
                 ANDROID_HOME="${ANDROID_HOME:-$PWD/../.tools/android-sdk}" \
                 ./gradlew :app:jacocoDebugReport >/dev/null)
fi

if [[ ! -f "$BASELINE_FILE" ]]; then
  echo "error: $BASELINE_FILE not found — bootstrap by writing the current %% to it" >&2
  exit 2
fi

# Compute current top-level LINE coverage + per-package map.
PARSED=$(python3 - "$REPORT_XML" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()

def line_pct(node):
    for c in node.findall("./counter"):
        if c.get("type") == "LINE":
            m = int(c.get("missed", "0"))
            cov = int(c.get("covered", "0"))
            tot = m + cov
            return (0.0 if tot == 0 else cov * 100.0 / tot, cov, tot)
    return (0.0, 0, 0)

top_pct, top_cov, top_tot = line_pct(root)
print(f"TOTAL {top_pct:.2f} {top_cov} {top_tot}")
for pkg in root.findall("./package"):
    name = pkg.get("name")
    pct, cov, tot = line_pct(pkg)
    if tot > 0:
        print(f"PKG {name} {pct:.2f} {cov} {tot}")
PY
)

CURRENT_PCT=$(echo "$PARSED" | awk '$1=="TOTAL"{print $2}')
CURRENT_COV=$(echo "$PARSED" | awk '$1=="TOTAL"{print $3}')
CURRENT_TOT=$(echo "$PARSED" | awk '$1=="TOTAL"{print $4}')
BASELINE_PCT=$(tr -d '[:space:]' <"$BASELINE_FILE")

printf '\n    app coverage: %s%% lines (%s/%s)\n' "$CURRENT_PCT" "$CURRENT_COV" "$CURRENT_TOT"

# ---------- per-package floor check ----------
if [[ -f "$FLOORS_FILE" ]]; then
  FLOOR_VIOLATIONS=""
  while IFS=$'\t' read -r FLOOR_PKG FLOOR_PCT; do
    [[ -z "$FLOOR_PKG" || "$FLOOR_PKG" =~ ^# ]] && continue
    PKG_PCT=$(echo "$PARSED" | awk -v p="$FLOOR_PKG" '$1=="PKG" && $2==p{print $3}')
    if [[ -z "$PKG_PCT" ]]; then
      # Package not present in the report — likely renamed or removed.
      # Don't fail the gate on that, just note it.
      printf '    \033[1;33m⚠\033[0m  package %s in floors file but not in report\n' "$FLOOR_PKG" >&2
      continue
    fi
    DROPPED=$(python3 -c "print('yes' if float('$PKG_PCT') < float('$FLOOR_PCT') - 0.01 else 'no')")
    if [[ "$DROPPED" == "yes" ]]; then
      FLOOR_VIOLATIONS+=$(printf '       %-30s %s%% → %s%%\n' "$FLOOR_PKG" "$FLOOR_PCT" "$PKG_PCT")$'\n'
    fi
  done <"$FLOORS_FILE"
  if [[ -n "$FLOOR_VIOLATIONS" ]]; then
    printf '\033[1;31m✘ per-package coverage floors broken:\033[0m\n' >&2
    printf '%s' "$FLOOR_VIOLATIONS" >&2
    printf '   add tests in the dropping package, or lower its floor in %s\n' "$FLOORS_FILE" >&2
    exit 1
  fi
fi

# ---------- top-level ratchet ----------
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
    printf '\033[1;31m✘ app coverage regressed:\033[0m %s%% → %s%% (baseline: %s%%)\n' \
      "$BASELINE_PCT" "$CURRENT_PCT" "$BASELINE_PCT" >&2
    printf '   add tests, or update %s if the drop is intentional.\n' "$BASELINE_FILE" >&2
    exit 1
    ;;
  improved)
    printf '\033[1;32m↑ app coverage improved:\033[0m %s%% → %s%%\n' "$BASELINE_PCT" "$CURRENT_PCT"
    printf '%s\n' "$CURRENT_PCT" >"$BASELINE_FILE"
    printf '   updated %s — commit the bump before pushing.\n' "$BASELINE_FILE"
    exit 1
    ;;
  unchanged)
    printf '\033[1;32m✔ app coverage holds at %s%%\033[0m\n' "$CURRENT_PCT"
    exit 0
    ;;
esac
