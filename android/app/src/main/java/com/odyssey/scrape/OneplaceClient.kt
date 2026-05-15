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
     * via maxFetch.
     */
    suspend fun newSince(listenUrl: String, lastSeen: Long, maxFetch: Int = 100): List<OneplaceEpisode> {
        val latest = latestEpisodeId(listenUrl) ?: return emptyList()
        if (latest == lastSeen) return emptyList()

        val out = mutableListOf<OneplaceEpisode>()
        // The bootstrap ID is itself an episode; fetch its details by querying
        // its predecessors then reconstructing — simpler is to start the
        // pagination at latest+1 so latest is included.
        var cursor = latest + 1
        while (out.size < maxFetch) {
            val page = episodesBefore(cursor, pageSize = 20)
            if (page.isEmpty()) break
            for (ep in page) {
                if (ep.episodeId == lastSeen) return out
                out += ep
                if (out.size >= maxFetch) return out
            }
            cursor = page.last().episodeId
        }
        return out
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
    // Episode artwork. AIO currently returns the same generic show logo
    // for every episode (and `imageUrlWebP` is null), but threading it
    // through gives lockscreen/notification/list-row art today and lets
    // future per-episode art light up automatically.
    val imageUrl: String? = null,
)
