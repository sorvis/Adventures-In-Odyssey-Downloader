package com.odyssey.download

import android.content.Context
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
    private val rootDir: File by lazy {
        File(ctx.getExternalFilesDir(null), "Episodes").apply { mkdirs() }
    }

    fun fileFor(episodeId: Long, title: String): File {
        val safe = title.replace(Regex("[^A-Za-z0-9 ._-]"), "_").take(80)
        return File(rootDir, "$episodeId-$safe.mp3")
    }

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
        http.newCall(req).execute().use { resp ->
            if (resp.code != 200 && resp.code != 206) {
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

    private companion object {
        const val CHUNK_BYTES = 64L * 1024            // 64 KB per read
        const val MIN_EMIT_INTERVAL_MS = 100L          // throttle to ~10 Hz
    }

    fun delete(file: File) { file.delete() }
}
