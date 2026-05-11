package com.odyssey.show

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Your Story Hour — the "currently free" rotating sample pool surfaced
 * at yourstoryhour.org/crud/free-streaming. The pool is small (~7
 * tracks across ~6 albums today, one free sample per album) and YSH
 * rotates which track is free on a roughly-quarterly cadence. Library
 * grows over time as more tracks roll through the pool.
 *
 * Snapshot source — ignores `lastSeenExternalId`. Dedup happens in
 * DailyCheckWorker via the composite-PK existing-keys check. Tracks
 * the worker has already ingested (by `ysh-sku-<sku_id>` externalId)
 * are skipped silently on the next run; new entries appear as the
 * pool rotates.
 *
 * The free-streaming response carries enough album metadata inline
 * (album name, slug, cover image) so this provider works without the
 * full YshCatalog being loaded. Useful for fresh installs: the user
 * gets YSH content from day 1 even before the weekly catalog refresh
 * has populated the deep index.
 */
@Singleton
class YshFreeStreamProvider @Inject constructor(
    private val http: OkHttpClient,
) : ShowProvider {
    override val id = "ysh"
    override val displayName = "Your Story Hour"
    override val artistName = "Your Story Hour"

    /** Overridable for tests; production targets yourstoryhour.org. */
    var freeStreamUrl: String = FREE_STREAM_URL

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun newSince(lastSeenExternalId: String?, maxFetch: Int): List<ProviderEpisode> {
        val body = httpGet(freeStreamUrl)
        val albums = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(YshFreeAlbum.serializer()),
            body,
        )
        val out = mutableListOf<ProviderEpisode>()
        for (a in albums) {
            for (t in a.tracks) {
                if (!t.isFreeStreaming || t.downloadUrl.isNullOrBlank()) continue
                out += ProviderEpisode(
                    externalId = "$EXTERNAL_ID_PREFIX${t.skuId}",
                    title = t.sku.orEmpty(),
                    airDate = a.createdAt?.take(10),
                    description = t.skuDescription,
                    downloadUrl = t.downloadUrl,
                    sourceUrl = "$SITE_ORIGIN/free-streaming/album/${a.slug}",
                    durationSeconds = t.lengthSeconds ?: DEFAULT_DURATION_S,
                    imageUrl = absolutize(a.primaryImage),
                )
                if (out.size >= maxFetch) return out
            }
        }
        return out
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
        const val EXTERNAL_ID_PREFIX = "ysh-sku-"
        const val FREE_STREAM_URL = "https://www.yourstoryhour.org/crud/free-streaming"
        private const val SITE_ORIGIN = "https://www.yourstoryhour.org"
        // 30-minute story is the YSH norm; only used when length_seconds
        // is null in the response (rare in practice).
        private const val DEFAULT_DURATION_S = 30L * 60L
    }
}

/**
 * Resolve a free-streaming `primary_image` to an absolute URL.
 * yourstoryhour.org sometimes serves relative paths
 * (`/images/albums/foo.jpg`); the host serves the asset at the same
 * origin. Absolute URLs flow through unchanged. Visible for tests.
 */
internal fun absolutize(path: String?): String? {
    if (path.isNullOrBlank()) return null
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val prefix = if (path.startsWith("/")) "" else "/"
    return "https://www.yourstoryhour.org$prefix$path"
}

// =====================================================================
// Wire format — /crud/free-streaming
// =====================================================================

@Serializable
internal data class YshFreeAlbum(
    @SerialName("product_id") val productId: Long,
    val product: String,
    @SerialName("product_description") val productDescription: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val slug: String,
    @SerialName("primary_image") val primaryImage: String? = null,
    val tracks: List<YshFreeTrack> = emptyList(),
)

@Serializable
internal data class YshFreeTrack(
    @SerialName("sku_id") val skuId: Long,
    val sku: String? = null,
    @SerialName("sku_description") val skuDescription: String? = null,
    @SerialName("length_seconds") val lengthSeconds: Long? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("is_free_streaming") val isFreeStreaming: Boolean = false,
)
