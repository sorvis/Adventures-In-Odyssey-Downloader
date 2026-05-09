#!/usr/bin/env bash
# Run the OneplaceClient JVM tests without Gradle / Android SDK.
#
# First run downloads JDK 21, the Kotlin compiler, and a handful of JARs
# into .tools/ (gitignored, ~250MB total). Subsequent runs reuse them.
#
# Why this exists: the Android scaffold lives in android/ behind the AGP,
# which needs an Android SDK to even evaluate. The OneplaceClient tests
# are pure JVM — no Android types — so we can compile and run them
# directly with kotlinc + java.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

TOOLS="$ROOT/.tools"
JDK_DIR="$TOOLS/jdk"
KOTLIN_DIR="$TOOLS/kotlinc"
LIBS="$TOOLS/libs"
BUILD="$TOOLS/build"
mkdir -p "$TOOLS" "$LIBS"

JDK_VERSION="21.0.5+11"
JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz"
KOTLIN_VERSION="2.0.21"
KOTLIN_URL="https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip"

step() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }

# ---------- 1. JDK ----------
if [[ ! -x "$JDK_DIR/bin/java" ]]; then
  step "Downloading JDK ${JDK_VERSION} (~200MB extracted)"
  curl -fsSL -o /tmp/jdk.tgz "$JDK_URL"
  rm -rf "$JDK_DIR" && mkdir -p "$JDK_DIR"
  tar -xzf /tmp/jdk.tgz -C "$JDK_DIR" --strip-components=1
  rm /tmp/jdk.tgz
fi
export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

# ---------- 2. Kotlin ----------
if [[ ! -x "$KOTLIN_DIR/bin/kotlinc" ]]; then
  step "Downloading Kotlin ${KOTLIN_VERSION} (~70MB)"
  curl -fsSL -o /tmp/kotlinc.zip "$KOTLIN_URL"
  rm -rf "$KOTLIN_DIR" /tmp/kotlinc-extract
  mkdir -p /tmp/kotlinc-extract
  unzip -q /tmp/kotlinc.zip -d /tmp/kotlinc-extract
  mv /tmp/kotlinc-extract/kotlinc "$KOTLIN_DIR"
  rm -rf /tmp/kotlinc.zip /tmp/kotlinc-extract
fi
export PATH="$KOTLIN_DIR/bin:$PATH"

# ---------- 3. Library JARs from Maven Central ----------
fetch() {
  local url="$1"
  local out="$LIBS/$(basename "$url")"
  if [[ ! -f "$out" ]]; then
    printf '    fetching %s\n' "$(basename "$url")"
    curl -fsSL -o "$out" "$url"
  fi
}

step "Resolving JAR dependencies"
REPO="https://repo1.maven.org/maven2"

# Production-side deps for OneplaceClient.kt
fetch "$REPO/javax/inject/javax.inject/1/javax.inject-1.jar"
fetch "$REPO/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar"
fetch "$REPO/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar"
fetch "$REPO/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.7.3/kotlinx-serialization-core-jvm-1.7.3.jar"
fetch "$REPO/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.7.3/kotlinx-serialization-json-jvm-1.7.3.jar"
fetch "$REPO/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.1/kotlinx-coroutines-core-jvm-1.8.1.jar"

# Test-only deps
fetch "$REPO/com/squareup/okhttp3/mockwebserver/4.12.0/mockwebserver-4.12.0.jar"
fetch "$REPO/junit/junit/4.13.2/junit-4.13.2.jar"
fetch "$REPO/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"
fetch "$REPO/org/jetbrains/kotlinx/kotlinx-coroutines-test-jvm/1.8.1/kotlinx-coroutines-test-jvm-1.8.1.jar"

