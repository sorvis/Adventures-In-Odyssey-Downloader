package com.odyssey.scrape

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * oneplace.com scraper. Verified live 2026-05-03; the original C# date-string
 * approach is dead — site is now Alpine.js + JSON API.
 *
 * Bootstrap: GET /ministries/<show-slug>/listen/  → contains the latest
 *   episodeId as a bare assignment in inline JS. The show-slug differs
 *   per show (`adventures-in-odyssey`, `your-story-hour`, …) so the
 *   listen URL is a method argument rather than a field — one client
 *   instance can serve every oneplace-syndicated show.
 * Older episodes: GET /api/related-episodes?eid=<id>&ps=<n>&watch=false →
 *   JSON array, ordered newest-first, paginated by feeding back the last id.
 *   Episode IDs are globally unique across shows (one CMS sequence), so the
 *   API endpoint is the same regardless of which show seeded the cursor.
 *
 * Public endpoint, no auth. CDN MP3 URLs at zcast.swncdn.com support Range.
 */
@Singleton
class OneplaceClient @Inject constructor(
    private val http: OkHttpClient,
) {
    /**
     * Overridable for tests; production value is the live API. Stays a `var`
     * (rather than a method arg) because the API URL is the same for every
     * oneplace-syndicated show — show identity is only carried in the
     * listenUrl seed and the eid cursor that flows out of it.
     */
    var apiUrl: String = "https://www.oneplace.com/api/related-episodes"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Listen-page bootstrap regexes, tried in order. Each one extracts
     * a `(\d{6,})` group. The FIRST match in the page wins per pattern;
     * the FIRST pattern that finds anything wins overall.
     *
     * The original primary `episodeId[=:"\s]+(\d{6,})` still leads
     * because oneplace's current HTML emits both `salemMeta.episodeId=N`
     * and `window.initData.episodeId=N` in inline JS — both match. The
     * fallback patterns guard against the next shape oneplace ships
     * (data-attr, JSON blob, named JS object) without needing a code
     * release every time they rearrange the page. If ALL miss,
     * `latestEpisodeId` returns null and AioOneplaceProvider's empty-
     * result diagnostic surfaces the miss in the in-app debug log.
     */
    private val episodeIdPatterns: List<Regex> = listOf(
        Regex("""episodeId[=:"\s]+(\d{6,})"""),         // bare JS assignment: salemMeta.episodeId=1278298
        Regex("""data-eid\s*=\s*["'](\d{6,})["']"""),    // HTML data-eid="1278298"
        Regex(""""episodeId"\s*:\s*(\d{6,})"""),         // JSON: "episodeId": 1278298
        Regex(""""eid"\s*:\s*(\d{6,})"""),               // JSON: "eid": 1278298
    )

    /**
     * Listen-page showId regexes, tried in order. The bootstrap page
     * carries this in at least two spots today (verified 2026-05-31):
     *   `window.salemMeta.showId=`777``
     *   `window.salemMeta.zetaUniqueId=`777``
     * Auto-discovering it means providers no longer hard-depend on the
     * `AIO_SHOW_ID = 777L` constant — if oneplace renumbers the show,
     * the new id flows through bootstrap automatically.
     */
    private val showIdPatterns: List<Regex> = listOf(
        Regex("""salemMeta\.showId\s*=\s*[`'"]?(\d+)"""),
        Regex("""zetaUniqueId\s*=\s*[`'"]?(\d+)"""),
        Regex("""initData\.showId\s*=\s*[`'"]?(\d+)"""),
        Regex(""""showId"\s*:\s*(\d+)"""),
    )

    /**
     * Single-fetch bootstrap: returns BOTH the latest episodeId and the
     * showId (if discoverable) from one HTML GET. Callers that need
     * both should prefer this over calling [latestEpisodeId] twice.
     *
     * Returns null only when the bootstrap regex array entirely missed
     * — the network call succeeded but oneplace's HTML doesn't expose
     * any pattern we recognize. That's the signal to ship a new regex.
     */
    suspend fun bootstrap(listenUrl: String): OneplaceBootstrap? = runCatching {
        val body = get(listenUrl)
        val eid = firstMatch(body, episodeIdPatterns) ?: return@runCatching null
        val showId = firstMatch(body, showIdPatterns)
        OneplaceBootstrap(latestEpisodeId = eid, showId = showId)
    }.onFailure { t ->
        // stderr is routed to logcat on Android (tag "System.err") and
        // surfaces in adb. Can't use DebugLogger from this file — it's
        // in the pure-JVM compile set; DebugLogger imports android.util.Log.
        System.err.println("[OneplaceClient] bootstrap($listenUrl) failed: ${t.message}")
    }.getOrNull()

    /**
     * Returns the most recent episode ID currently displayed for the
     * show at `listenUrl`. Thin convenience over [bootstrap] so existing
     * call sites that only need the eid don't have to unpack the pair.
     */
    suspend fun latestEpisodeId(listenUrl: String): Long? =
        bootstrap(listenUrl)?.latestEpisodeId

    /** Apply each regex in order; return the first numeric capture that parses. */
    private fun firstMatch(body: String, patterns: List<Regex>): Long? {
        for (re in patterns) {
            re.find(body)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
        }
        return null
    }

    /** Episodes immediately preceding `cursor`, newest-first; up to `pageSize`. */
    suspend fun episodesBefore(cursor: Long, pageSize: Int = 20): List<OneplaceEpisode> {
        val url = "$apiUrl?eid=$cursor&ps=$pageSize&watch=false"
        return json.decodeFromString(get(url))
    }

    /**
     * Convenience: walk the API backward from the current latest (seeded
     * from `listenUrl`'s bootstrap page) until either `lastSeen` is reached
     * or `maxFetch` total episodes are accumulated. Returns episodes
     * ordered newest-first, EXCLUDING `lastSeen` itself.
     *
     * Pass lastSeen = 0 on a fresh install — caller decides how far back to go
     * via maxFetch. Pass `showId` to filter to a single oneplace show; the
     * probe will advance THROUGH pages of other shows until it lands on
     * one containing the target show's recent episodes.
     *
     * Cursor strategy: the related-episodes API EXCLUDES its seed eid from
     * the response, so we start at latest + 1 to capture latest itself.
     * Two distinct failure modes the probe must traverse:
     *
     *   (a) Gap eid — seed isn't a real episode → response is [].
     *       Skip forward by 1.
     *   (b) Wrong-show eid — seed is a real episode on a DIFFERENT show
     *       (oneplace's CMS id sequence is global; AIO leaves wide gaps
     *       inhabited by Sekulow/FOTF/etc.). Response is non-empty but
     *       contains 0 target-show items. Skip forward by 1.
     *
     * Pre-v0.1.69 the code only handled (a) — a non-empty response was
     * trusted to contain target-show content and the loop walked back
     * via `page.last().episodeId` into the OTHER show's history,
     * returning 0 matches after filtering. Today (2026-05-23) the gap
     * between AIO's latest and the next AIO-context seed has grown to
     * +20; the user lost two days of episodes (May 21 + May 22) before
     * this surfaced.
     */
    suspend fun newSince(
        listenUrl: String,
        lastSeen: Long,
        maxFetch: Int = 100,
        showId: Long? = null,
    ): List<OneplaceEpisode> {
        val boot = bootstrap(listenUrl) ?: return emptyList()
        val latest = boot.latestEpisodeId
        if (latest == lastSeen) return emptyList()
        // Prefer the showId discovered on the listen page itself (via
        // `salemMeta.showId=`777`` and friends) over the caller's hint
        // — if oneplace ever renumbers the show, the new id flows
        // through bootstrap and the caller's stale constant is bypassed.
        // Fall back to the caller's hint when bootstrap couldn't extract
        // one (e.g. on a future HTML shape we don't yet match).
        val effectiveShowId = boot.showId ?: showId

        // Phase 1: Probe forward from `latest` to find a cursor that
        // yields target-show episodes. Without showId filter, ANY non-
        // empty response wins (old behavior). With showId set, the
        // probe also has to see at least one matching item.
        //
        // Cursor starts at `latest` (not `latest + 1`) — verified live
        // 2026-05-31 via scripts/probe-oneplace.sh:
        //
        //   - Bootstrap regex on /ministries/<slug>/ extracts an
        //     ANCHOR eid (e.g. 1278298 for AIO), not the show's actual
        //     newest episodeId (which was 1278423 on the same day).
        //   - `/api/related-episodes?eid=<anchor>` returns the show's
        //     N most recent episodes regardless of whether the anchor
        //     is itself one of them. So querying the seed directly
        //     hits the AIO content immediately.
        //   - The old `latest + 1` skipped past the anchor into a gap
        //     (eid 1278299 → []) and then into other shows
        //     (1278300+ → showId=1055). Forward-probing 50 from there
        //     never reached AIO again and newSince returned [].
        //
        // The +1 was correct when the API was a global newest-first
        // feed that excluded its seed eid. Today it's per-show-context
        // and the anchor is the right entry point. If a future oneplace
        // change reverts to "seed is the true newest and is excluded
        // from the response," we lose at most ONE episode (the seed
        // itself) on the first refresh of a fresh install — acceptable
        // degradation vs the all-or-nothing miss of the +1 strategy.
        var cursor = latest
        var probesRemaining = GAP_PROBE_CAP
        var firstHit: List<OneplaceEpisode>? = null
        while (probesRemaining-- > 0) {
            val page = episodesBefore(cursor, pageSize = 20)
            val targetHits = if (effectiveShowId == null) page else page.filter { it.showId == effectiveShowId }
            if (targetHits.isNotEmpty()) {
                firstHit = page
                break
            }
            cursor++
        }
        if (firstHit == null) return emptyList()

        // Phase 2: Walk back via page.last().episodeId, accumulating
        // target-show items along the way. Subsequent pages may be
        // mixed-show; keep walking until we hit lastSeen, an empty
        // page (end of archive), or maxFetch.
        val out = mutableListOf<OneplaceEpisode>()
        var page: List<OneplaceEpisode> = firstHit
        while (true) {
            val targetItems = if (effectiveShowId == null) page else page.filter { it.showId == effectiveShowId }
            for (ep in targetItems) {
                if (ep.episodeId == lastSeen) return out
                // Dedup defensively — pages can re-yield the same id
                // across the probe-forward + walk-back boundary.
                if (out.any { it.episodeId == ep.episodeId }) continue
                out += ep
                if (out.size >= maxFetch) return out
            }
            if (page.isEmpty()) break
            val nextCursor = page.last().episodeId
            page = episodesBefore(nextCursor, pageSize = 20)
            if (page.isEmpty()) break
        }
        return out
    }

    private companion object {
        /**
         * Max number of forward probes from `latest` before giving up.
         * Real-world observed (2026-05-23): gap between AIO's latest
         * and the next AIO-context seed grew to +20 once oneplace
         * interleaved Sekulow / FOTF / other-show episodes between AIO
         * publications. 50 gives ~2.5x headroom without burning an
         * API quota when the show is genuinely silent.
         */
        const val GAP_PROBE_CAP = 50
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) odyssey-app/0.1")
            .header("Accept", "application/json, text/html, */*")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
            return resp.body?.string().orEmpty()
        }
    }
}

