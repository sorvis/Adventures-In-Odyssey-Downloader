# Backlog

Future-work notes — short specs that are too big for an inline TODO and
not yet scheduled. Add new entries at the top; remove an entry when its
PR lands.

---

## Per-episode album art

**What:** Each episode has its own image (cover art / show logo). Today
it's not surfaced anywhere — Recent rows are text-only, and the Media3
notification + lockscreen show no artwork. We should download the image
alongside the MP3 and feed it to ExoPlayer's `MediaMetadata.artworkUri`
so notifications, the in-app NowPlaying screen, and lockscreen controls
all show the correct art.

**Where the data already is:** `OneplaceClient`'s episode JSON has an
`imageUrl` field per item (and a nullable `imageUrlWebP`). Today we
only parse `title`, `airDate`, `downloadUrl`, etc. — image is
discarded. Sample fixture: `android/app/src/test/resources/oneplace/api_page1.json`.

**Sketch of the change:**

1. **Schema.** Add `imageUrl: String?` and `imagePath: String?` to
   `LocalEpisodeEntity` (Room migration — bump DB version, add a
   simple `ALTER TABLE` migration since we're not yet shipping
   destructive migrations).
2. **Scrape.** Parse `imageUrl` in `OneplaceClient.newSince()`. Add a
   fixture-based test (we already have JVM tests against captured
   responses — extend `OneplaceClientTest`).
3. **Download.** Either:
   - Extend `DownloadEpisodeWorker` to fetch the JPG/WebP after the
     MP3, store at `audio/<album>/<id>-<slug>.jpg`, mark `imagePath`
     in DB. Same pattern as the MP3 path.
   - Or add a tiny `DownloadArtworkWorker` that runs immediately on
     first sync, before MP3 download — so artwork shows up in the
     list even for not-yet-downloaded episodes.
   The latter is better UX; cost is one extra worker class. Recommend
   that path.
4. **Player.** In `PlayerController.playLocal/playStream`, set
   `MediaMetadata.Builder().setArtworkUri(...)` from the local image
   path (or remote URL fallback). This is what populates the
   notification + lockscreen + Bluetooth-display art.
5. **UI.** `EpisodeRow` gains a leading 56dp thumbnail loaded with
   Coil (`io.coil-kt:coil-compose:2.7.0` — single new dep). Falls
   back to a generic show icon when `imagePath` is null. Same on
   `NowPlayingScreen`.
6. **Streaming case.** When user taps a streamable row, we'd want art
   shown immediately even before MP3 starts. Two options: (a) load
   directly from `imageUrl` over the network for the player metadata,
   (b) eagerly cache the artwork on daily check so it's always on
   disk. (b) is better — small file, predictable cost, makes the
   list look right offline.

**Tests to add:**

- `OneplaceClient` unit test asserting `imageUrl` is parsed from the
  fixture.
- `DownloadArtworkWorker` happy-path with `MockWebServer` returning
  a tiny JPG (or just N bytes — we don't validate image content).
- Pure helper for building the on-disk image path from
  `(album, episodeId, slug)` — testable JVM-only.

**Caveats:**

- The current API returns the same generic show logo for every
  episode (all `imageUrl`s point at the same WebP). Per-episode art
  may not actually exist for AIO yet. The plumbing is still useful
  for showing *some* art in the notification, and lays the
  groundwork if the upstream API later returns distinct images.
- If we end up downloading the same bytes for every episode, dedupe
  by URL hash — store one file at `audio/<album>/_artwork/<sha>.jpg`
  and have multiple rows point at it.
- WebP support in Media3 metadata varies by Android version. Prefer
  `imageUrl` (JPG) over `imageUrlWebP` for `setArtworkUri` until
  proven otherwise on minSdk 26 devices.
