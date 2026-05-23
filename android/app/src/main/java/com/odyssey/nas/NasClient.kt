package com.odyssey.nas

import com.odyssey.app.Settings
import com.odyssey.app.SettingsRepo
import com.odyssey.debug.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NAS-side archive client. Every public method returns Result so callers can
 * handle "NAS not configured" / "NAS unreachable" without crashing the app.
 *
 * The app is designed to function fully without a NAS — daily check, download,
 * play, retention all work standalone. NAS failures degrade gracefully.
 */
@Singleton
class NasClient @Inject constructor(
    private val settings: SettingsRepo,
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun isConfigured(): Boolean = settings.flow.first().nasConfigured

    suspend fun upload(
        episodeId: Long,
        title: String,
        airDate: String?,
        description: String?,
        durationSecs: Long,
        sourceUrl: String,
        audio: File,
        /**
         * Album name resolved phone-side via AioCatalogRepo. When present
         * the server uses it directly; when null the server falls back to
         * its own (fragile) scrape against adventuresinodyssey.com. The
         * phone is the better authority because it ships a fresh
         * catalog asset and uses the same matcher that powers the
         * Albums tab — keeps the server free of provider-specific
         * scraping logic.
         */
        album: String? = null,
        /**
         * Called as bytes are streamed to the server. (bytesWritten,
         * totalBytes). totalBytes is the audio file length — multipart
         * envelope overhead is tiny by comparison so we just report
         * audio bytes for the user-facing progress UI.
         */
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Result<Unit> = call { s ->
        val audioBody = audio.asRequestBody("audio/mpeg".toMediaType())
        val countingAudio = if (onProgress != null) {
            CountingRequestBody(audioBody, audio.length(), onProgress)
        } else audioBody

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("episode_id", episodeId.toString())
            .addFormDataPart("title", title)
            .apply {
                airDate?.let     { addFormDataPart("air_date", it) }
                description?.let { addFormDataPart("description", it) }
                album?.takeIf { it.isNotBlank() }
                    ?.let { addFormDataPart("album", it) }
                addFormDataPart("duration_secs", durationSecs.toString())
                addFormDataPart("source_url", sourceUrl)
            }
            .addFormDataPart("audio", audio.name, countingAudio)
            .build()

        val url = "${s.nasUrl}/episodes"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${s.nasToken}")
            .applyCfAccess(s)
            .post(body)
            .build()
        DebugLogger.i("NasClient", "upload($episodeId) → POST $url (${audio.length()} bytes)")
        http.newCall(req).execute().use { resp ->
            if (resp.code !in 200..201) {
                val bodyPreview = runCatching { resp.body?.string()?.take(200) }.getOrNull().orEmpty()
                DebugLogger.w("NasClient", "upload($episodeId) — HTTP ${resp.code}: $bodyPreview")
                error("upload HTTP ${resp.code}: $bodyPreview")
            }
            DebugLogger.d("NasClient", "upload($episodeId) — HTTP ${resp.code} OK")
        }
    }

    /**
     * Fetch every episode the server knows about, paging until the
     * result page is short. Used by BrowseVm.refresh() to mirror the
     * full library into the local DB so the Album tab can show
     * "☁ on backup" badges without the user having to type a search
     * first. Pure metadata — no audio bytes.
     */
    suspend fun listAllEpisodes(pageSize: Int = 200): Result<List<NasEpisode>> {
        val all = mutableListOf<NasEpisode>()
        var offset = 0
        while (true) {
            val page = search(q = null, album = null, limit = pageSize, offset = offset)
                .getOrElse { return Result.failure(it) }
            all += page
            if (page.size < pageSize) break
            offset += page.size
            // Defensive: bail at 50k to avoid runaway loops on a buggy
            // server that always returns full pages.
            if (offset > 50_000) break
        }
        return Result.success(all)
    }

    suspend fun search(q: String?, album: String?, limit: Int = 50, offset: Int = 0):
            Result<List<NasEpisode>> = call { s ->
        val params = buildList {
            q?.takeIf { it.isNotBlank() }?.let     { add("q=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            album?.takeIf { it.isNotBlank() }?.let { add("album=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            add("limit=$limit"); add("offset=$offset")
        }.joinToString("&")
        val req = Request.Builder()
            .url("${s.nasUrl}/episodes?$params")
            .header("Authorization", "Bearer ${s.nasToken}")
            .applyCfAccess(s)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("search HTTP ${resp.code}")
            json.decodeFromString<List<NasEpisode>>(resp.body!!.string())
        }
    }

    suspend fun listAlbums(): Result<List<NasAlbum>> = call { s ->
        val req = Request.Builder()
            .url("${s.nasUrl}/albums")
            .header("Authorization", "Bearer ${s.nasToken}")
            .applyCfAccess(s)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("albums HTTP ${resp.code}")
            json.decodeFromString<List<NasAlbum>>(resp.body!!.string())
        }
    }

    /** Stream URL for a NAS-archived episode; ExoPlayer can range-fetch this. */
    suspend fun audioUrl(episodeId: Long): Result<NasAudio> {
        val s = settings.flow.first()
        if (!s.nasConfigured) return Result.failure(NasNotConfiguredException)
        return Result.success(NasAudio(
            url = "${s.nasUrl}/episodes/$episodeId/audio",
            authHeader = "Bearer ${s.nasToken}",
        ))
    }

    /**
     * Verify-before-prune check used by RetentionWorker. Returns
     *   Result.success(true)  → server has both DB row + on-disk file
     *   Result.success(false) → row missing (404) OR file missing (410)
     *   Result.failure(...)   → network/auth error; caller should NOT
     *                           interpret as "missing"
     * A HEAD round-trip is one socket + zero body bytes, so this is
     * cheap enough to call once per pruning candidate.
     */
    suspend fun episodeExistsOnNas(episodeId: Long): Result<Boolean> = call { s ->
        val req = Request.Builder()
            .url("${s.nasUrl}/episodes/$episodeId")
            .head()
            .header("Authorization", "Bearer ${s.nasToken}")
            .applyCfAccess(s)
            .build()
        http.newCall(req).execute().use { resp ->
            when (resp.code) {
                200 -> true
                404, 410 -> false
                else -> error("HEAD episode/$episodeId HTTP ${resp.code}")
            }
        }
    }

    /**
     * Provider-aware verify-before-prune (v0.1.72). Same contract as
     * [episodeExistsOnNas] but hits the v2 endpoint
     * `HEAD /providers/{provider}/episodes/{externalId}` so YSH rows
     * (with non-numeric externalIds) can be verified too.
     */
    suspend fun episodeExistsOnNasByKey(
        providerId: String,
        externalId: String,
    ): Result<Boolean> = call { s ->
        val req = Request.Builder()
            .url("${s.nasUrl}/providers/$providerId/episodes/${java.net.URLEncoder.encode(externalId, "UTF-8")}")
            .head()
            .header("Authorization", "Bearer ${s.nasToken}")
            .applyCfAccess(s)
            .build()
        http.newCall(req).execute().use { resp ->
            when (resp.code) {
                200 -> true
                404, 410 -> false
                else -> error("HEAD providers/$providerId/episodes/$externalId HTTP ${resp.code}")
            }
        }
    }

    /**
     * Provider-aware upload (v0.1.72). Posts to the v2 endpoint
     * `POST /providers/{provider}/episodes` instead of the legacy
     * AIO-only `/episodes`. YSH rows whose externalId isn't parseable
     * as int can be uploaded this way — the server's v2 handler
     * leaves the legacy `episode_id` column NULL for those rows and
     * uses the `(provider_id, external_id)` composite as the row's
     * identity.
     */
    suspend fun uploadV2(
        providerId: String,
        externalId: String,
        title: String,
        airDate: String?,
        description: String?,
        durationSecs: Long,
        sourceUrl: String,
        audio: File,
        album: String? = null,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Result<Unit> = call { s ->
        val audioBody = audio.asRequestBody("audio/mpeg".toMediaType())
        val countingAudio = if (onProgress != null) {
            CountingRequestBody(audioBody, audio.length(), onProgress)
        } else audioBody
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("external_id", externalId)
            .addFormDataPart("title", title)
            .apply {
                airDate?.let     { addFormDataPart("air_date", it) }
                description?.let { addFormDataPart("description", it) }
                album?.takeIf { it.isNotBlank() }
                    ?.let { addFormDataPart("album", it) }
                addFormDataPart("duration_secs", durationSecs.toString())
                addFormDataPart("source_url", sourceUrl)
            }
            .addFormDataPart("audio", audio.name, countingAudio)
            .build()
        val url = "${s.nasUrl}/providers/$providerId/episodes"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${s.nasToken}")
            .applyCfAccess(s)
            .post(body)
            .build()
        DebugLogger.i("NasClient", "uploadV2($providerId:$externalId) → POST $url (${audio.length()} bytes)")
        http.newCall(req).execute().use { resp ->
            if (resp.code !in 200..201) {
                val bodyPreview = runCatching { resp.body?.string()?.take(200) }.getOrNull().orEmpty()
                DebugLogger.w("NasClient", "uploadV2($providerId:$externalId) — HTTP ${resp.code}: $bodyPreview")
                error("uploadV2 HTTP ${resp.code}: $bodyPreview")
            }
            DebugLogger.d("NasClient", "uploadV2($providerId:$externalId) — HTTP ${resp.code} OK")
        }
    }

    /**
     * Provider-aware streaming URL builder. Mirrors [audioUrl] but
     * uses the v2 endpoint so YSH externalIds (non-numeric strings)
     * round-trip cleanly.
     */
    suspend fun audioUrlByKey(providerId: String, externalId: String): Result<NasAudio> {
        val s = settings.flow.first()
        if (!s.nasConfigured) return Result.failure(NasNotConfiguredException)
        val encoded = java.net.URLEncoder.encode(externalId, "UTF-8")
        return Result.success(NasAudio(
            url = "${s.nasUrl}/providers/$providerId/episodes/$encoded/audio",
            authHeader = "Bearer ${s.nasToken}",
        ))
    }

    private suspend inline fun <T> call(crossinline block: (Settings) -> T): Result<T> {
        val s = settings.flow.first()
        if (!s.nasConfigured) return Result.failure(NasNotConfiguredException)
        // Force every NasClient call onto IO. OkHttp's execute() is
        // blocking; without this, listAlbums/search/upload-from-Main
        // threw NetworkOnMainThreadException on-device. Each individual
        // caller wrapping in withContext(Dispatchers.IO) was easy to
        // forget — centralizing here makes the API safe by default,
        // and a no-op when callers ALSO wrap (nested same-dispatcher
        // withContext is free).
        return withContext(Dispatchers.IO) {
            runCatching { block(s) }
                .onFailure { DebugLogger.w("NasClient", "call → ${it::class.simpleName}: ${it.message}", it) }
        }
    }
}

/**
 * Apply Cloudflare Access service-token headers if the user has
 * configured them. Required when the backup server is fronted by a
 * Cloudflare Tunnel + Access app — without these, the edge 403s
 * every request before it reaches the Bearer-protected FastAPI
 * handler.
 *
 * Top-level so MediaCache's HTTP DataSource factory can reuse the
 * same logic for ExoPlayer streaming.
 */
fun Request.Builder.applyCfAccess(s: Settings): Request.Builder {
    if (s.cfAccessConfigured) {
        header("CF-Access-Client-Id", s.cfAccessClientId)
        header("CF-Access-Client-Secret", s.cfAccessClientSecret)
    }
    return this
}

object NasNotConfiguredException : RuntimeException("NAS not configured")

/**
 * RequestBody wrapper that ticks a callback as okhttp pulls bytes from
 * the underlying delegate. Same pattern used by every "upload progress"
 * recipe — wraps the okio sink in a ForwardingSink and increments a
 * counter on each write call.
 */
private class CountingRequestBody(
    private val delegate: RequestBody,
    private val totalBytes: Long,
    private val onProgress: (Long, Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = totalBytes

    override fun writeTo(sink: BufferedSink) {
        val countingSink = object : ForwardingSink(sink) {
            private var bytes = 0L
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                bytes += byteCount
                onProgress(bytes, totalBytes)
            }
        }.buffer()
        delegate.writeTo(countingSink)
        countingSink.flush()
    }
}

@Serializable
data class NasEpisode(
    val episode_id: Long,
    val title: String,
    val air_date: String? = null,
    val album: String? = null,
    val description: String? = null,
    val duration_secs: Long? = null,
    val file_size: Long,
)

@Serializable
data class NasAlbum(val name: String, val episode_count: Int)

data class NasAudio(val url: String, val authHeader: String)
