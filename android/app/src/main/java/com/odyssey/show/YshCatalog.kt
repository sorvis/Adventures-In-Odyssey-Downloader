package com.odyssey.show

import android.content.Context
import com.odyssey.debug.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Title-keyed index of the yourstoryhour.org paid album catalog. Used
 * by YshOneplaceProvider to attach proper album metadata (cover art,
 * album name, track ordering) to each broadcast episode it ingests —
 * the oneplace YSH feed doesn't expose any of that on its own.
 *
 * Lifecycle:
 *   1. App starts → YshCatalog.load() reads any cached index from
 *      `filesDir/ysh/catalog.json`. Synchronous + fast (one small
 *      JSON parse).
 *   2. Once a week (or on demand from Settings) the
 *      YshCatalogRefreshWorker calls refresh() which walks
 *      yourstoryhour.org/crud/product/skus through pagination,
 *      rebuilds the index, and overwrites the cached file.
 *
 * Fresh installs land with `state.value == null` — providers tolerate
 * a missing catalog (YshOneplaceProvider drops to the
 * unmatched-titles log when lookup() returns null).
 *
 * Decisions (see docs/ysh-design.md):
 *   - lang_code filter is hard-coded to "en" — Spanish/Russian
 *     albums exist on yourstoryhour but the user is English-only.
 *   - When a normalized title appears in multiple albums (~65 cases
 *     out of 1055 tracks), lookup() returns null rather than guessing
 *     so the unmatched flow surfaces the ambiguity.
 */
@Singleton
class YshCatalog @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val http: OkHttpClient,
) {
    /**
     * Overridable for tests. Production calls hit the live API; tests
     * point this at a MockWebServer.
     */
    var skusUrl: String = SKUS_URL

    private val _state = MutableStateFlow<YshCatalogIndex?>(null)
    val state: StateFlow<YshCatalogIndex?> = _state

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val cacheFile: File by lazy {
        File(ctx.filesDir, "ysh").apply { mkdirs() }.resolve("catalog.json")
    }

    /**
     * Load the on-disk cached index into `state`. Cheap; safe to call
     * on every app start. No-op if no cache file exists yet — caller
     * (or the refresh worker) will populate it later.
     */
    suspend fun load() = mutex.withLock {
        if (!cacheFile.exists()) return@withLock
        runCatching {
            val parsed = json.decodeFromString(YshCatalogIndex.serializer(), cacheFile.readText())
            _state.value = parsed
        }.onFailure {
            DebugLogger.w("YshCatalog", "load() failed; will refresh from network later", it)
        }
    }

    /**
     * Walk the paginated API, build the index, persist it, and emit on
     * `state`. Pagination stops at the first empty page.
     */
    suspend fun refresh(): Result<Int> = mutex.withLock {
        runCatching {
            val seen = mutableMapOf<Long, YshApiAlbum>()
            var page = 1
            while (true) {
                val pageJson = httpGet("$skusUrl?page=$page")
                val parsed = json.decodeFromString(YshApiPage.serializer(), pageJson)
                if (parsed.items.isEmpty()) break
                for (a in parsed.items) seen[a.id] = a
                page++
                if (page > MAX_PAGES) break        // safety stop
            }
            val tracks = buildTracks(seen.values)
            val index = YshCatalogIndex(
                scrapedAtMs = System.currentTimeMillis(),
                tracks = tracks,
            )
            cacheFile.writeText(json.encodeToString(YshCatalogIndex.serializer(), index))
            _state.value = index
            DebugLogger.i(
                "YshCatalog",
                "refresh: ingested ${tracks.size} tracks across ${seen.size} albums",
            )
            tracks.size
        }.onFailure {
            DebugLogger.w("YshCatalog", "refresh failed", it)
        }
    }

    /**
     * Convenience lookup against the currently-loaded index. Returns
     * null on miss, on ambiguous match (title appears in more than one
     * album — the caller's unmatched-log handles this case), or when
     * the catalog isn't loaded yet.
     */
    fun lookup(title: String): YshCatalogTrack? {
        val idx = _state.value ?: return null
        val key = normalize(title)
        return idx.byNormalizedTitle()[key]?.singleOrNull()
    }

    private fun httpGet(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) odyssey-app/0.1")
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
            return resp.body?.string().orEmpty()
        }
    }

    companion object {
        const val SKUS_URL = "https://www.yourstoryhour.org/crud/product/skus"
        private const val MAX_PAGES = 20
    }
}

// =====================================================================
// Wire format
// =====================================================================

@Serializable
internal data class YshApiPage(
    val items: List<YshApiAlbum> = emptyList(),
)

@Serializable
internal data class YshApiAlbum(
    val id: Long,
    val title: String = "",
    val slug: String = "",
    @SerialName("primary_image") val primaryImage: String? = null,
    @SerialName("lang_code") val langCode: String? = null,
    val skus: List<YshApiSku> = emptyList(),
)

@Serializable
internal data class YshApiSku(
    val id: Long,
    val title: String? = null,
    val type: String? = null,
    @SerialName("order_index") val orderIndex: Int? = null,
)

// =====================================================================
// Domain types
// =====================================================================

@Serializable
data class YshCatalogTrack(
    val skuId: Long,
    val title: String,
    val albumId: Long,
    val albumTitle: String,
    val albumSlug: String,
    val albumImageUrl: String?,
    val orderIndex: Int,
)

@Serializable
data class YshCatalogIndex(
    val scrapedAtMs: Long,
    val tracks: List<YshCatalogTrack>,
) {
    /**
     * Title-normalized lookup. Lazy because we want serialize() to
     * write tracks without the derived map duplicating the data.
     */
    @kotlinx.serialization.Transient
    private val cachedIndex: Map<String, List<YshCatalogTrack>> by lazy {
        tracks.groupBy { normalize(it.title) }
    }
    fun byNormalizedTitle(): Map<String, List<YshCatalogTrack>> = cachedIndex
}

// =====================================================================
// Pure helpers (public for tests; YshOneplaceProvider also uses these)
// =====================================================================

/**
 * Build the flat track index from a collection of API-shaped albums.
 * Drops non-English albums and any sku that isn't a digital_track.
 * Visible for tests.
 */
internal fun buildTracks(albums: Collection<YshApiAlbum>): List<YshCatalogTrack> =
    albums.filter { (it.langCode ?: "en") == "en" }
        .flatMap { a ->
            a.skus
                .filter { it.type == "digital_track" && !it.title.isNullOrBlank() }
                .map { s ->
                    YshCatalogTrack(
                        skuId = s.id,
                        title = s.title!!,
                        albumId = a.id,
                        albumTitle = a.title,
                        albumSlug = a.slug,
                        albumImageUrl = a.primaryImage,
                        orderIndex = s.orderIndex ?: 0,
                    )
                }
        }

/**
 * Normalize a title for catalog matching. Strict normalization only:
 * lowercase, all non-alphanumerics → space, collapse whitespace.
 * Mirrors the design's title-join rule; reused both by `lookup` and
 * by tests that build fixture indexes.
 */
internal fun normalize(title: String): String {
    val sb = StringBuilder(title.length)
    var lastWasSpace = false
    for (raw in title) {
        val ch = raw.lowercaseChar()
        if (ch.isLetterOrDigit()) {
            sb.append(ch)
            lastWasSpace = false
        } else {
            if (!lastWasSpace && sb.isNotEmpty()) sb.append(' ')
            lastWasSpace = true
        }
    }
    return sb.toString().trim()
}
