# Session checkpoint — 2026-05-03 (mid-debug)

## Demo status

v0.1.0 is shipped to GitHub Releases. Steven installed it via Obtainium.
**Reported bug: tapping "Check now" pulls zero episodes.**

## What we've already determined about the bug

**The OneplaceClient (scrape layer) is NOT the cause.**

Evidence:
- Live API + listen page captured into fixtures on 2026-05-03 (see
  `android/app/src/test/resources/oneplace/`).
- 9 JVM unit tests written in `OneplaceClientTest.kt` (commit `d1e8b22`)
  exercise: bootstrap regex match, JSON deserialization, `newSince(0, 7)`
  walking pagination, lastSeen short-circuit, empty-page termination,
  HTTP error handling, missing-bootstrap handling.
- **CI run on `d1e8b22` is GREEN** — all 9 tests pass:
  https://github.com/sorvis/Adventures-In-Odyssey-Downloader/actions/runs/25279889651

So with mocked HTTP, the client works. The bug is downstream.

## Suspect list (ranked)

1. **Worker silently retrying** — `DailyCheckWorker` uses `runCatching`
   then `.getOrElse { Result.retry() }`. Any exception becomes a silent
   retry. From the user's POV, "Check now" appears to do nothing because
   no errors surface to the UI and Room rows are never inserted.
2. **OkHttp/TLS in production differs from tests** — MockWebServer is
   plaintext HTTP; production talks HTTPS to oneplace.com. Possible
   gzip/cert/SNI issues on the device that don't reproduce in JVM tests.
3. **Hilt wiring of `OneplaceClient`** — the client is `@Singleton` with
   `@Inject` constructor; Hilt should provide it via `AppModule.provideOkHttp()`.
   If injection fails, worker construction would fail outright (more
   visible than a silent retry, but worth checking with adb logcat).
4. **Room observer not refreshing** — less likely since `RecentScreen`
   uses `episodes.observeAll().stateIn(...)` which is a Flow that emits
   on DB mutation. But worth verifying that upserts complete before the
   worker returns success.

## What's committed but not yet useful

- `scripts/run-jvm-tests.sh` — self-bootstraps JDK 21 + Kotlin compiler
  + Maven JARs into `.tools/` (gitignored). **Has a bug**: passes
  `-cp ".tools/libs/*"` to kotlinc, but kotlinc doesn't expand the
  classpath glob the way java does. Need to switch to shell glob
  expansion: `-cp "$(printf '%s:' "$LIBS"/*.jar)"`.
- `.gitignore` updated with `.tools/`.

## Next steps when this session resumes

1. **Fix `scripts/run-jvm-tests.sh`** — replace the `-cp "$LIBS/*"`
   with shell glob expansion so kotlinc receives explicit JAR paths.
   Lines to change: `CP_LIBS="$LIBS/*"` → `CP_LIBS="$(printf '%s:' "$LIBS"/*.jar)"`.
   Then re-run; expect green.
2. **Then debug the actual bug** — the next observable signal we don't
   have is **adb logcat from the device while "Check now" runs**. Without
   logcat, three useful blind moves:
   - Add a UI-visible error surface so the worker can report failure
     (e.g. a Snackbar or a "Last error: ..." row in Settings, written
     by DailyCheckWorker on `Result.failure()`).
   - Replace the silent `Result.retry()` with a logged failure path that
     captures the exception class + message into DataStore.
   - Add a "Diagnose" button in Settings that runs `OneplaceClient.latestEpisodeId()`
     synchronously on a coroutine and shows the result/exception.
3. **Cut v0.1.1** with whichever of those debug surfaces is fastest, push
   tag, install via Obtainium, hit Check now, screenshot the result.

## Auth + tooling state

- SSH key on this LXC matches the one in Steven's GitHub account
  (fingerprint `SHA256:FZquF9u88pkJ+ZEIL86hMwnDhVrIYTnlV7gVqbWkey4`).
  `git push` works. `gh` not installed. No PAT.
- No JDK / Kotlin / Android SDK / Maestro / Docker on this box.
  `scripts/run-jvm-tests.sh` is the bootstrap; once it works, expect
  ~250MB in `.tools/` after first run.

## Key URLs

- Repo: https://github.com/sorvis/Adventures-In-Odyssey-Downloader
- v0.1.0 release: https://github.com/sorvis/Adventures-In-Odyssey-Downloader/releases/tag/v0.1.0
- Green test CI run: https://github.com/sorvis/Adventures-In-Odyssey-Downloader/actions/runs/25279889651
