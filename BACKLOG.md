# Backlog

Future-work notes — short specs that are too big for an inline TODO and
not yet scheduled. Add new entries at the top; remove (or move to
"Landed") when a PR lands.

---

## Open architecture question — finding old downloaded episodes when offline

The **Recent** tab is what its name says: a window onto the most-recent
episodes from oneplace.com's daily check. The **NAS** tab browses
remote-archived episodes via `archive-service`. There's currently no
view that shows **"all my downloaded episodes regardless of recency,
without needing the NAS"**. So:

- A fresh install + 60 days of usage = a phone full of MP3s the user
  can't get back to once they roll out of the Recent window.
- The retention worker also deletes "old" downloaded episodes silently.

**Options to consider** (pick when we tackle this):

1. **Rename Recent → Library** and stop pruning the list to a recent
   window. Library shows *every* `local_episodes` row, sorted by date.
   Simplest UX; "Recent" was always a misnomer for what the screen does.
2. **Add a Downloaded tab** that filters to `filePath != null`. Keeps
   Recent as a "what's new" view; adds a permanent "what's on disk"
   view. Requires a new screen + a 4th nav target (or absorbs the NAS
   tab).
3. **Rework the NAS tab → Library tab** that shows a unified Local +
   NAS view, with an "offline" filter that hides NAS-only rows. Most
   ambitious; consolidates the "browse my catalogue" UX.

(2) is probably the right answer for a personal app — minimal disruption
to existing screens, and the NAS tab keeps doing one specific thing.

---

## Better data sources for artwork + descriptions

Two new endpoints from the AIO mobile-web app that are richer than
oneplace.com's API:

- **`https://app.adventuresinodyssey.com/radio`** — per-episode metadata
  including (presumably) better description text and per-episode artwork
  URLs. Right now we get those from oneplace.com, where every episode
  shares the same generic show logo and descriptions are 1-sentence
  blurbs. Worth scraping/inspecting to see if AIO's own canonical app
  serves richer data.
- **`https://app.adventuresinodyssey.com/albums`** — the official AIO
  album list (Album 1 "The Adventure Begins" through current ~70+
  albums) with album-level cover art. Anchor for any future
  album-organized browse UX.

**Sketch:**

1. Capture live JSON/HTML from both endpoints (curl + save to
   `android/app/src/test/resources/aio-app/`) so we have offline
   fixtures.
2. Decide whether to keep oneplace.com as primary and use AIO-app as
   an enrichment layer, or switch entirely. Probably enrichment-layer
   first to avoid a big rewrite; merge data per-episode by matching on
   title + airDate (or by figuring out a shared episode key).
3. New `AioAppClient` parallel to `OneplaceClient` with JVM fixture
   tests.
4. Per-album cover cache: download once at album-list-load time, cache
   on disk under `files/album-art/<albumId>.jpg`. Wire into player's
   `MediaMetadata.artworkUri` so notification/lockscreen show real
   album art, not the generic logo.
5. Per-episode artwork: if /radio gives distinct images per episode,
   thread through (replaces the generic-logo fallback we have now).

**Tests:**

- Fixture-based JVM tests on the new client, same pattern as
  `OneplaceClientTest`.
- Pure helper for matching oneplace episodes to AIO-app records by
  title/date, JVM-testable.

---

## Per-episode album art (partially landed in v0.1.6)

**Done so far:**

- `imageUrl` parsed from oneplace JSON, threaded through
  `LocalEpisodeEntity` (DB v2 migration).
- `EpisodeRow` shows a 56dp Coil thumbnail in the leading slot.

**Still open:**

- Lockscreen / notification artwork — `PlayerController.playLocal/
  playStream` doesn't set `MediaMetadata.Builder().setArtworkUri(...)`
  yet, so Bluetooth/lockscreen still shows nothing.
- On-disk artwork cache so list rows render offline (today Coil hits
  the network on first load and caches in its own RAM/disk cache,
  which doesn't survive an uninstall and won't preload). Eager cache
  during daily check would fix this — see "Better data sources" above
  for where to source the actual images from.
- The fallback when `imageUrl` is null is currently empty space —
  needs a default placeholder (generic show icon).

---

# Bugs

- **Play button is broken in v0.1.6.** Reported after install. Worked
  in earlier releases. Most likely culprit is the P2.4 CacheDataSource
  wiring — `OdysseyPlaybackService` is now `@AndroidEntryPoint` with
  an injected `MediaCache`, and a Hilt-graph or SimpleCache-init failure
  would silently prevent the service from starting (and `MediaController`
  binding would hang). Diagnose with `adb logcat` filtering for
  `odyssey|exoplayer|hilt|fatalexception`. If P2.4 is at fault, revert
  the `setMediaSourceFactory` + the `@Inject MediaCache` until we add
  a service-construction Robolectric test.

- **Settings → retention field looks broken.** The control for
  "downloaded-episode retention" doesn't appear to behave correctly in
  the UI. Need to reproduce, narrow down (is it the editor, the saved
  value, or the worker that uses it?), and fix.

---

## Multi-show plugin abstraction (AIO + Your Story Hour + …)

The whole app is hardcoded for Adventures in Odyssey today: the
oneplace.com scraper, the `Adventures in Odyssey` strings in
`MediaMetadata`, the single-show retention model. We'll want to add
**Your Story Hour** next, and possibly more shows after that, so a
plugin-style abstraction makes sense before the second integration.

**Sketch:**

1. Define a `ShowProvider` interface with the surface of `OneplaceClient`
   plus a few static fields:
   ```kotlin
   interface ShowProvider {
       val showId: String          // "aio", "ysh"
       val displayName: String     // "Adventures in Odyssey"
       val artistName: String      // for MediaMetadata
       suspend fun latestEpisodeId(): Long?
       suspend fun newSince(lastSeen: Long, maxFetch: Int): List<ProviderEpisode>
       fun parseAirDate(raw: String?): Long  // each show may format differently
   }
   ```
2. `LocalEpisodeEntity` gains a `showId: String` column (Room migration
   v2→v3). All queries filter by active showId or show all when "All"
   is selected.
3. Each provider lives in its own package (`com.odyssey.show.aio`,
   `com.odyssey.show.ysh`) with its own scrape model, fixture tests,
   and any show-specific quirks.
4. Hilt provides a `Map<String, ShowProvider>` (multibinding). Workers
   iterate providers; UI shows a show-picker.
5. Settings: per-show NAS path, per-show retention, per-show
   allow-metered-downloads. Or a "global" toggle that applies to all.

**Where Your Story Hour data lives:** TBD — first step is to scrape /
inspect the YSH site/app and find a stable JSON or HTML endpoint, same
exercise as we did for oneplace.com.

**Why "plugin" but not actual runtime-loaded plugins:** for a personal
app with two shows, a sealed interface + per-package implementation is
plenty. Real OSGi-style plugins are overkill and bring classpath
isolation problems we don't need.

**Naming:** if we go this route, the project name "Adventures in
Odyssey Downloader" becomes a misnomer. Worth renaming the repo or at
least the app/applicationId. Lower priority than the abstraction
itself; we can ship multi-show under the current name first.
