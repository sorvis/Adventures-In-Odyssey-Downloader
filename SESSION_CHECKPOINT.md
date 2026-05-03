# Session checkpoint — 2026-05-03

Recoverable context if this session gets interrupted. Delete after demo
ships.

## Where we are

**Goal:** ship a v0.1.0 debug APK to Steven's phone via Obtainium so he
can listen to today's Odyssey episodes today.

**Done:**
- Commit `8fa9329` pushed to master. Includes:
  - `.github/workflows/android.yml` — CI build + test + APK upload, mirror
    of pantry-pal's pattern.
  - `android/maestro/flows/{daily_check,playback}.yaml` — E2E flows
    (Compose `testTag` selectors, `testTagsAsResourceId = true`).
  - LTE-download toggle: `Settings.allowMeteredDownloads` (default false),
    `WorkScheduler` switches `NetworkType.UNMETERED ↔ CONNECTED`,
    Settings UI Switch row, Recent screen pre-flight + AlertDialog with
    "Open settings" → nav to Settings tab.
  - `android/TESTING.md` — testing layers, CI design, distribution via
    Obtainium, release recipe.
- CI run on `8fa9329` is **green** — run id `25279192956`. Artifact
  `odyssey-debug` (~21 MB) is downloadable from the run page.

**In progress (next step in this session):**
- Switch the workflow to also trigger on `push: tags: ['v*']` so we can
  create a GitHub Release without a Personal Access Token (just by
  pushing a tag from this machine via SSH). `softprops/action-gh-release`
  auto-creates the release on tag push.
- Commit + push that change.
- Push `v0.1.0` tag.
- Wait for the tag-triggered CI run; verify it created the release and
  attached the APK.

**After ship:**
- Steven installs Obtainium on phone, points it at this repo.
- Manually verifies: see today's episode → tap row to download → tap to
  play. Maestro E2E cannot run from this dev box (no Android emulator,
  no `/dev/kvm`, LXC without nested virt).

## Auth state on this machine

- SSH key `~/.ssh/id_ed25519` (fingerprint
  `SHA256:FZquF9u88pkJ+ZEIL86hMwnDhVrIYTnlV7gVqbWkey4`) is added to
  Steven's GitHub account. `git push` and `git push --tags` work.
- `gh` CLI is **not installed**. No PAT available. Anything beyond
  git-over-SSH (e.g. POST to `/releases`, downloading workflow
  artifacts) needs the user to do it via the web UI or to provide a token.

## Key URLs

- Repo: https://github.com/sorvis/Adventures-In-Odyssey-Downloader
- Green CI run: https://github.com/sorvis/Adventures-In-Odyssey-Downloader/actions/runs/25279192956
- Releases (where v0.1.0 will land): https://github.com/sorvis/Adventures-In-Odyssey-Downloader/releases

## Things that might break

- `paths:` filter on the workflow still includes `android/**` — tag
  pushes match paths against the tagged commit's changed files. Since
  the tag will point at a commit that touches `android/**`, this should
  be fine, but if a future tag is cut against an unrelated commit, the
  workflow will silently not run.
- `softprops/action-gh-release@v2` requires `permissions: contents:
  write` (already set) and the default `GITHUB_TOKEN` (always available
  in workflow context) — no PAT needed.