/**
 * What [OneplaceClient.bootstrap] extracts from a listen-page HTML in
 * one HTTP GET. [showId] is nullable because the bootstrap regex array
 * may fail to find a show-identity assignment on a future HTML shape;
 * the caller falls back to its own hint constant in that case.
 */
data class OneplaceBootstrap(
    val latestEpisodeId: Long,
    val showId: Long?,
)

@Serializable
data class OneplaceEpisode(
    val episodeId: Long,
    val title: String,
    @SerialName("subTitle") val airDate: String? = null,
    @SerialName("descriptionHtmlWithoutImages") val descriptionHtml: String? = null,
    val description: String? = null,
    val downloadFileUrl: String,
    val encodedFileUrl: String? = null,
    val url: String,                                 // episode page URL
    val series: String? = null,                      // album/series — usually null
    val durationSeconds: Long = 0L,
    /**
     * oneplace's numeric show identity. Reliable across the API
     * (AIO=777, Jay Sekulow Live=663, Your Story Hour=583, etc.).
     * Critical for filtering: oneplace's `/api/related-episodes`
     * endpoint walks BACKWARD by episodeId across ALL shows on
     * the network — so AIO pagination eventually drifts into
     * Sekulow / FOTF / etc. as `cursor = page.last().episodeId`
     * crosses show boundaries. Providers MUST filter results by
     * showId to stop the leak.
     */
    val showId: Long? = null,
    // Episode artwork. AIO currently returns the same generic show logo
    // for every episode (and `imageUrlWebP` is null), but threading it
    // through gives lockscreen/notification/list-row art today and lets
    // future per-episode art light up automatically.
    val imageUrl: String? = null,
)
