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

    // bare assignment: episodeId=1278294
    private val bootstrapRe = Regex("""episodeId[=:"\s]+(\d{6,})""")

    /** Returns the most recent episode ID currently displayed for the show at `listenUrl`. */
    suspend fun latestEpisodeId(listenUrl: String): Long? = runCatching {
        val body = get(listenUrl)
        bootstrapRe.find(body)?.groupValues?.get(1)?.toLong()
    }.onFailure { t ->
        // Silent null on this path used to hide a class of "DailyCheckWorker
        // returns 0 episodes for no apparent reason" mysteries: HTML schema
        // change, network blip, regex miss. We can't use DebugLogger here
        // because this file is in the pure-JVM compile set (scripts/
        // run-jvm-tests.sh) and DebugLogger pulls in android.util.Log.
        // stderr is routed to logcat on Android (tag "System.err") and
        // shows up in `adb logcat`; not as nice as the in-app debug screen
        // but a strict improvement over the silent null.
        System.err.println("[OneplaceClient] latestEpisodeId($listenUrl) failed: ${t.message}")
    }.getOrNull()

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
        val latest = latestEpisodeId(listenUrl) ?: return emptyList()
        if (latest == lastSeen) return emptyList()

        // Phase 1: Probe forward from latest+1 to find a cursor that
        // yields target-show episodes. Without showId filter, ANY non-
        // empty response wins (old behavior). With showId set, the
        // probe also has to see at least one matching item.
        var cursor = latest + 1
        var probesRemaining = GAP_PROBE_CAP
        var firstHit: List<OneplaceEpisode>? = null
        while (probesRemaining-- > 0) {
            val page = episodesBefore(cursor, pageSize = 20)
            val targetHits = if (showId == null) page else page.filter { it.showId == showId }
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
            val targetItems = if (showId == null) page else page.filter { it.showId == showId }
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
