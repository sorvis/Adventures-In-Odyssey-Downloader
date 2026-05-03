#!/usr/bin/env bash
# Run the AGP unit-test suite locally — Robolectric/Compose tests included.
#
# First run (per machine) bootstraps:
#   .tools/android-sdk/   ← cmdline-tools + platforms;android-35 + build-tools
#                           + platform-tools  (~1GB, gitignored)
#   android/gradlew       ← Gradle 8.10 wrapper (committed once we run it)
# Subsequent runs reuse them.
#
# Usage:
#   scripts/run-android-tests.sh                           # full debug unit-test suite
#   scripts/run-android-tests.sh --tests com.foo.BarTest   # any gradle-test args pass through
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

TOOLS="$ROOT/.tools"
JDK_DIR="$TOOLS/jdk"
SDK_DIR="$TOOLS/android-sdk"

step() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }

# ---------- 1. JDK (shared with run-jvm-tests.sh) ----------
if [[ ! -x "$JDK_DIR/bin/java" ]]; then
  echo "==> JDK missing — running run-jvm-tests.sh first to bootstrap it"
  "$ROOT/scripts/run-jvm-tests.sh" >/dev/null  # lays down .tools/jdk/
fi
export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

# ---------- 2. Android SDK ----------
if [[ ! -d "$SDK_DIR/cmdline-tools/latest" ]]; then
  step "Downloading Android cmdline tools (~150MB)"
  curl -fsSL -o /tmp/cmdline-tools.zip \
    https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  mkdir -p "$SDK_DIR/cmdline-tools"
  rm -rf /tmp/cmdline-tools-extract && mkdir /tmp/cmdline-tools-extract
  unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extract
  mv /tmp/cmdline-tools-extract/cmdline-tools "$SDK_DIR/cmdline-tools/latest"
  rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools-extract
fi
export ANDROID_HOME="$SDK_DIR"
export PATH="$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:$PATH"

if [[ ! -d "$SDK_DIR/platforms/android-35" ]]; then
  step "Installing platforms;android-35 build-tools;35.0.0 platform-tools (~1GB total)"
  yes | sdkmanager --licenses >/dev/null 2>&1
  sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools" >/dev/null
fi

# ---------- 3. Gradle wrapper (mirrors CI bootstrap) ----------
if [[ ! -f "$ROOT/android/gradlew" ]]; then
  step "Downloading Gradle 8.10 + generating wrapper"
  curl -fsSL https://services.gradle.org/distributions/gradle-8.10-bin.zip -o /tmp/gradle.zip
  rm -rf /tmp/gradle && mkdir -p /tmp/gradle
  unzip -q /tmp/gradle.zip -d /tmp/gradle
  ( cd "$ROOT/android" && /tmp/gradle/gradle-8.10/bin/gradle wrapper --gradle-version 8.10 )
  rm -rf /tmp/gradle.zip /tmp/gradle
fi

# ---------- 4. Run ----------
# Quiet by default: capture gradle output to a log; on success print a
# tight test-summary line; on failure show the last 40 lines of the log
# so callers don't have to chain `| tail -N` (which churns the harness's
# command-allowlist on every length tweak). Set ODYSSEY_VERBOSE=1 to
# stream gradle live.
step "Running ./gradlew :app:testDebugUnitTest $*"
cd "$ROOT/android"

LOG="$ROOT/.tools/build/last-android-test.log"
mkdir -p "$(dirname "$LOG")"

if [[ "${ODYSSEY_VERBOSE:-0}" == "1" ]]; then
  ./gradlew :app:testDebugUnitTest --no-daemon "$@" 2>&1 | tee "$LOG"
  STATUS="${PIPESTATUS[0]}"
else
  ./gradlew :app:testDebugUnitTest --no-daemon "$@" >"$LOG" 2>&1 &
  GRADLE_PID=$!
  # Heartbeat dot every ~6s so the user knows we're alive on long runs.
  while kill -0 "$GRADLE_PID" 2>/dev/null; do
    printf '.'
    sleep 6
  done
  printf '\n'
  wait "$GRADLE_PID"
  STATUS=$?
fi

# Summary from the JUnit report HTML.
REPORT="$ROOT/android/app/build/reports/tests/testDebugUnitTest/index.html"
if [[ -f "$REPORT" ]]; then
  COUNTS=$(grep -oE 'class="counter">[0-9]+</div>' "$REPORT" \
    | grep -oE '[0-9]+' | head -3 | tr '\n' ' ')
  read -r TOTAL FAILED IGNORED <<<"$COUNTS"
  if [[ "$STATUS" == "0" ]]; then
    printf '\033[1;32m✔ %s tests passed (%s ignored)\033[0m   log: %s\n' \
      "$TOTAL" "${IGNORED:-0}" "$LOG"
  else
    printf '\033[1;31m✘ %s tests, %s failed\033[0m   report: file://%s\n' \
      "$TOTAL" "$FAILED" "$REPORT"
    echo "--- last 40 lines of $LOG ---"
    tail -40 "$LOG"
  fi
else
  if [[ "$STATUS" == "0" ]]; then
    echo "BUILD SUCCESSFUL (no test report — task may have been UP-TO-DATE)   log: $LOG"
  else
    echo "BUILD FAILED   log: $LOG"
    echo "--- last 40 lines ---"
    tail -40 "$LOG"
  fi
fi
exit "$STATUS"