# JaCoCo (code coverage). Agent jar is attached to the test JVM via -javaagent;
# CLI jar generates HTML/XML reports from the resulting jacoco.exec.
JACOCO_VERSION="0.8.12"
fetch "$REPO/org/jacoco/org.jacoco.agent/$JACOCO_VERSION/org.jacoco.agent-$JACOCO_VERSION-runtime.jar"
fetch "$REPO/org/jacoco/org.jacoco.cli/$JACOCO_VERSION/org.jacoco.cli-$JACOCO_VERSION-nodeps.jar"
JACOCO_AGENT="$LIBS/org.jacoco.agent-$JACOCO_VERSION-runtime.jar"
JACOCO_CLI="$LIBS/org.jacoco.cli-$JACOCO_VERSION-nodeps.jar"

# Kotlin stdlib + reflect ship with kotlinc and need to be on the runtime
# classpath for kotlin-compiled code.
KOTLIN_RUNTIME="$KOTLIN_DIR/lib/kotlin-stdlib.jar:$KOTLIN_DIR/lib/kotlin-reflect.jar"

# kotlinx.serialization needs its compiler plugin to generate KSerializers
# for @Serializable classes. The plugin ships with the kotlinc distribution.
SERIALIZATION_PLUGIN="$KOTLIN_DIR/lib/kotlinx-serialization-compiler-plugin.jar"
if [[ ! -f "$SERIALIZATION_PLUGIN" ]]; then
  echo "ERROR: kotlinx-serialization-compiler-plugin.jar not found in $KOTLIN_DIR/lib"
  ls "$KOTLIN_DIR/lib" | grep -i serial || true
  exit 1
fi

# kotlinc doesn't expand `dir/*` classpath syntax the way java does — we
# have to assemble an explicit list ourselves. (java does honour `dir/*`,
# but it's safer to use the same string everywhere.)
CP_LIBS="$(printf '%s:' "$LIBS"/*.jar)"
CP_LIBS="${CP_LIBS%:}"

# ---------- 4. Compile ----------
rm -rf "$BUILD"
mkdir -p "$BUILD/main" "$BUILD/test"

step "Compiling production sources"
kotlinc \
  -Xplugin="$SERIALIZATION_PLUGIN" \
  -cp "$CP_LIBS" \
  -d "$BUILD/main" \
  android/app/src/main/java/com/odyssey/scrape/OneplaceClient.kt \
  android/app/src/main/java/com/odyssey/player/PlaySource.kt \
  android/app/src/main/java/com/odyssey/player/PlaybackFormat.kt \
  android/app/src/main/java/com/odyssey/player/Mp3Validation.kt \
  android/app/src/main/java/com/odyssey/player/PlayAction.kt \
  android/app/src/main/java/com/odyssey/player/PositionPersistence.kt \
  android/app/src/main/java/com/odyssey/player/SeekTarget.kt \
  android/app/src/main/java/com/odyssey/catalog/AioCatalog.kt \
  android/app/src/main/java/com/odyssey/catalog/AioCatalogMatch.kt \
  android/app/src/main/java/com/odyssey/catalog/AlbumOwnership.kt \
  android/app/src/main/java/com/odyssey/ui/screens/RecentListing.kt \
  android/app/src/main/java/com/odyssey/debug/DebugLog.kt \
  android/app/src/main/java/com/odyssey/download/DownloadProgress.kt \
  android/app/src/main/java/com/odyssey/download/TransferRow.kt \
  android/app/src/main/java/com/odyssey/qr/ServerQrCodec.kt

step "Compiling test sources"
kotlinc \
  -Xplugin="$SERIALIZATION_PLUGIN" \
  -cp "$CP_LIBS:$BUILD/main" \
  -d "$BUILD/test" \
  android/app/src/test/java/com/odyssey/scrape/OneplaceClientTest.kt \
  android/app/src/test/java/com/odyssey/app/AndroidManifestTest.kt \
  android/app/src/test/java/com/odyssey/player/PlaySourceTest.kt \
  android/app/src/test/java/com/odyssey/player/PlaybackFormatTest.kt \
  android/app/src/test/java/com/odyssey/player/Mp3ValidationTest.kt \
  android/app/src/test/java/com/odyssey/player/PlayActionTest.kt \
  android/app/src/test/java/com/odyssey/player/PositionPersistenceTest.kt \
  android/app/src/test/java/com/odyssey/player/SeekTargetTest.kt \
  android/app/src/test/java/com/odyssey/catalog/AioCatalogMatchTest.kt \
  android/app/src/test/java/com/odyssey/catalog/AlbumOwnershipTest.kt \
  android/app/src/test/java/com/odyssey/ui/screens/RecentListingTest.kt \
  android/app/src/test/java/com/odyssey/debug/DebugLogTest.kt \
  android/app/src/test/java/com/odyssey/download/DownloadProgressTest.kt \
  android/app/src/test/java/com/odyssey/download/TransferRowTest.kt \
  android/app/src/test/java/com/odyssey/qr/ServerQrCodecTest.kt

