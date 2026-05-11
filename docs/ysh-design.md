# Adding Your Story Hour (YSH) — design

Status: design, not yet implemented. Captures the probe results from
2026-05-10 and the architecture that follows.

## What the user wants

1. YSH lives alongside AIO in the Android app, but **the two shows are not
   mixed in the UI**. Active-show mode toggles which library you're
   browsing.
2. YSH library is **album-organized**, not chronological. It grows over
   time as new content becomes accessible.
3. Archive-service supports YSH as well, under its own folder structure on
   the NAS.

## Probe results (2026-05-10)

Captured JSON in [`ysh-probe/`](ysh-probe/) — reproduce with:

```bash
# yourstoryhour.org full paid catalog (88 albums, 1055 digital_track SKUs)
for p in 1 2 3 4 5; do
  curl -s "https://www.yourstoryhour.org/crud/product/skus?page=$p"
done > docs/ysh-probe/yourstoryhour-catalog.json    # merge by id

# Current rotating free pool (~7 tracks across 6 albums)
curl -s "https://www.yourstoryhour.org/crud/free-streaming" \
  > docs/ysh-probe/yourstoryhour-free-streaming.json

# oneplace.com YSH archive (walked /api/related-episodes back)
# → only ~9 episodes total available; pagination dies after 1272638
```

Key findings:

- **yourstoryhour.org** is an Angular SPA over a JSON API at `/crud/*`.
  `/crud/product/skus` returns 88 albums; each album's `skus` array
  contains pricing-SKU entries — `type=digital_album` (bundle ~$10.50),
  `type=physical` (CD ~$14), and **`type=digital_track`** (individual
  stories, $2 each). The digital_track titles are the per-story
  catalog — **1055 stories total** across the 88 albums.
- `/crud/free-streaming` returns the **currently-free** sample track per
  album. Only ~7 tracks free at any time; YSH rotates them periodically.
  The download_url is a public S3 URL (no auth).
- **oneplace.com syndicates YSH** at
  `/ministries/your-story-hour/listen/` using the same JSON API as AIO,
  but **only ~9 recent broadcast episodes** are accessible. Older YSH
  content isn't in oneplace's archive at all.
- **Title cross-match against catalog**: all 9 oneplace YSH episodes
  match a digital_track in the yourstoryhour.org catalog by normalized
  title (lowercase, punctuation stripped). **100% hit rate.** Examples:
  - oneplace 1277611 "The $14 Horse" → YSH album "Exciting Events Vol 17"
  - oneplace 1277616 "The Land of Uz" → YSH album "Bible Comes Alive 4"
  - oneplace 1272643 "A Promise to Keep" → YSH album "Passion of Jesus"
- **Free-pool overlap between sources is tiny.** Cross-check of
  oneplace's 9 recent broadcasts vs. yourstoryhour's 7 rotating free
  tracks yields **only 1 shared title** ("The Land of Uz"). The two
  surfaces are curated independently — oneplace runs on broadcast
  schedule, yourstoryhour rotates promotional free samples. Running
  both providers nets ~15 unique stories at any given moment vs. ~7
  from yourstoryhour alone, so both providers earn their keep. Dedup
  by `(providerId, externalId="ysh-sku-<sku_id>")` is mostly a
  defensive measure against the occasional overlap, not the main
  reason to share the externalId scheme.

### What this means architecturally

- The yourstoryhour.org catalog is the **album-metadata source of truth**.
  88 albums × ~12 stories each = 1055 logical tracks. Cover art, album
  names, track ordering all come from here.
- The yourstoryhour.org free-streaming pool is the **only legal download
  source** — but it's small (7 at a time) and slow-rotating.
- The oneplace.com YSH feed is a **second downloadable source** with
  ~9 episodes' depth. Every oneplace YSH episode matches an album track,
  so we can title-join to get proper album organization for that content.
- Daily-check ingestion from BOTH sources, deduped, lets us:
  - cover the rotating yourstoryhour free pool as it changes,
  - cover the rotating oneplace broadcast feed as it changes,
  - tag every track with album metadata regardless of which source it
    came from.
- The user's hope of "download a lot of albums at once from oneplace"
  doesn't pan out — oneplace's archive is shallow. The library grows
  over time via both rotating sources, not via one big backfill.

## Architecture

### Android — ShowProvider implementations

Two YSH providers, not one. They share the catalog index.

```kotlin
// show/YshCatalog.kt — pulls + caches the full digital_track index
@Singleton
class YshCatalog @Inject constructor(
    private val http: OkHttpClient,
    @ApplicationContext private val ctx: Context,
) {
    /**
     * Title-keyed lookup of YSH album metadata. Loaded from
     * yourstoryhour.org/crud/product/skus?page=1..N, walked
     * until pages are empty. Persists to a JSON file in app
     * filesDir so a brand-new install can run offline after
     * first load. Refreshed weekly by a worker.
     */
    suspend fun lookup(title: String): YshTrackMetadata?
    suspend fun refresh()    // walk all pages, persist
}

data class YshTrackMetadata(
    val skuId: Long,
    val albumId: Long,
    val albumName: String,
    val albumSlug: String,
    val albumImageUrl: String,   // absolute S3 URL
    val trackOrder: Int,
)
```

```kotlin
// show/YshFreeStreamProvider.kt — primary YSH source
@Singleton
class YshFreeStreamProvider @Inject constructor(
    private val http: OkHttpClient,
    private val catalog: YshCatalog,
) : ShowProvider {
    override val id = "ysh"        // single provider id for both sources
    override val displayName = "Your Story Hour"
    override val artistName = "Your Story Hour"

    override suspend fun newSince(lastSeenExternalId: String?, maxFetch: Int)
        : List<ProviderEpisode> {
        // Pull /crud/free-streaming → for each track, attach album
        // metadata. externalId = sku_id (definitive, stable).
        // Ignore lastSeenExternalId — this is a snapshot source.
    }
}
```

