// Headless smoke test for the YSH download path.
//
// Mirrors what com.odyssey.download.EpisodeDownloader actually does
// on the device: same OkHttpClient timeouts, no User-Agent header
// (OkHttp default is "okhttp/4.12.0"), no auth header. Resolves a
// live YSH episode via the OneplaceClient flow, then runs the
// download against the live CDN and reports response code, headers,
// downloaded bytes, and MP3 magic-byte check.
//
// Compile + run via .tools/jdk + .tools/kotlinc (already bootstrapped
// by scripts/run-jvm-tests.sh). See scripts/probe-ysh-download.sh.
package com.odyssey.scripts

import com.odyssey.scrape.OneplaceClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

fun main() = runBlocking {
    val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val LISTEN = "https://www.oneplace.com/ministries/your-story-hour/listen/"
    println("step 1: resolving latest YSH episodes via OneplaceClient")
    val oneplace = OneplaceClient(http)
    val eps = oneplace.newSince(LISTEN, lastSeen = 0L, maxFetch = 3)
    require(eps.isNotEmpty()) { "no YSH episodes returned by oneplace" }
    eps.forEachIndexed { i, ep -> println("  [$i] ${ep.episodeId} \"${ep.title}\" downloadFileUrl=${ep.downloadFileUrl}") }

    val target = eps.first { it.downloadFileUrl.isNotBlank() }
    val url = target.downloadFileUrl
    println("\nstep 2: GET $url (mimicking EpisodeDownloader — no UA override)")
    val req = Request.Builder().url(url).build()
    http.newCall(req).execute().use { resp ->
        println("  HTTP ${resp.code}")
        println("  request UA sent: ${req.header("User-Agent") ?: "<okhttp default>"}")
        println("  response headers:")
        for ((k, v) in resp.headers) println("    $k: $v")
        val body = resp.body ?: error("empty body")
        val tmp = File.createTempFile("ysh-probe-", ".mp3").apply { deleteOnExit() }
        var total = 0L
        tmp.outputStream().use { out ->
            val src = body.source()
            val buf = okio.Buffer()
            while (true) {
                val n = src.read(buf, 64L * 1024)
                if (n == -1L) break
                total += n
                out.write(buf.readByteArray())
                // Cap at 256 KB so we don't pull 20 MB just for a smoke test.
                if (total >= 256L * 1024) break
            }
        }
        val head = tmp.readBytes().take(8)
        val isId3 = head.size >= 3 && head[0] == 0x49.toByte() && head[1] == 0x44.toByte() && head[2] == 0x33.toByte()
        val isMpegSync = head.isNotEmpty() && head[0] == 0xFF.toByte()
        println("\nstep 3: payload sanity")
        println("  downloaded bytes (capped): $total")
        println("  head bytes (hex): ${head.joinToString(" ") { "%02x".format(it) }}")
        println("  is ID3 prefix: $isId3")
        println("  is MPEG frame sync: $isMpegSync")
        check(isId3 || isMpegSync) { "downloaded bytes do not look like MP3" }
        tmp.delete()
    }
    println("\n✓ YSH download path works end-to-end via OkHttp default config")
}