# Test resources need to live on the runtime classpath for getResource() to find them.
cp -r android/app/src/test/resources/* "$BUILD/test/" 2>/dev/null || true

# Manifest test reads the manifest directly; expose its path via a system property.
MANIFEST_PATH="$ROOT/android/app/src/main/AndroidManifest.xml"

# ---------- 5. Run JUnit (with JaCoCo agent attached) ----------
COVERAGE_DIR="$TOOLS/coverage"
rm -rf "$COVERAGE_DIR"
mkdir -p "$COVERAGE_DIR"
JACOCO_EXEC="$COVERAGE_DIR/jacoco.exec"

step "Running tests (JaCoCo coverage on)"
java \
  -javaagent:"$JACOCO_AGENT"=destfile="$JACOCO_EXEC" \
  -Dodyssey.manifest="$MANIFEST_PATH" \
  -cp "$KOTLIN_RUNTIME:$CP_LIBS:$BUILD/main:$BUILD/test" \
  org.junit.runner.JUnitCore \
  com.odyssey.scrape.OneplaceClientTest \
  com.odyssey.app.AndroidManifestTest \
  com.odyssey.player.PlaySourceTest \
  com.odyssey.player.PlaybackFormatTest \
  com.odyssey.player.Mp3ValidationTest \
  com.odyssey.player.PlayActionTest \
  com.odyssey.player.PositionPersistenceTest \
  com.odyssey.player.SeekTargetTest \
  com.odyssey.catalog.AioCatalogMatchTest \
  com.odyssey.catalog.AlbumOwnershipTest \
  com.odyssey.ui.screens.RecentListingTest \
  com.odyssey.debug.DebugLogTest \
  com.odyssey.download.DownloadProgressTest \
  com.odyssey.download.TransferRowTest \
  com.odyssey.qr.ServerQrCodecTest

# ---------- 6. Coverage report ----------
step "Generating JaCoCo report"
java -jar "$JACOCO_CLI" report "$JACOCO_EXEC" \
  --classfiles "$BUILD/main" \
  --sourcefiles android/app/src/main/java \
  --xml "$COVERAGE_DIR/jacoco.xml" \
  --html "$COVERAGE_DIR/html" \
  >/dev/null

# Pull the top-level LINE counter out of the XML report. The first <counter
# type="LINE"…> child of the root <report> element is the project total.
python3 - "$COVERAGE_DIR/jacoco.xml" >"$COVERAGE_DIR/current.txt" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for c in root.findall("./counter"):
    if c.get("type") == "LINE":
        missed = int(c.get("missed", "0"))
        covered = int(c.get("covered", "0"))
        total = missed + covered
        pct = 0.0 if total == 0 else (covered * 100.0 / total)
        # Keep two decimal places — enough resolution for ratchet, not
        # so noisy that float drift trips the comparison.
        print(f"{pct:.2f} {covered} {total}")
        sys.exit(0)
sys.exit("no LINE counter in jacoco.xml")
PY

read -r COV_PCT COV_COVERED COV_TOTAL <"$COVERAGE_DIR/current.txt"
printf '\n    coverage: %s%% lines (%s/%s)\n' "$COV_PCT" "$COV_COVERED" "$COV_TOTAL"
printf '    html report: %s/html/index.html\n' "$COVERAGE_DIR"
