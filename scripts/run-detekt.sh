#!/usr/bin/env bash
# Run detekt locally using the .tools/jdk + .tools/android-sdk bootstraps.
#
# Same env-setup pattern as run-jvm-tests.sh; called by the pre-push hook
# so SwallowedException / RethrowCaughtException / EmptyCatchBlock
# violations fail the push before they hit CI.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
TOOLS="$ROOT/.tools"
JDK_DIR="$TOOLS/jdk"
SDK_DIR="$TOOLS/android-sdk"

if [[ ! -x "$JDK_DIR/bin/java" ]]; then
  echo "ERROR: .tools/jdk missing. Run scripts/run-jvm-tests.sh once to bootstrap." >&2
  exit 1
fi
if [[ ! -d "$SDK_DIR/platforms/android-35" ]]; then
  echo "ERROR: .tools/android-sdk/platforms/android-35 missing — Gradle AGP needs the Android SDK to evaluate :app." >&2
  exit 1
fi

export JAVA_HOME="$JDK_DIR"
export ANDROID_HOME="$SDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$ROOT/android"
./gradlew detekt --no-daemon --stacktrace
