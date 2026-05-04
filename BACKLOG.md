# Backlog

Future-work notes — short specs that are too big for an inline TODO and
not yet scheduled. Add new entries at the top; remove (or move to
"Landed") when a PR lands.

---

## Multi-show plugin abstraction — full path beyond H-lite

**H-lite landed** (commit TBD): `ShowProvider` interface, AIO wrapped
as `AioOneplaceProvider`, Hilt multibinding, `LocalEpisodeEntity`
gained `providerId` column with v2→v3 migration. `DailyCheckWorker`
iterates a `Set<ShowProvider>` (one entry today). PK stays
`episodeId: Long`; only AIO has `lastSeen` state; `MediaMetadata`
artist is still the hardcoded AIO string.

The product niche is **daily-aired Christian/educational radio
shows** (AIO + eventually Your Story Hour). Generic RSS is opportunistic
— support it later only if it falls out of the abstraction trivially.

What's still owed for full multi-show:

### Common surface — what every provider has to expose

```kotlin
interface ShowProvider {
    /** Stable, unique among providers — "aio", "ysh", "rss-<feedHash>". */
    val id: String
    /** "Adventures in Odyssey", "Your Story Hour", "Hardcore History". */
    val displayName: String
    /** Goes into MediaMetadata.artist for lockscreen display. */
    val artistName: String
    /** Optional album-art URL — shown in show selector + as fallback row art. */
    val showImageUrl: String?

    /** Pull episodes newer than `lastSeenExternalId`. Newest-first. */
    suspend fun newSince(lastSeenExternalId: String?, maxFetch: Int): List<ProviderEpisode>

    /** Optional per-episode enrichment hook (canonical # / better thumb). */
    fun enrich(externalId: String, title: String): EnrichedEpisode? = null
}

data class ProviderEpisode(
    /** Unique within this provider — CMS id, GUID, etc. */
    val externalId: String,
    val title: String,
    val airDate: String?,
    val description: String?,
    val downloadUrl: String,
    val sourceUrl: String?,
    val durationSeconds: Long,
    val imageUrl: String?,
)

data class EnrichedEpisode(
    val displayName: String? = null,    // canonical "#657: Clutter"
    val thumbnailUrl: String? = null,
    val albumName: String? = null,
)
```

### Concrete providers

- **AioOneplaceProvider** — wraps the existing `OneplaceClient`.
  `enrich()` consults `AioCatalogRepo`. `id = "aio"`.
- **YshProvider** — TODO: probe yourstoryhour.org; the public feed
  is likely a podcast RSS or a Wix/Squarespace catalog. Once the
  shape is known, this likely just delegates to a per-show RSS
  parser with custom artwork URLs hard-coded.
- **RssProvider(feedUrl, nickname)** — generic. User adds feeds in
  Settings ("New show" → URL → nickname). One instance per added
  feed; each gets `id = "rss-${sha256(feedUrl).take(8)}"`. No
  enrichment.

### Schema migration

`LocalEpisodeEntity` becomes provider-aware:

```kotlin
@Entity(
    tableName = "local_episodes",
    primaryKeys = ["providerId", "externalId"],
)
data class LocalEpisodeEntity(
    val providerId: String,
    val externalId: String,
    val title: String,
    val airDate: String?,
    // … remaining fields unchanged …
)
```

DB version v2 → v3:
- Add `providerId TEXT NOT NULL DEFAULT 'aio'` and `externalId TEXT NOT NULL` columns.
- Backfill `externalId = CAST(episodeId AS TEXT)` for existing rows.
- Drop the old `episodeId` PK; create composite `(providerId, externalId)`.
- Re-create indexes accordingly.

`PlaybackPositionEntity` likewise — its `episodeId` becomes `(providerId, externalId)`.

For Media3's `MediaItem.mediaId`, encode as `"${providerId}:${externalId}"`.
`PlayerController` parses on the way back. Pure helper:

```kotlin
fun encodeMediaId(providerId: String, externalId: String) = "$providerId:$externalId"
fun decodeMediaId(mediaId: String): Pair<String, String>? = ...
```

### Workers

- `DailyCheckWorker` becomes provider-aware: iterates the registered
  `ShowProvider`s, calls `newSince()` on each, persists rows tagged
  with `providerId`.
- `DownloadEpisodeWorker` keys its work by `(providerId, externalId)`.
- `RetentionWorker` runs per-provider so the user can keep different
  numbers of episodes per show.

### UI implications

- A **show selector** at the top of Recent / Library — defaults to
  "All". When set to a single show, list filters by `providerId`.
- Albums tab is AIO-only by definition (the catalog is AIO-specific);
  hide it for other shows.
- Settings page gets a "Manage shows" section: list registered
  shows, add an RSS feed by URL, remove.

### Build order from here

1. ✅ DownloadEnqueuer extracted (commit `408ed00`).
2. ✅ ShowProvider interface + AIO impl (H-lite, commit TBD).
3. **YSH next** — investigate yourstoryhour.org. If it's just an RSS
   feed, build a small `RssProvider(feedUrl, displayName, artistName)`
   first and YSH falls out for free as a config. If the data shape is
   weird, write a custom `YshProvider` directly. Decision waits on the
   actual scrape probe.
4. RSS as a generic provider only if YSH didn't already require it.

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

## Canonical AIO episode number (#657, etc.)

oneplace.com's API exposes only its CMS-internal `episodeId` (~1.27M
range — e.g. `1278294`). The CANONICAL Adventures in Odyssey episode
number — what listeners reference, what the official site shows next
to the title — is different. Verified: app.adventuresinodyssey.com
displays `#657: Clutter` for what oneplace calls episodeId 1278294.

The CMS id is *useful* as a stable foreign key but should never be
shown to users as "the episode number." v0.1.13 briefly rendered it
as `#1278294` in the row headline; v0.1.14 removed that — the row
title now stands alone until we have the real number.

**Where the real number comes from:** the AIO-app endpoints below
expose canonical episode/album numbering. Schema TBD until we capture
fixtures. Once captured, add `aioEpisodeNumber: Int?` to
`LocalEpisodeEntity` (Room migration) and render it in `EpisodeRow`'s
headline as `#<num> <title>` matching the official-site format.

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

- ~~**Settings → retention field looks broken.**~~ Likely resolved
  by the verticalScroll fix in commit `139bc20` — the field was
  below the fold and clipped, so the Save button wasn't reachable.
  Re-test on v0.1.16+ before reopening if it still misbehaves.

