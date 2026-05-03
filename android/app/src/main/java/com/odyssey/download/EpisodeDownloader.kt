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
            val append = resp.code == 206 && partial > 0
            out.parentFile?.mkdirs()
            val sink = if (append) {
                out.outputStream().also { it.channel.position(partial) }.sink().buffer()
            } else {
                out.sink(append = false).buffer()
            }
            sink.use { s -> resp.body?.source()?.let { src -> s.writeAll(src) } }
        }
        return out.length()
    }

    fun delete(file: File) { file.delete() }
}