```kotlin
// show/YshOneplaceProvider.kt — secondary YSH source
@Singleton
class YshOneplaceProvider @Inject constructor(
    private val oneplace: OneplaceClient,
    private val catalog: YshCatalog,
) : ShowProvider {
    override val id = "ysh"        // SAME provider id — append-only join
    override val displayName = "Your Story Hour"
    override val artistName = "Your Story Hour"

    override suspend fun newSince(lastSeenExternalId: String?, maxFetch: Int)
        : List<ProviderEpisode> {
        val lastSeen = lastSeenExternalId?.toLongOrNull() ?: 0L
        return oneplace.newSince(LISTEN_URL, lastSeen, maxFetch)
            .mapNotNull { ep ->
                val meta = catalog.lookup(ep.title) ?: return@mapNotNull null
                // externalId = "ysh-sku-${meta.skuId}" so the
                // SAME story coming from oneplace and from the free
                // pool dedupes to one row keyed by sku_id.
                ProviderEpisode(
                    externalId = "ysh-sku-${meta.skuId}",
                    title = ep.title,
                    downloadUrl = ep.downloadFileUrl,    // oneplace CDN
                    albumName = meta.albumName,
                    albumImageUrl = meta.albumImageUrl,
                    albumTrackOrder = meta.trackOrder,
                    // …
                )
            }
    }
    companion object {
        const val LISTEN_URL =
            "https://www.oneplace.com/ministries/your-story-hour/listen/"
    }
}
```

Important: **both YSH providers share `id = "ysh"` and use the same
`externalId` scheme keyed off `sku_id`**. So if "The Land of Uz" appears
in both the free pool and oneplace's stream on the same day, the second
ingestion sees the row already exists (by `(providerId, externalId)`) and
skips. No duplicate downloads.

The free-pool provider derives `externalId = "ysh-sku-${sku_id}"` from
the API response directly. The oneplace provider derives it via catalog
title-match → sku_id. The two paths converge on one identifier.

When `YshOneplaceProvider` encounters a title not in the catalog, it
skips the episode (`mapNotNull` returns null) rather than ingesting an
album-less row. That's intentional — better to lose the rare unmatched
episode than to pollute the album view with orphans. Log + surface a
"unmatched titles" count in Debug.

### `OneplaceClient` refactor

Today `listenUrl` is a `var` on a singleton. Make the listen URL a
method arg so one client serves multiple shows:

```kotlin
suspend fun latestEpisodeId(listenUrl: String): Long?
suspend fun newSince(listenUrl: String, lastSeen: Long, maxFetch: Int = 100)
    : List<OneplaceEpisode>
```

`AioOneplaceProvider` passes the AIO listen URL; `YshOneplaceProvider`
passes the YSH listen URL.

### Schema migration v3 → v4

`LocalEpisodeEntity` already gained `providerId` in v3. v4 makes the
implications mandatory:

```kotlin
@Entity(
    tableName = "local_episodes",
    primaryKeys = ["providerId", "externalId"],
)
data class LocalEpisodeEntity(
    val providerId: String,
    val externalId: String,            // was Long episodeId
    val title: String,
    val airDate: String?,
    val description: String?,
    val sourceUrl: String,
    val downloadUrl: String,
    val filePath: String?,
    val fileSize: Long,
    val durationMs: Long,
    val downloadedAt: Long?,
    val archivedAt: Long?,
    val imageUrl: String?,
    val albumName: String?,            // NEW
    val albumImageUrl: String?,        // NEW
    val albumTrackOrder: Int?,         // NEW
)
```

Why mandatory now: YSH `sku_id` is in the 2000-range; AIO uses canonical
broadcast numbers (~657) when the catalog matches. They will collide on
`episodeId: Long`. The composite PK has been on the BACKLOG since H-lite
landed — YSH is the trigger.

`PlaybackPositionEntity` mirrors the change: composite PK on
`(providerId, externalId)`.

`MediaItem.mediaId` encoding (already in the BACKLOG sketch):

```kotlin
fun encodeMediaId(providerId: String, externalId: String) = "$providerId:$externalId"
fun decodeMediaId(s: String): Pair<String, String>? = s.split(":", limit=2)
    .takeIf { it.size == 2 }?.let { it[0] to it[1] }
```

Migration body:

```sql
-- v3 → v4
-- 1. Rename old table
ALTER TABLE local_episodes RENAME TO local_episodes_v3;

-- 2. Create new table with composite PK
CREATE TABLE local_episodes (
    providerId        TEXT NOT NULL,
    externalId        TEXT NOT NULL,
    title             TEXT NOT NULL,
    airDate           TEXT,
    description       TEXT,
    sourceUrl         TEXT NOT NULL,
    downloadUrl       TEXT NOT NULL,
    filePath          TEXT,
    fileSize          INTEGER NOT NULL,
    durationMs        INTEGER NOT NULL,
    downloadedAt      INTEGER,
    archivedAt        INTEGER,
    imageUrl          TEXT,
    albumName         TEXT,
    albumImageUrl     TEXT,
    albumTrackOrder   INTEGER,
    PRIMARY KEY (providerId, externalId)
);

-- 3. Backfill — every v3 row is AIO; externalId = old episodeId as text
INSERT INTO local_episodes (providerId, externalId, title, airDate, …)
SELECT providerId, CAST(episodeId AS TEXT), title, airDate, …
FROM local_episodes_v3;

DROP TABLE local_episodes_v3;

-- 4. PlaybackPositionEntity mirrors
-- (same pattern)
```

### Per-provider lastSeen

`SettingsRepo` stops single-keying lastSeen. Per-provider DataStore keys:

