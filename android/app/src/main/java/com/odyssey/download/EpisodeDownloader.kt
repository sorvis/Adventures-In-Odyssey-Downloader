package com.odyssey.download

import android.content.Context
import com.odyssey.debug.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpisodeDownloader @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val http: OkHttpClient,
) {
    /**
     * Top-level episodes directory. Per-provider subdirectories sit
     * underneath so AIO downloads don't collide with YSH downloads when
     * their externalIds happen to share a numeric range. Exposed
     * package-internal so DiskLayoutMigrator can enumerate legacy files
     * directly under this dir without going through fileFor().
     */
    internal val rootDir: File by lazy {
        File(ctx.getExternalFilesDir(null), "Episodes").apply { mkdirs() }
    }

    /**
     * New provider-aware path: `rootDir/<providerId>/<externalId>-<safeTitle>.mp3`.
     * YSH externalIds like "ysh-sku-1958" round-trip safely through the
     * filename slugger.
     */
    fun fileFor(providerId: String, externalId: String, title: String): File {
        val safeTitle = title.replace(Regex("[^A-Za-z0-9 ._-]"), "_").take(80)
        val safeId = externalId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val providerDir = File(rootDir, providerId).apply { mkdirs() }
        return File(providerDir, "$safeId-$safeTitle.mp3")
    }

    /**
     * Legacy AIO-only shim — keeps DownloadEpisodeWorker / RestoreEpisodeWorker
     * call sites compiling unchanged. Equivalent to
     * `fileFor("aio", episodeId.toString(), title)`.
     */
    fun fileFor(episodeId: Long, title: String): File =
        fileFor("aio", episodeId.toString(), title)

    /**
     * Download the URL to `out`, resuming from `out.length()` if it already
     * exists (server must honor Range: bytes=N-). Returns final byte length.
     *
     * [onProgress] is called periodically (throttled to ~10 Hz) with the
     * current `bytesRead` (cumulative, including any prior partial bytes
     * on disk) and `totalBytes` (the full expected size — partial +
     * Content-Length when resuming, or just Content-Length on a fresh 200).
     * Pass a no-op for callers that don't need progress.
     *
     * Resume gotcha: opening a FileOutputStream truncates the file to 0
     * bytes; seeking past EOF then writing causes the OS to fill the gap
     * with zeros, producing a final file of correct size but with the
     * first N bytes zeroed out — ExoPlayer's MP3 extractor then can't
     * find ID3 / frame sync and rejects the file. Use append mode
     * (which never truncates and writes at current EOF) instead.
     */
    fun download(
        url: String,
        out: File,
        /**
         * Optional auth header for sources that require it (e.g. the
         * self-hosted backup service which checks a bearer token on
         * /episodes/N/audio). Public oneplace.com URLs don't need it
         * and pass null; restore-from-backup passes "Bearer <token>".
         */
        authHeader: String? = null,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Long {
        val partial = out.length()
        val req = Request.Builder()
            .url(url)
            .apply {
                if (partial > 0) header("Range", "bytes=$partial-")
                authHeader?.let { header("Authorization", it) }
            }
            .build()
        DebugLogger.d(TAG, "GET $url${if (partial > 0) " (resume from $partial)" else ""}")
        http.newCall(req).execute().use { resp ->
            // HTTP 416 Range Not Satisfiable — happens when we ask for
            // bytes at or past the resource's end. The way the worker
            // gets stuck here is: a prior attempt downloaded ALL bytes
            // successfully, then was killed (or threw post-download)
            // before `episodes.upsert(filePath=...)` persisted. Row's
            // filePath stays null → next attempt sees out.length() ==
            // content-length → Range:bytes=N- → 416 → silent retry →
            // infinite loop. Observed live for YSH downloads
            // 2026-05-15 — see EpisodeDownloaderTest's 416-recovery
            // tests for the contract.
            //
            // Recovery: HEAD the URL, compare local size to the
            // canonical content-length. Match → trust the local file
            // and report success so the worker's upsert sets filePath
            // and breaks the loop. Mismatch → local file is corrupt
            // (oversized); truncate and throw so the next retry starts
            // clean.
            if (resp.code == 416 && partial > 0) {
                val serverSize = headContentLength(url, authHeader)
                if (serverSize != null && partial == serverSize) {
                    DebugLogger.i(TAG, "416 recovery: local file at $partial bytes matches server — treating as complete (no re-download)")
                    onProgress(partial, partial)
                    return partial
                }
                DebugLogger.w(TAG, "416 for $url with local=$partial vs server=$serverSize — truncating local file for clean retry")
                out.delete()
                error("HTTP 416 for $url (local $partial vs server $serverSize, truncated)")
            }
            if (resp.code != 200 && resp.code != 206) {
                // Log a slim header summary alongside the code so CDN
                // rejections (Cloudflare 403, S3 SignatureDoesNotMatch,
                // etc.) are diagnosable from the in-app debug log
                // (Settings → Open debug logs) without round-tripping
                // back to a curl repro.
                val server = resp.header("server").orEmpty()
                val cfRay = resp.header("cf-ray").orEmpty()
                val contentType = resp.header("content-type").orEmpty()
                DebugLogger.w(TAG, "HTTP ${resp.code} for $url [server=$server cf-ray=$cfRay content-type=$contentType]")
                error("HTTP ${resp.code} for $url")
            }
            // append=true when the server honored our Range request (206).
            // append=false when the server ignored Range and is sending the
            // whole file again (200) — we want a fresh write.
            val append = resp.code == 206 && partial > 0
            out.parentFile?.mkdirs()
            val body = resp.body ?: error("empty body for $url")
            val bodyLen = body.contentLength()  // -1 if server didn't send it
            // For 206 Partial Content the body length is just the rest;
            // total file size = bytes already on disk + the rest.
            val total = when {
                bodyLen < 0 -> -1L
                append -> partial + bodyLen
                else -> bodyLen
            }
            val sink = out.sink(append = append).buffer()
            sink.use { s ->
                val src = body.source()
                var bytesRead = if (append) partial else 0L
                var lastEmitMs = 0L
                onProgress(bytesRead, total)
                while (true) {
                    val n = src.read(s.buffer, CHUNK_BYTES)
                    if (n == -1L) break
                    s.emitCompleteSegments()
                    bytesRead += n
                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs >= MIN_EMIT_INTERVAL_MS) {
                        onProgress(bytesRead, total)
                        lastEmitMs = now
                    }
                }
                onProgress(bytesRead, total)  // final tick at 100%
            }
        }
        return out.length()
    }

    /**
     * Sidecar HEAD to read the canonical Content-Length for [url].
     * Used by the 416-recovery path to decide whether a fully-populated
     * local file matches the server. Returns null if the HEAD fails or
     * the server doesn't expose Content-Length — in which case the
     * caller MUST refuse to mark the download complete (we have no
     * evidence the local bytes are sound).
     */
    private fun headContentLength(url: String, authHeader: String?): Long? {
        val req = Request.Builder()
            .url(url)
            .head()
            .apply { authHeader?.let { header("Authorization", it) } }
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    DebugLogger.w(TAG, "HEAD $url returned ${resp.code} — can't verify completeness")
                    return@use null
                }
                resp.header("Content-Length")?.toLongOrNull()
            }
        }.getOrElse { t ->
            DebugLogger.w(TAG, "HEAD $url threw — can't verify completeness", t)
            null
        }
    }

    private companion object {
        const val TAG = "EpisodeDownloader"
        const val CHUNK_BYTES = 64L * 1024            // 64 KB per read
        const val MIN_EMIT_INTERVAL_MS = 100L          // throttle to ~10 Hz
    }

    fun delete(file: File) { file.delete() }
}
