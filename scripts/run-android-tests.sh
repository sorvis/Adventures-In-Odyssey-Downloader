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
step "Running ./gradlew :app:testDebugUnitTest $*"
cd "$ROOT/android"
./gradlew :app:testDebugUnitTest --no-daemon "$@"