```kotlin
private fun lastSeenKey(providerId: String) =
    stringPreferencesKey("last_seen_external_id__$providerId")

fun lastSeenFor(providerId: String): Flow<String?> =
    ctx.dataStore.data.map { p ->
        p[lastSeenKey(providerId)]
            ?: if (providerId == "aio")
                  p[Keys.LAST_SEEN_EID]?.toString()?.takeIf { it != "0" }
               else null
    }

suspend fun setLastSeen(providerId: String, externalId: String) =
    ctx.dataStore.edit { it[lastSeenKey(providerId)] = externalId }
```

The legacy `LAST_SEEN_EID` long key stays readable so existing v0.1.16
users don't lose their AIO cursor on upgrade.

YSH's free-stream provider never writes lastSeen (snapshot source).
YSH's oneplace provider does, scoped under `provider.id = "ysh"`. Wait —
both YSH providers share `id = "ysh"`. They'd stomp each other's
lastSeen. Resolution:
- Use a sub-key for the lastSeen-bearing provider only:
  `last_seen_external_id__ysh__oneplace`.
- DailyCheckWorker decides per-provider what key to read/write.
- Free-stream provider's lastSeen call is a no-op.

Implement this via an internal `lastSeenStateKey: String?` on each
`ShowProvider`:

```kotlin
interface ShowProvider {
    val id: String
    val displayName: String
    val artistName: String
    /** Key suffix for lastSeen state. Null = snapshot source, no
     *  lastSeen persisted. */
    val lastSeenStateKey: String? get() = id
    suspend fun newSince(lastSeenExternalId: String?, maxFetch: Int)
        : List<ProviderEpisode>
}

// YshFreeStreamProvider overrides:  override val lastSeenStateKey = null
// YshOneplaceProvider  overrides:   override val lastSeenStateKey = "ysh__oneplace"
// AIO uses the default (returns "aio").
```

### DailyCheckWorker

```kotlin
override suspend fun doWork(): Result = runCatching {
    val s = settings.flow.first()

    val fetched = providers.flatMap { provider ->
        val lastSeen = provider.lastSeenStateKey
            ?.let { settings.lastSeenFor(it).first() }
        val cap = if (lastSeen == null) 7 else 50
        provider.newSince(lastSeen, cap).map { provider to it }
    }

    if (fetched.isEmpty()) {
        settings.setLastRun(System.currentTimeMillis())
        return@runCatching Result.success()
    }

    // Dedup by (providerId, externalId) — same story coming from
    // YshFreeStream and YshOneplace must collapse to one row.
    val keys = fetched.map { (p, e) -> p.id to e.externalId }
    val existing = episodes.existingComposite(keys).toSet()
    val seen = mutableSetOf<Pair<String,String>>()

    for ((provider, ep) in fetched) {
        val key = provider.id to ep.externalId
        if (key in existing || key in seen) continue
        seen += key
        episodes.upsert(/* row built from ep, providerId = provider.id */)
        scheduler.enqueueDownload(provider.id, ep.externalId,
                                  allowMetered = s.allowMeteredDownloads)
    }

    // Per-provider lastSeen — only for providers that opt into it
    fetched.groupBy({ it.first }, { it.second })
           .forEach { (provider, eps) ->
               provider.lastSeenStateKey?.let { key ->
                   settings.setLastSeen(key, eps.first().externalId)
               }
           }
    settings.setLastRun(System.currentTimeMillis())
    Result.success()
}.getOrElse { Result.retry() }
```

Per-provider fresh-install cap (`7 vs 50`) means an existing AIO user
turning on YSH gets a small first batch (7 YSH episodes for free pool +
7 for oneplace YSH), not a 100-episode dump.

### Player `MediaMetadata.artist`

Inject `Set<ShowProvider>` into `PlayerController` (or a lightweight
`ProviderRegistry` wrapper exposing `byId: Map<String, ShowProvider>`).
When building `MediaItem` from a row, read `providerById[row.providerId]
?.artistName ?: "Adventures in Odyssey"`.

### App-mode UI: "active show" switcher

New DataStore key: `activeShow: String` (default `"aio"`). Persists
across launches.

`OdysseyNav.kt` reads `activeShow` and renders different destination
sets per mode:

- **AIO mode (`activeShow=aio`)**:
  Recent · Albums · Downloaded · NAS · Settings (unchanged from today).
- **YSH mode (`activeShow=ysh`)**:
  Albums · Downloaded · Settings. (No Recent — there's no chronological
  daily feed in our YSH model. No NAS tab — see archive-service section
  below for the longer-term answer.)
- **Shared regardless of mode**: Now Playing, Debug.

The **show switcher** is a compact dropdown in the top app bar showing
the active show's `displayName` + chevron. Tap → menu listing every
registered `ShowProvider`'s `displayName`. Selecting one calls
`settings.setActiveShow(it.id)`. The whole nav rebinds reactively.

```kotlin
// ui/components/ShowSwitcher.kt
@Composable
fun ShowSwitcher(navController: NavController) {
    val active by settings.activeShowFlow.collectAsState(initial = "aio")
    val enabledIds by settings.enabledProvidersFlow.collectAsState(initial = setOf("aio"))
    val unmatchedCount by yshUnmatched.observeCount().collectAsState(initial = 0)
    val providers = LocalProviderRegistry.current
    val enabled = providers.all.filter { it.id in enabledIds }
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(providers.byId(active)?.displayName ?: "—")
            if (unmatchedCount > 0) Badge(modifier = Modifier.padding(start = 4.dp))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            enabled.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.displayName) },
                    leadingIcon = {
                        if (p.id == active) Icon(Icons.Default.Check, null)
                    },
                    onClick = {
                        scope.launch { settings.setActiveShow(p.id) }
                        expanded = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Manage shows…") },
                leadingIcon = { Icon(Icons.Default.Settings, null) },
                onClick = {
                    expanded = false
                    navController.navigate("settings?section=shows")
                },
            )
        }
    }
}
```

