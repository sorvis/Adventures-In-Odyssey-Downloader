#!/usr/bin/env bash
# Drift guard for the bare-kotlinc JVM test island (scripts/run-jvm-tests.sh).
#
# Why this exists: run-jvm-tests.sh compiles a HAND-PICKED set of pure-JVM
# sources + tests with plain kotlinc (no Android SDK). Membership can't be
# globbed — files that look pure-JVM (no direct android/androidx import)
# routinely pull in Android/Room transitively through com.odyssey.* or
# same-package references, so only the compiler knows the true closure.
# The failure mode that bit us (v0.1.84 dev): you write Foo.kt + FooTest.kt,
# both pure-JVM, forget to add them to run-jvm-tests.sh, and the test
# silently never runs — looks green because nothing ran it.
#
# This guard closes that gap. It enumerates every *Test.kt that LOOKS like
# a bare-runner candidate (imports nothing from the Android/Robolectric/
# mockk world) and fails if one is neither wired into run-jvm-tests.sh nor
# explicitly parked in scripts/jvm-test-skiplist.txt. So a new pure-JVM
# test forces a deliberate choice: wire it, or skiplist it (with a reason).
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
RUNNER="$ROOT/scripts/run-jvm-tests.sh"
SKIPLIST="$ROOT/scripts/jvm-test-skiplist.txt"

# A *Test.kt is a bare-runner candidate unless it imports from a world the
# bare kotlinc classpath can't satisfy. Robolectric/mockk imply the Gradle
# toolchain; android/androidx/dagger imply the Android SDK.
DENY='^import (android|androidx|dagger|org\.robolectric|io\.mockk)\.'

is_candidate() { ! grep -qE "$DENY" "$1"; }

# Tests the runner actually compiles: the src/test/*Test.kt paths listed in
# run-jvm-tests.sh's "Compiling test sources" kotlinc invocation.
mapfile -t WIRED < <(grep -oE 'android/app/src/test/java/[^ ]+Test\.kt' "$RUNNER" | sort -u)
is_wired() { local x; for x in "${WIRED[@]}"; do [[ "$x" == "$1" ]] && return 0; done; return 1; }

# Explicitly-parked tests (path per line, # comments stripped).
mapfile -t SKIP < <(grep -vE '^\s*(#|$)' "$SKIPLIST" | sed 's/[[:space:]]*$//' | sort -u)
is_skipped() { local x; for x in "${SKIP[@]}"; do [[ "$x" == "$1" ]] && return 0; done; return 1; }

missing=()
while IFS= read -r f; do
  is_candidate "$f" || continue
  is_wired "$f" && continue
  is_skipped "$f" && continue
  missing+=("$f")
done < <(find android/app/src/test/java -name '*Test.kt' | sort)

# Sanity: skiplist entries that no longer exist or are ALSO wired (stale /
# contradictory) — warn, don't fail, so a rename doesn't block a push.
stale=()
for s in "${SKIP[@]}"; do
  [[ -f "$ROOT/$s" ]] || { stale+=("$s (no such file)"); continue; }
  is_wired "$s" && stale+=("$s (also wired in run-jvm-tests.sh)")
done

if (( ${#stale[@]} )); then
  printf '\033[1;33m⚠ jvm-test-skiplist.txt has stale entries:\033[0m\n' >&2
  printf '   - %s\n' "${stale[@]}" >&2
fi

if (( ${#missing[@]} )); then
  printf '\033[1;31m✘ pure-JVM test(s) not wired into the bare-kotlinc runner:\033[0m\n' >&2
  printf '   - %s\n' "${missing[@]}" >&2
  cat >&2 <<'EOF'

   Each looks like a bare-JVM test (no android/androidx/robolectric/mockk
   import) but scripts/run-jvm-tests.sh never runs it. Pick one:
     • wire it in — add its source + test to the kotlinc lists (and its
       test classes to the JUnitCore run line) in scripts/run-jvm-tests.sh;
     • or park it — add its path to scripts/jvm-test-skiplist.txt with a
       one-line reason (it's covered by the Gradle Robolectric gate).
EOF
  exit 1
fi

printf '\033[1;32m✔ JVM test wiring: all %s candidate test(s) wired or skiplisted\033[0m\n' \
  "$(( ${#WIRED[@]} + ${#SKIP[@]} ))"
