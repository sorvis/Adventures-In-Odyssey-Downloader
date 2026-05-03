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
 * Bootstrap: GET /ministries/adventures-in-odyssey/listen/  → contains the
 *   latest episodeId as a bare assignment in inline JS.
 * Older episodes: GET /api/related-episodes?eid=<id>&ps=<n>&watch=false →
 *   JSON array, ordered newest-first, paginated by feeding back the last id.
 *
 * Public endpoint, no auth. CDN MP3 URLs at zcast.swncdn.com support Range.
 */
@Singleton
class OneplaceClient @Inject constructor(
    private val http: OkHttpClient,
) {
    /** URLs are overridable for tests; production paths default to the live site. */
    var listenUrl: String = "https://www.oneplace.com/ministries/adventures-in-odyssey/listen/"
    var apiUrl: String    = "https://www.oneplace.com/api/related-episodes"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // bare assignment: episodeId=1278294
    private val bootstrapRe = Regex("""episodeId[=:"\s]+(\d{6,})""")

    /** Returns the most recent episode ID currently displayed. */
    suspend fun latestEpisodeId(): Long? = runCatching {
        val body = get(listenUrl)
        bootstrapRe.find(body)?.groupValues?.get(1)?.toLong()
    }.getOrNull()

    /** Episodes immediately preceding `cursor`, newest-first; up to `pageSize`. */
    suspend fun episodesBefore(cursor: Long, pageSize: Int = 20): List<OneplaceEpisode> {
        val url = "$apiUrl?eid=$cursor&ps=$pageSize&watch=false"
        return json.decodeFromString(get(url))
    }

    /**
     * Convenience: walk the API backward from the current latest until either
     * `lastSeen` is reached or `maxFetch` total episodes are accumulated.
     * Returns episodes ordered newest-first, EXCLUDING `lastSeen` itself.
     *
     * Pass lastSeen = 0 on a fresh install — caller decides how far back to go
     * via maxFetch.
     */
    suspend fun newSince(lastSeen: Long, maxFetch: Int = 100): List<OneplaceEpisode> {
        val latest = latestEpisodeId() ?: return emptyList()
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