The "Manage shows…" entry deep-links to a `?section=shows` anchor on
the Settings screen so the user lands at the right control without
scrolling. SettingsScreen consumes the query param to scroll/highlight
the corresponding card.

### YSH Albums screen

Distinct from AIO Albums (which is backed by `AioCatalogRepo`). YSH
Albums is a query against `local_episodes`:

```kotlin
@Query("""
  SELECT albumName,
         MIN(albumImageUrl)        AS coverUrl,
         COUNT(*)                  AS trackCount,
         SUM(CASE WHEN filePath IS NOT NULL THEN 1 ELSE 0 END) AS downloadedCount
  FROM local_episodes
  WHERE providerId = 'ysh' AND albumName IS NOT NULL
  GROUP BY albumName
  ORDER BY albumName ASC
""")
fun observeYshAlbums(): Flow<List<YshAlbumSummary>>
```

Tap album → list its tracks ordered by `albumTrackOrder` then `title`.
Tap track → play. Tracks without `filePath` show a "Tap to download"
state (since YSH downloads are gated to the rotating pool, lots of
tracks visible in the catalog won't have audio yet — that's expected
and is the "library grows over time" UX).

(Implication: should the YSH Albums view show albums you have NO tracks
from yet — i.e. the whole 88-album catalog as placeholders, with
"0/12 downloaded"? Or only albums you have at least one track from?
**Recommendation: only show albums with at least one local track.**
Otherwise the screen is a 88-album wall of empties. Add a "Browse full
catalog" subscreen if the user wants to wishlist albums later.)

## archive-service changes

NAS layout today: `<AUDIO_DIR>/<album-slug>/<episode_id>-<title>.mp3`.
Episodes table PK is `episode_id INTEGER`. Both are AIO-shaped.

**Scope reminder**: per user direction, pin-from-backup for YSH lands
with this work, not deferred. That means archive-service work is IN the
main YSH PR series, not phase 2 — see Build Order steps 10-12.

For YSH:

### Filesystem migration

```
Before:                                    After:
audio/                                     audio/
  the-adventure-begins/                      aio/
    1278294-clutter.mp3                        the-adventure-begins/
                                                 1278294-clutter.mp3
                                             ysh/
                                               bible-comes-alive-4/
                                                 ysh-sku-1850-the-land-of-uz.mp3
```

On service startup: if `<AUDIO_DIR>/aio/` doesn't exist AND any
top-level slug directory exists, move existing slug dirs into `aio/`.
Update `episodes.file_path` rows in the same transaction. Idempotent:
runs once, no-ops thereafter.

### Schema migration

```sql
-- v1 → v2
ALTER TABLE episodes ADD COLUMN provider_id TEXT NOT NULL DEFAULT 'aio';
ALTER TABLE episodes ADD COLUMN external_id TEXT;   -- mirrors Android
UPDATE episodes SET external_id = CAST(episode_id AS TEXT);

-- Composite uniqueness — episode_id alone is no longer unique across providers
CREATE UNIQUE INDEX idx_episodes_provider_external
    ON episodes(provider_id, external_id);
```

`episode_id INTEGER PRIMARY KEY` stays for now (rowid-stable identifier
for the audio API endpoint), but business logic keys on
`(provider_id, external_id)`.

### Storage helper

```python
# app/storage.py
def episode_path(provider_id: str, external_id: str, title: str,
                 album: str | None) -> Path:
    album_dir = slugify(album) if album else "unsorted"
    fname = f"{external_id}-{slugify(title)}.mp3"
    return AUDIO_DIR / provider_id / album_dir / fname
```

### Routes

Provider belongs in the path, not a query param — it's part of the
resource identity. New resource shape, with the old AIO-only paths
preserved as deprecated aliases for v0.1.30 clients still in the wild.

```python
# === New (preferred) ===
POST   /providers/{provider}/episodes                       # multipart upload
GET    /providers/{provider}/episodes                       # paged listing
GET    /providers/{provider}/episodes/{external_id}         # one episode
GET    /providers/{provider}/episodes/{external_id}/audio   # range stream
GET    /providers/{provider}/albums                         # distinct album dirs

# === Deprecated aliases (default provider=aio) ===
POST   /episodes                          → /providers/aio/episodes
GET    /episodes                          → /providers/aio/episodes
GET    /episodes/{external_id}            → /providers/aio/episodes/{external_id}
GET    /episodes/{external_id}/audio      → /providers/aio/episodes/{external_id}/audio
GET    /albums                            → /providers/aio/albums
```

Aliases stay until v0.2.0 ships and all phones are updated.

Handler shape:

```python
# routes/episodes.py
@router.post("/providers/{provider}/episodes", response_model=EpisodeOut, status_code=201)
async def create_episode(
    provider: str,                                  # "aio" | "ysh"
    external_id: Annotated[str, Form()],            # required
    title: Annotated[str, Form()],
    album: Annotated[str | None, Form()] = None,
    # … existing fields …
    audio: UploadFile = File(...),
):
    # Dedup by (provider, external_id). enrich_album is AIO-only;
    # YSH always sends album directly from its catalog ingest.
    if provider == "aio":
        album = album or enrich_album(title)
    out_path = episode_path(provider, external_id, title, album)
    # …
```

### `scrape_aio.enrich_album`

AIO-only. YSH album info travels with the upload payload — no
server-side scrape. Don't wire YSH into the AIO catalog scraper.

## YSH file imports (server-side drop-folder)

User has the paid YSH album library as MP3 files on disk (purchased
CDs / digital downloads) and wants to push them all onto the NAS at
once instead of waiting for the rotation pools to dribble them in.
Mirrors the existing AIO `import_dropbox.py` workflow:

```
1. User SCPs / NFS-copies MP3s into /data/import/ (or /data/import/ysh/).
2. User runs `scripts/run-import.sh --provider ysh` on the LXC.
3. Server walks the directory, identifies provider per-file (see
   below), looks each one up in the YSH catalog, files into
   /data/audio/ysh/<album-slug>/<sku_id>-<title>.mp3, inserts row.
4. Unmatched files move to /data/import/_unmatched/ for rename + retry.
```

### Filename conventions (verified from yourstoryhour.org S3 URLs)

YSH MP3 names follow a tight pattern from the publisher:

```
EE-11-02 - Madeleine's Courage.mp3
GS-07-05 - Eli Whitney - Boy Craftsman.mp3
B-4-02 - The Land of Uz.mp3
A-08-19 - What Joe Learned.mp3
```

Structure: `<CODE>-<VOL>-<TRACK> - <Title>.mp3`. The code prefix is
per-series:

| Code | Series                    |
|------|---------------------------|
| `EE` | Exciting Events           |
| `GS` | Great Stories             |
| `B`  | Bible Comes Alive         |
| `A`  | Adventures in Life        |
| _…_  | (others derived at catalog-refresh time, see below) |

The full code map is built when the YSH catalog is refreshed: for each
album, extract the `<CODE>-<VOL>-` prefix from any track's
`download_url` when one is exposed; if no track currently has a URL,
fall back to a derived prefix from album title (`Exciting Events Vol
11` → `EE-11`). Persist the map in `ysh_catalog.json` alongside album
metadata.

### Provider detection per file

Two heuristics, applied in order:

1. **Directory hint** — `/data/import/ysh/**.mp3` is YSH;
   `/data/import/aio/**.mp3` is AIO; bare `/data/import/*.mp3` is
   ambiguous and falls through.
2. **Filename prefix sniff** — if `re.match(r"^[A-Z]{1,3}-\d+-\d+\s", name)`
   matches AND the prefix is in the YSH code map, route to YSH. Else
   default to AIO (existing behavior preserved).

This means mixed-provider drops in the bare `import/` directory still
just work for most files; the `--provider` CLI flag overrides if a
user wants to force-route a batch.

### Catalog matching

The YSH catalog match function:

```python
# app/scrape_ysh.py (new)
@dataclass(frozen=True)
class YshCatalogMatch:
    sku_id: int
    canonical_title: str
    album_title: str
    album_slug: str
    track_order: int          # order_index from the catalog
    code_prefix: str | None   # "EE-11" if extractable, else None

def build_ysh_indexes(catalog_path: Path = YSH_CATALOG_PATH):
    """
    Returns three indexes for matching:
      - code_index:    "EE-11-02" → YshCatalogMatch  (uniquely identifying)
      - title_index:   normalized_title → list[YshCatalogMatch]  (ambiguous when >1)
      - sku_index:     sku_id → YshCatalogMatch
    """
```

Match order for an imported file:

1. **Code prefix** (`EE-11-02`) extracted from filename → direct hit
   in `code_index`. Highest confidence, used when available.
2. **Title-only normalized** → `title_index[normalized_title]`. If
   one hit, accept. If multiple albums share the title, surface to
   unmatched-needs-disambiguation (don't guess).
3. **ID3 TIT2** → same as #2.
4. **Miss** → move to `/data/import/_unmatched/`.

Reuse the existing `normalize_title` from `import_dropbox.py` — same
aggressive normalization will work since both catalogs share the
"punctuation-doesn't-matter" property.

### Storage path

```python
# app/storage.py — already provider-aware after the route changes above
def episode_path("ysh", external_id="1850", title="The Land of Uz",
                 album="Bible Comes Alive - Album 4")
  → /data/audio/ysh/bible-comes-alive-album-4/1850-the-land-of-uz.mp3
```

`external_id` is the catalog `sku_id` (stable), matching the Android
side.

### Catalog refresh script

```bash
# archive-service/scripts/refresh-ysh-catalog.sh
# Walks all 5 pages of https://www.yourstoryhour.org/crud/product/skus,
# aggregates, derives code prefixes per album, writes ysh_catalog.json.
# Should be run periodically by the LXC operator (manual or cron).
```

JSON shape (mirrors AIO catalog file enough for the importer to share
helpers):

```json
{
  "scrapedAtMs": 1778000000000,
  "albumCount": 88,
  "albums": [
    {
      "id": 119,
      "title": "Exciting Events - Volume 11",
      "slug": "exciting-events-volume-11",
      "image": "https://your-story-hour.s3.amazonaws.com/.../Exciting%20Events%20Volume%2011.jpg",
      "code_prefix": "EE-11",
      "lang_code": "en",
      "tracks": [
        {
          "sku_id": 1958,
          "title": "Madeleine's Courage",
          "order_index": 2,
          "code": "EE-11-02"
        }
      ]
    }
  ]
}
```

### Routes / API exposure

File import is a server-local operation; no new HTTP route needed.
Reuse the existing `scripts/run-import.sh` with a `--provider` flag
(defaults to `aio`). Internally `import_dropbox.py` either dispatches
to the existing AIO matcher or the new YSH matcher based on the flag
and per-file sniffing.

The architecture keeps two parallel importer modules (AIO and YSH)
with one shared `_dispatch_for_file()` entry point — don't try to
generalize into one mega-importer; the catalogs are different
shapes and the filename conventions are different enough that a
shared abstraction would obscure both.

### Tests

- **`YshImporterTest`** — fixture catalog + a synthetic drop of
  three files:
  - `EE-11-02 - Madeleine's Courage.mp3` → match via code prefix
  - `The Land of Uz.mp3` → match via title (single album hit)
  - `unknown-thing.mp3` → unmatched, moved to `_unmatched/`
  Assert file moves + DB rows.
- **`YshImporterAmbiguityTest`** — a title that appears in 2+ albums,
  no code prefix: file gets moved to `_unmatched/` with reason
  "ambiguous title", no row inserted.
- **`YshCatalogIndexTest`** — code prefix extraction from track URLs
  (when present) AND title-derived fallback for albums with no free
  tracks; build full `code_index` from fixture and verify expected
  prefixes.

## Tests

- **Fixtures** (commit under `android/app/src/test/resources/ysh/`):
  - `free-streaming.json` — copy of `docs/ysh-probe/yourstoryhour-free-streaming.json`
  - `catalog-page-1.json` … `catalog-page-5.json` — paginated copies for
    catalog-loading test
  - `oneplace-ysh-recent.json` — copy of `docs/ysh-probe/oneplace-ysh-archive.json`
- **`YshCatalogTest`** — loads fixture pages, asserts 88 albums × 1055
  digital_track SKUs. Title lookup for "The Land of Uz" returns Bible
  Comes Alive Album 4.
- **`YshFreeStreamProviderTest`** — fixture-driven, asserts mapping +
  album fields populated.
- **`YshOneplaceProviderTest`** — fixture-driven, asserts title-join
  against catalog stub; unmatched titles dropped with log.
- **`DailyCheckWorkerTest`** (extend existing) — three providers
  registered (AIO, YshFreeStream, YshOneplace); same story from both
  YSH sources collapses to one row.
- **`OdysseyDbMigrationTest`** — v3 → v4: seed v3 row with
  `episodeId=1278294`, run migration, expect a row with
  `providerId="aio"`, `externalId="1278294"`, composite PK enforced,
  `ysh_unmatched_titles` table present and empty.
- **`YshUnmatchedFlowTest`** — provider encounters a title that
  doesn't match catalog → row written; `observeCount()` flow emits 1
  → mode switcher composable shows badge; manual-match override
  persisted → next encounter ingests successfully and clears the row.
- **`PhoneDiskMigrationTest`** — pre-populate `filesDir/episodes/X.mp3`,
  run migration, assert file moved to `filesDir/episodes/aio/X.mp3`
  and DB row `filePath` updated.
- **archive-service**: pytest covering the filesystem migration
  startup behavior (`audio/<slug>/...` → `audio/aio/<slug>/...`)
  AND the deprecated-alias routes return identical responses to
  their `/providers/aio/...` counterparts.

## Build order

1. **Android DB migration — split into two sub-steps for safer review:**
   - **1a (additive only)**: v3 → v4 adds `albumName`, `albumImageUrl`,
     `albumTrackOrder` columns to `local_episodes`, creates
     `ysh_unmatched_titles` table. Pure ALTER TABLE / CREATE TABLE.
     No code-side ripple — existing `episodeId: Long` PK unchanged.
     Tests stay green with zero behavior change.
   - **1b (composite PK + type ripple)**: v4 → v5 rename + recreate
     `local_episodes` and `playback_positions` with composite PK on
     `(providerId, externalId)`. Touches the 27 .kt files referencing
     `episodeId: Long`. Player mediaId encode/decode helpers land.
     Bigger PR; gate on 1a being green.
2. **`OneplaceClient.listenUrl` as arg + per-provider lastSeen in
   SettingsRepo** (with legacy-key shim for AIO).
3. **`ShowProvider.lastSeenStateKey` + `DailyCheckWorker` rewrite.**
4. **Phone disk-path migration** (`filesDir/episodes/<id>.mp3` →
   `filesDir/episodes/aio/<id>.mp3`).
5. **`YshCatalog`** with disk persistence + weekly refresh worker +
   fixture test.
6. **`YshFreeStreamProvider`** + multibind + fixture test.
7. **`YshOneplaceProvider`** + multibind + fixture test (includes
   `YshUnmatchedDao` writes on title-join miss).
8. **`PlayerController` MediaMetadata.artist by provider.**
9. **`activeShow` setting + show switcher (with unmatched-badge) +
   nav-adapts-per-show.**
10. **YSH Albums screen + YSH Album Detail screen + Unmatched-list
    screen + first-time snackbar.**
11. **archive-service**: SQLite schema migration + filesystem
    migration (`audio/<slug>/` → `audio/aio/<slug>/`) +
    `/providers/{provider}/...` resource paths + deprecated aliases.
12. **YSH catalog on server** + `refresh-ysh-catalog.sh` script +
    `scrape_ysh.py` indexes (code/title/sku).
13. **YSH file importer** (`scripts/run-import.sh --provider ysh`,
    `import_dropbox.py` per-file dispatch). Provider detection by
    directory hint and filename code prefix.
14. **Android NasClient**: per-provider `audioUrl(provider, externalId)`
    + `listAllEpisodes(provider)`; `BrowseVm.refresh` runs both
    providers; YSH Album Detail "☁ on backup" badges + Pin Offline.
15. BACKLOG.md update — strike the H-section "still owed" entries.

All 15 steps are in scope for the YSH series. Archive (steps 11-14)
is mandatory per user direction — pin-from-backup AND server-side
file import must work for YSH at landing.

## Decisions (closed 2026-05-11)

1. **Sources**: keep both YSH providers (yourstoryhour free pool +
   oneplace recent broadcasts). Confirmed disjoint feeds (~6% overlap)
   so both earn their keep; dedup via `(providerId, externalId="ysh-sku-<sku_id>")`.
2. **Active-show default & enable flow**: YSH is **disabled** by
   default after upgrade. The active-show dropdown lists ONLY
   currently-enabled providers (so a fresh install / non-YSH user
   sees just "Adventures in Odyssey" — no clutter). Below the
   provider list, a permanent footer item **"Manage shows…"** opens
   Settings → Shows section. From there the user toggles YSH on
   (and any future shows). Once enabled, the new provider appears in
   the dropdown alongside AIO and becomes selectable as active.
   Settings is also the place to disable a provider later. No
   first-run setup screen yet — add one if a third show ever lands.
3. **Retention — per-provider**: each provider has its own retention
   count. `Settings.retentionByProvider: Map<String, Int>` keyed by
   provider id. Defaults: `aio = 7` (matches today), `ysh = -1` (no
   pruning). Settings UI gets a per-show section, each with its own
   slider; `-1` shown as "Keep all." RetentionWorker iterates
   providers, runs the existing prune logic against each filtered
   subset of `downloadedOldestFirst()`. Backward compat: on first
   run after upgrade, read legacy single `retention_count` setting
   and write it into the new map under `aio`; remove the legacy key.
4. **YSH download cadence**: reuse `DailyCheckWorker` schedule (24h).
5. **NAS archive parity** for YSH lands in this design (not phase 2).
   Pin-from-backup works for YSH same as AIO.
6. **Album cover storage on phone**: Coil's default disk cache.
7. **Catalog refresh worker**: separate `YshCatalogRefreshWorker`,
   weekly. Providers tolerate missing catalog gracefully — fresh
   install's first daily check no-ops on YSH, catalog populates,
   day 2 ingests normally.
8. **Title-join fallback**: strict normalized-match only in v1
   (lowercase, punctuation→space, collapse whitespace). Unmatched
   titles persist in a dedicated table and surface in-app via a
   badge + read-only review screen — see "Unmatched title surfacing"
   below. **No manual-match override in v1** — observability only.
9. **archive-service URL shape**: `/providers/{provider}/episodes/...`
   is canonical; old `/episodes/...` paths kept as aliases defaulting
   to AIO during the migration window.
10. **File-import support for YSH** lands with this work (mirror of
    the AIO drop-folder importer). See "YSH file imports" below.
11. **Auto-delete after backup** (per-provider toggle). When ON,
    files where `filePath != null AND archivedAt != null AND
    completedAt != null` are removed from phone storage; the row
    stays so the user can re-pin from NAS. See "Storage tidying"
    below. Defaults: AIO OFF (preserves upgrade behavior), YSH OFF
    (user wants to build the library).

## Unmatched title surfacing

When `YshOneplaceProvider` gets a title that doesn't match any catalog
track after normalization, it must be visible to the user without
forcing them to open Debug logs. Mechanism:

### Persistence

```kotlin
@Entity(tableName = "ysh_unmatched_titles")
data class YshUnmatchedTitleEntity(
    @PrimaryKey val oneplaceEpisodeId: Long,
    val title: String,
    val sourceUrl: String,
    val downloadUrl: String,
    val firstSeenAt: Long,         // epoch ms
    val attemptCount: Int,         // increment on each daily-check re-encounter
)

@Dao
interface YshUnmatchedDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: YshUnmatchedTitleEntity)

    @Query("UPDATE ysh_unmatched_titles SET attemptCount = attemptCount + 1 WHERE oneplaceEpisodeId = :id")
    suspend fun bumpAttempt(id: Long)

    @Query("SELECT COUNT(*) FROM ysh_unmatched_titles") 
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM ysh_unmatched_titles ORDER BY firstSeenAt DESC")
    fun observeAll(): Flow<List<YshUnmatchedTitleEntity>>

    @Query("DELETE FROM ysh_unmatched_titles WHERE oneplaceEpisodeId = :id")
    suspend fun delete(id: Long)
}
```

Adds to v3 → v4 migration as a new table.

### Where it shows up

- **Badge on the show-switcher dropdown** when the count > 0 — small
  warning dot next to "Your Story Hour" in the menu. Same dot on
  the Settings tab icon. (Standard Material badge composable.)
- **Settings → YSH section** has a "Unmatched titles (N)" row that
  navigates to a list screen showing each unmatched title, when it
  was first seen, attempt count, and per-row actions:
  - **Manual match** — opens a catalog-track picker (search by
    album/title) that records the join in a `ysh_title_overrides`
    DataStore map; future encounters resolve to that catalog row.
  - **Dismiss** — removes from the unmatched table; will re-appear
    on next daily check if oneplace re-broadcasts the same episode,
    so the dot returns. Dismiss-permanently is a follow-up.
- **One-time snackbar** when count transitions 0 → ≥1, with action
  "Review" navigating to the unmatched-list screen. Keyed off
  `ShownUnmatchedSnackbarFor: Set<Long>` in DataStore so a single
  unmatched title doesn't re-snackbar after dismissal.

### v1 scope: observability only

No manual-match override in v1. The badge + read-only list is enough
to make the user aware of misses; a future v0.2.x can add a
catalog-track picker if real-world drift produces a backlog. Keep
`ProviderEpisode` slim — no `sourceEpisodeId` field needed yet.

## Storage tidying (auto-delete after backup)

Optional per-provider feature, independent of retention count. Removes
local audio for episodes the user has already listened to AND that
are safely on the NAS — the row itself stays in the DB so the
episode keeps appearing in lists, just streams from NAS on next play.

### Signal

Eligible row = `filePath IS NOT NULL AND archivedAt IS NOT NULL AND
the matching PlaybackPositionEntity has completedAt IS NOT NULL`.

Completion is already tracked: `PlaybackPositionEntity.completedAt`
flips when playback hits ≥95% (existing logic, unchanged).

### Worker

New `TidyWorker` — runs:
- after each playback completion event (chained from
  `PositionPersistence` when it sets `completedAt`),
- and as a pre-pass inside `RetentionWorker` so missed events get
  caught on the daily cycle.

```kotlin
@Dao
interface EpisodeDao {
    @Query("""
      SELECT e.* FROM local_episodes e
      JOIN playback_positions p
        ON p.providerId = e.providerId AND p.externalId = e.externalId
      WHERE e.providerId   = :providerId
        AND e.filePath     IS NOT NULL
        AND e.archivedAt   IS NOT NULL
        AND p.completedAt  IS NOT NULL
    """)
    suspend fun tidyCandidates(providerId: String): List<LocalEpisodeEntity>
}

@HiltWorker
class TidyWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters,
    private val episodes: EpisodeDao,
    private val downloader: EpisodeDownloader,
    private val settings: SettingsRepo,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val s = settings.flow.first()
        for ((providerId, policy) in s.retentionByProvider) {
            if (!policy.autoCleanWhenListenedAndArchived) continue
            for (ep in episodes.tidyCandidates(providerId)) {
                ep.filePath?.let { downloader.delete(File(it)) }
                episodes.markUndownloaded(ep.providerId, ep.externalId)
            }
        }
        return Result.success()
    }
}
```

`markUndownloaded` already exists for `PlaybackRecovery`'s use; the
v3→v4 migration just generalizes its keying from `Long` to
`(providerId, externalId)`.

### Settings model

`retentionByProvider` value type extends beyond a bare `Int`:

```kotlin
data class RetentionPolicy(
    val keepCount: Int? = null,           // null = "keep all"
    val autoCleanWhenListenedAndArchived: Boolean = false,
)

// Settings exposes a Map<String, RetentionPolicy>
// Default: aio -> RetentionPolicy(keepCount = 7), 
//          ysh -> RetentionPolicy(keepCount = null)
```

Serialized into DataStore as two parallel keys per provider — keeps
it preference-friendly without bringing in a JSON serializer:
- `retention_keep_count__<providerId>` (int; -1 = keep-all sentinel)
- `retention_auto_clean__<providerId>` (boolean)

### Player fallback after tidy

A tidied row has `filePath = null, downloadUrl = <original oneplace
URL>, archivedAt = <timestamp>`. The existing `AlbumDetailVm.play()`
logic already branches on null filePath to fall back to remote — but
it currently expects `downloadUrl` starting with `backup://` to mean
"pull from NAS." For tidied rows, prefer NAS even though the
downloadUrl still points at oneplace (oneplace URLs may expire; the
NAS copy is the user's owned, stable artifact).

`PlayerController.playLocal/playStream` needs a third branch:

```
if (filePath != null)              → playLocal(filePath)
elif (archivedAt != null && nasConfigured) → playStream(NasClient.audioUrl)
elif (downloadUrl startsWith "backup://")  → playStream(NasClient.audioUrl)
else                                       → playStream(downloadUrl)
```

This unifies the "Pin offline" mirror rows and the "tidied" rows into
one resolution rule: archived rows always prefer the NAS source.

### UI surface

In Album Detail / Recent row UI:
- A tidied row (filePath=null, archivedAt!=null) gets a "Pin offline"
  affordance same as today's backup://-mirror rows.
- The row's right-side metadata could subtly indicate "on NAS"
  (small cloud icon) so the user sees that tapping play will stream.

### Defaults & migration

Both providers default to `autoCleanWhenListenedAndArchived = false`
on existing installs. Surfacing in Settings is enough — opt-in
explicit avoids surprising existing users.

The legacy single `retention_count` DataStore key migrates to
`retention_keep_count__aio` on first read post-upgrade. Legacy key
removed in the same edit.

### Tests

- `TidyWorkerTest` — provider toggle off → no-op; toggle on +
  three candidate rows → files deleted, rows still present with
  filePath/fileSize/downloadedAt nulled.
- `PlayerControllerArchivedFallbackTest` — tidied row with NAS
  configured → resolves to NAS audio URL not oneplace.

## Phone disk layout

Today AIO downloads write to `filesDir/episodes/<id>.mp3` (verified
in `EpisodeDownloader.kt`). YSH would collide with AIO on the rare
sku_id-vs-broadcast-number overlap. Standardize:

```
filesDir/episodes/aio/1278294.mp3     # was filesDir/episodes/1278294.mp3
filesDir/episodes/ysh/ysh-sku-1850.mp3
```

Migration runs once on first launch of the new version: enumerate
`filesDir/episodes/*.mp3` → move into `aio/` subdirectory. Update
`local_episodes.filePath` rows in the same transaction.

## Player `mediaId` audit (closed)

mediaId is constructed and parsed exclusively in `PlayerController.kt`
(8 sites). Adopt the encode/decode helpers from BACKLOG and route all
sites through them. No other file builds a MediaItem from a row.

Wider type ripple: 27 .kt files reference `episodeId: Long` (workers,
trackers, NasClient, all UI screens). The v3 → v4 composite-PK
migration changes these to `(providerId: String, externalId: String)`.
Treat step 1 of the build order as one big PR covering migration +
type changes; tests stay green throughout.

Concrete edit list:
- `data/local/OdysseyDb.kt` — entity + DAO + migration body
- `player/PlayerController.kt` — encode/decode helpers
- `player/EpisodePlayer.kt`, `PositionPersistence.kt`,
  `PlaybackRecovery.kt`
- `nas/NasClient.kt` — `audioUrl(providerId, externalId)`,
  `listAllEpisodes(providerId)`
- `download/EpisodeDownloader.kt`, all four progress trackers
- `work/*.kt` (all 7 workers/helpers)
- `ui/screens/*.kt` (Recent, RecentListing, Downloaded, BrowseNas,
  AlbumDetail, Transfers)
- `scrape/OneplaceClient.kt`, `show/AioOneplaceProvider.kt`,
  `debug/DebugLogger.kt`
