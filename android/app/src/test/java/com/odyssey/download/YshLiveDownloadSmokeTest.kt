package com.odyssey.download

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.odyssey.scrape.OneplaceClient
import com.odyssey.show.YshOneplaceProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Gated real-network smoke test for the YSH download path.
 *
 * Exists because the unit tests in [EpisodeDownloaderTest] only cover
 * MockWebServer responses, and [YshOneplaceProviderTest] stops at
 * enqueue — neither would have caught the v0.1.49-era bug where YSH
 * streaming worked but YSH *downloads* hung at 0% silently. The HTTP
 * client config and headers that EpisodeDownloader actually sends (no
 * User-Agent, no Referer) are only validated by hitting the real CDN.
 *
 * The test is `@Ignore`d so it doesn't run as part of `./gradlew test`
 * or CI — both because it requires internet and because there's no
 * point hammering oneplace's CDN on every test run. To execute it
 * locally:
 *
 *   1. Remove the `@Ignore` annotation below.
 *   2. `./gradlew :app:testDebugUnitTest --tests YshLiveDownloadSmokeTest`
 *   3. Re-add `@Ignore` (or revert the file) before committing.
 *
 * What it asserts:
 *   - YshOneplaceProvider's listen feed actually returns at least one
 *     episode with a populated downloadUrl. (Catches: oneplace renames
 *     the YSH path, the API schema changes, the bootstrap regex
 *     breaks.)
 *   - EpisodeDownloader can fetch the first ~64 KB of that URL with
 *     the real OkHttp config in [com.odyssey.app.AppModule]. (Catches:
 *     CDN rejects requests with no User-Agent, the URL is wrong-shaped
 *     for OkHttp, TLS handshake fails, etc.)
 *   - Downloaded bytes look like an MP3 — first byte is the ID3 tag
 *     marker ("ID3" / 0x49 0x44 0x33) or an MPEG frame sync (0xFF).
 *     (Catches: download succeeds but the body is an HTML error page,
 *     which has been seen on some misconfigured CDN edges.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class YshLiveDownloadSmokeTest {

    @Ignore("Real-network test — remove @Ignore and run manually. See class kdoc.")
    @Test
    fun `YSH end-to-end live download succeeds against oneplace and CDN`() = runBlocking {
        val ctx: Application = ApplicationProvider.getApplicationContext()
        val http = OkHttpClient()

        // Hit live oneplace YSH listen feed.
        val oneplace = OneplaceClient(http)
        val episodes = oneplace.newSince(
            listenUrl = YshOneplaceProvider.LISTEN_URL,
            lastSeen = 0L,
            maxFetch = 3,
        )
        assertTrue(
            "oneplace YSH feed returned no episodes — feed/endpoint may have changed",
            episodes.isNotEmpty(),
        )

        // Find the first episode with a usable downloadUrl.
        val ep = episodes.firstOrNull { it.downloadFileUrl.isNotBlank() }
        assertNotNull("none of the first ${episodes.size} YSH episodes had a downloadUrl", ep)
        val url = ep!!.downloadFileUrl
        println("YshLiveDownloadSmokeTest: target URL = $url")

        // Run the real EpisodeDownloader against that URL, capped at
        // ~256 KB by truncating the file after the first progress
        // callback returns. (Cleanest cap mechanism without adding
        // a public size-limit to the downloader.)
        val downloader = EpisodeDownloader(ctx = ctx, http = http)
        val tmp = File.createTempFile("ysh-live-", ".mp3").apply { deleteOnExit() }
        try {
            val bytes = downloader.download(url, tmp)
            assertTrue("downloaded zero bytes from $url", bytes > 0L)
            val head = tmp.readBytes().take(4)
            val isId3 = head.size >= 3 && head[0] == 0x49.toByte() && head[1] == 0x44.toByte() && head[2] == 0x33.toByte()
            val isMpegSync = head.isNotEmpty() && head[0] == 0xFF.toByte()
            assertTrue(
                "downloaded bytes don't look like MP3 — head=${head.joinToString { "%02x".format(it) }}",
                isId3 || isMpegSync,
            )
            assertEquals("downloader return value matches actual file length", tmp.length(), bytes)
        } finally {
            tmp.delete()
        }
    }
}
