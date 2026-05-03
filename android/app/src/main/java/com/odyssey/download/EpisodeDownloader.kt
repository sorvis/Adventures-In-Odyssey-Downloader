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
     * Resume gotcha: opening a FileOutputStream truncates the file to 0
     * bytes; seeking past EOF then writing causes the OS to fill the gap
     * with zeros, producing a final file of correct size but with the
     * first N bytes zeroed out — ExoPlayer's MP3 extractor then can't
     * find ID3 / frame sync and rejects the file. Use append mode
     * (which never truncates and writes at current EOF) instead.
     */
    fun download(url: String, out: File): Long {
        val partial = out.length()
        val req = Request.Builder()
            .url(url)
            .apply { if (partial > 0) header("Range", "bytes=$partial-") }
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
            val sink = out.sink(append = append).buffer()
            sink.use { s -> resp.body?.source()?.let { src -> s.writeAll(src) } }
        }
        return out.length()
    }

    fun delete(file: File) { file.delete() }
}
