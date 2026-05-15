#!/usr/bin/env bash
# Headless live-network probe of the YSH download path. Compiles
# scripts/probe-ysh-download.kt against the production OneplaceClient
# and runs it with the same OkHttp config EpisodeDownloader uses.
#
# Counterpart to the Robolectric-based YshLiveDownloadSmokeTest. The
# Robolectric test needs the Android SDK to compile (full Gradle
# build); this script only needs .tools/jdk + .tools/kotlinc, which
# scripts/run-jvm-tests.sh already bootstraps. Run that script once
# first, or this one will exit until the bootstrap is in place.
#
# Prints: live YSH episode list (from oneplace), the GET request,
# response code + headers, downloaded byte count, and MP3 magic-byte
# check. Exits non-zero on any HTTP error or non-MP3 payload.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
TOOLS="$ROOT/.tools"
JDK_DIR="$TOOLS/jdk"
KOTLIN_DIR="$TOOLS/kotlinc"
LIBS="$TOOLS/libs"
BUILD="$TOOLS/build-probe"

if [[ ! -x "$JDK_DIR/bin/java" || ! -x "$KOTLIN_DIR/bin/kotlinc" ]]; then
  echo "ERROR: .tools/jdk or .tools/kotlinc missing. Run scripts/run-jvm-tests.sh first to bootstrap."
  exit 1
fi

export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$KOTLIN_DIR/bin:$PATH"

CP_LIBS=$(printf '%s:' "$LIBS"/*.jar)
CP_LIBS=${CP_LIBS%:}
KOTLIN_RUNTIME="$KOTLIN_DIR/lib/kotlin-stdlib.jar:$KOTLIN_DIR/lib/kotlin-reflect.jar"
SERIALIZATION_PLUGIN="$KOTLIN_DIR/lib/kotlinx-serialization-compiler-plugin.jar"

rm -rf "$BUILD"
mkdir -p "$BUILD"

kotlinc -Xplugin="$SERIALIZATION_PLUGIN" -cp "$CP_LIBS" -d "$BUILD" \
  android/app/src/main/java/com/odyssey/scrape/OneplaceClient.kt \
  scripts/probe-ysh-download.kt

java -cp "$KOTLIN_RUNTIME:$CP_LIBS:$BUILD" com.odyssey.scripts.Probe_ysh_downloadKt
