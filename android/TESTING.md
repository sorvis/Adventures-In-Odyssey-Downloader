# Testing & distribution

Pattern lifted from the pantry-pal project — proven there, reusing here.

## Testing layers

### 1. JVM unit tests (`./gradlew test`)
Pure-Kotlin logic only — oneplace JSON parsing, retention math, NAS-config
validation, episode-row mapping. Fast, no device required, runs in CI on
every push.

### 2. Maestro E2E flows (`android/maestro/flows/*.yaml`)
Maestro is the playwright-equivalent for Android: YAML flows, blackbox,
auto-waits. Install once on the dev box:

```bash
curl -Ls "https://get.maestro.mobile.dev" | bash
```

Run a flow against a connected device or emulator (`adb devices`):

```bash
cd android
maestro test maestro/flows/<flow>.yaml
```

Flows we want (none written yet):
- `daily_check.yaml` — launch app, trigger manual sync, assert at least one
  episode row appears in the library.
- `playback.yaml` — open an episode, hit play, assert position advances and
  ±30s buttons work.
- `nas_archive.yaml` — configure a fake NAS endpoint (or skip if unset),
  trigger archive worker, assert success state shows in UI.

Element selectors should use Compose `Modifier.testTag("…")` so flows match
by id, not by visible text (LLM-style brittleness doesn't apply here, but
text changes do).

### 3. Manual smoke on real hardware
Always before tagging a release. The CI APK is debug-signed; install via
adb or Obtainium (see below) and walk the golden path: daily check fires,
new episode downloads, plays back, resume-position sticks, retention
prunes correctly.

## CI: GitHub Actions

`/.github/workflows/android.yml` (to be created, mirror of pantry-pal's):

- Triggers: push to master, PRs touching `android/**`, published releases,
  `workflow_dispatch`.
- Steps: checkout → JDK 21 (Temurin) → Android SDK 35 + build-tools 35.0.0
  → cache Gradle → bootstrap `gradle-wrapper.jar` if missing (one-time;
  Android Studio normally regenerates it locally) → `./gradlew test`
  → `./gradlew assembleDebug` → upload APK as workflow artifact.
- On `release: published`: also attach the APK to the GitHub Release via
  `softprops/action-gh-release@v2`.
- Concurrency group `android-${{ github.ref }}` with `cancel-in-progress`
  to drop superseded runs.

Permissions: `contents: write` (only used by the release-attach step).

## Distribution: GitHub Releases + Obtainium

No Play Store. The flow:

1. Tag a release in GitHub (`v0.1.0`, etc.) and publish it.
2. The `android` workflow fires on `release: published`, builds the debug
   APK, and attaches it to the release as an asset.
3. On the phone, [Obtainium](https://github.com/ImranR98/Obtainium) tracks
   the GitHub repo. When a new release lands, Obtainium notifies and
   one-tap installs.

One-time phone setup:
- Install Obtainium (F-Droid or its own GitHub releases).
- Add app → source URL = this repo's GitHub URL → Obtainium auto-detects
  the APK asset pattern.
- Enable "install in background" if you want fully silent updates
  (requires shizuku or device-owner).

Why not Play Store: personal-use app, no review process needed, debug
signing is fine, releases are infrequent.

Why not public F-Droid: requires manual reviewer process — slow.
Self-hosted F-Droid repo is a future option if Obtainium proves clunky.

Why debug-signed (not release-signed): no need to manage a keystore for a
personal app. If we ever want release signing, store the keystore as a
GitHub Actions secret and add a `signingConfigs` block — pantry-pal hasn't
needed this either.

## adb sideload (fastest dev loop)

Skip CI entirely while iterating:

```bash
cd android
./gradlew installDebug          # USB or wireless adb
```

## Cutting a release (first time = today's demo)

Goal: get a `v0.1.0` APK on the phone via Obtainium.

```bash
# 1. Push current work so CI sees it
git push origin master

# 2. Watch the android workflow turn green
gh run watch

# 3. Tag + create the GitHub Release (this fires the release-attach step)
gh release create v0.1.0 \
  --title "v0.1.0 — first demo build" \
  --notes "Daily check + local playback. NAS archival no-ops until configured." \
  --target master

# 4. CI re-runs on the release event and attaches the APK to the release.
#    Watch again until the asset shows up:
gh release view v0.1.0
```

On the phone:

1. Install [Obtainium](https://github.com/ImranR98/Obtainium/releases).
2. Add app → paste this repo's GitHub URL.
3. Obtainium finds `v0.1.0`, downloads the APK asset, installs it.
4. Open the app → tap **Check now** → episodes start downloading.
   - On WiFi: downloads kick off immediately.
   - On cellular: by default a dialog warns you and offers **Open
     settings**, which jumps to the LTE toggle ("Allow downloads on
     cellular"). Flip it on if you want to grab today's episodes off WiFi.
   When the ⬇ icon disappears from a row, tap it to play.

For subsequent updates: bump `versionCode` + `versionName` in
`android/app/build.gradle.kts`, push, then `gh release create v0.1.1 …`.
Obtainium will auto-notify on next check.
