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

- **Settings → retention field looks broken.** The control for
  "downloaded-episode retention" doesn't appear to behave correctly in
  the UI. Need to reproduce, narrow down (is it the editor, the saved
  value, or the worker that uses it?), and fix.
