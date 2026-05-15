package com.odyssey.download

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Regression test for the v0.1.10 download bug:
 *   - First attempt downloads N bytes, then app is killed.
 *   - Second attempt sends "Range: bytes=N-" and gets 206 Partial Content.
 *   - The OLD code did `out.outputStream().channel.position(N).sink()`,
 *     which truncated the file to 0 first, then wrote at offset N. The
 *     OS filled the gap [0..N) with zeros — file was correct *size* but
 *     started with N zero bytes, so ExoPlayer's MP3 extractor (and
 *     every other extractor) couldn't find ID3 / frame sync and
 *     rejected the file with UnrecognizedInputFormatException.
 *
 * The fix: open in append mode, never truncate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class EpisodeDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var downloader: EpisodeDownloader
    private lateinit var tmpFile: File

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        downloader = EpisodeDownloader(
            ctx = ApplicationProvider.getApplicationContext<Application>(),
            http = OkHttpClient(),
        )
        tmpFile = File.createTempFile("episode-test-", ".mp3").apply { deleteOnExit() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        tmpFile.delete()
    }

    @Test
    fun `fresh download writes the entire body without leading zeros`() {
        val body = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x01, 0x02, 0x03)  // "ID3" + payload
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(body)))

        // Empty starting file → fresh write path (append=false in code).
        tmpFile.writeBytes(byteArrayOf())
        val len = downloader.download(server.url("/").toString(), tmpFile)

        val written = tmpFile.readBytes()
        assertEquals("returned length matches actual file length", written.size.toLong(), len)
        assertArrayEquals("file content equals server body", body, written)
    }

    @Test
    fun `resume download (206) preserves the existing prefix bytes — no zero padding`() {
        // Prime the file with a partial download — pretend bytes [0..3) were
        // written before the app was killed. Real-world this would be the
        // start of an ID3 tag.
        val prefix = byteArrayOf(0x49, 0x44, 0x33)   // "ID3"
        tmpFile.writeBytes(prefix)

        // Server returns 206 with the rest of the file.
        val rest = byteArrayOf(0x04, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05)
        server.enqueue(MockResponse().setResponseCode(206).setBody(okio.Buffer().write(rest)))

        val len = downloader.download(server.url("/").toString(), tmpFile)
        val written = tmpFile.readBytes()

        // Total length is prefix + rest.
        assertEquals((prefix.size + rest.size).toLong(), len)
        // CRITICAL: first 3 bytes are still "ID3" — NOT zeros (the bug
        // signature). If this assertion fails, the resume path has
        // regressed and ExoPlayer will reject every resumed download.
        assertEquals(0x49.toByte(), written[0])
        assertEquals(0x44.toByte(), written[1])
        assertEquals(0x33.toByte(), written[2])
        assertNotEquals("first byte must not be zero (the regression signature)", 0.toByte(), written[0])
        // ...and the rest of the file matches what the server sent.
        assertArrayEquals(prefix + rest, written)
    }

    @Test
    fun `server ignoring Range and returning 200 truncates and rewrites cleanly`() {
        // Prime the file with 5 bytes; server responds 200 (ignores Range).
        // The downloader should overwrite from 0, not append.
        tmpFile.writeBytes(byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00))

        val full = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0xFF.toByte(), 0xFB.toByte())
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(full)))

        downloader.download(server.url("/").toString(), tmpFile)
        assertArrayEquals("file content equals 200-response body (no append)", full, tmpFile.readBytes())
    }

    /**
     * Regression test for the v0.1.50-era YSH retry loop:
     *
     *   - A previous download attempt successfully wrote ALL N bytes to disk
     *     but was killed by the OS (or threw in upsert) before
     *     `episodes.upsert(filePath=...)` persisted. The DB row's `filePath`
     *     is still null.
     *   - On the next worker run, EpisodeDownloader sees `out.length() == N`
     *     and sends `Range: bytes=N-`. Servers MUST respond 416 to a range
     *     starting at or beyond the resource length (RFC 7233 §4.4).
     *   - Pre-fix behavior: the 416 threw, runCatching wrapped it as
     *     Result.retry(), and the loop ran forever — exactly the YSH
     *     "stuck at 0%" symptom from device logs.
     *
     * Fix: on 416 with partial > 0, HEAD the URL and compare. If local file
     * matches server content-length, treat as already-downloaded.
     */
    @Test
    fun `416 on full-size resume verifies via HEAD and returns success without re-downloading`() {
        val body = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x01, 0x02, 0x03)  // 8 bytes
        tmpFile.writeBytes(body)  // file already complete on disk

        // Worker sees out.length() == 8 and sends Range: bytes=8-. Server's
        // correct behavior per RFC 7233 is 416 with optional Content-Range.
        server.enqueue(MockResponse().setResponseCode(416))
        // Fix path: a HEAD follow-up to confirm the canonical resource size.
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Length", body.size.toString()))

        val len = downloader.download(server.url("/foo.mp3").toString(), tmpFile)

        assertEquals("returned length equals existing on-disk size", body.size.toLong(), len)
        assertArrayEquals("on-disk bytes are untouched (no wasteful re-download)", body, tmpFile.readBytes())
        // Two requests: the Range GET, then the verification HEAD.
        assertEquals(2, server.requestCount)
        val rangeReq = server.takeRequest()
        assertEquals("GET", rangeReq.method)
        assertEquals("bytes=${body.size}-", rangeReq.getHeader("Range"))
        val headReq = server.takeRequest()
        assertEquals("HEAD", headReq.method)
    }

    /**
     * Corruption case: local file is LARGER than the canonical resource
     * (e.g. a previous over-eager fsync wrote extra bytes, or the resource
     * was truncated server-side after our first download). Trusting the
     * local bytes would leave a permanently-broken MP3 in the library.
     * Behavior contract: truncate the local file and propagate the error
     * so WorkManager retries; the next retry starts fresh at partial=0.
     */
    @Test
    fun `416 with local size larger than server content-length truncates local file and throws`() {
        // Pre-existing file is 12 bytes; server says canonical size is 8.
        val oversized = ByteArray(12) { 0xCC.toByte() }
        tmpFile.writeBytes(oversized)

        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Length", "8"))

        val thrown = assertThrows(IllegalStateException::class.java) {
            downloader.download(server.url("/foo.mp3").toString(), tmpFile)
        }
        assertTrue(
            "error message mentions 416 and the size mismatch",
            thrown.message!!.contains("416"),
        )
        assertEquals(
            "local file is truncated (size 0 or deleted) so the next retry starts fresh",
            0L, tmpFile.length(),
        )
    }

    /**
     * Edge case: HEAD doesn't return Content-Length (server doesn't support
     * HEAD, or returns an error). We can't verify completeness, so the
     * safest move is to throw — the worker retries, exponential backoff
     * eventually gives up. We do NOT silently trust the local file in this
     * case because we have no evidence of completeness.
     */
    @Test
    fun `416 with no Content-Length from HEAD throws so we never falsely mark incomplete files as done`() {
        tmpFile.writeBytes(byteArrayOf(0x49, 0x44, 0x33))   // 3 bytes locally
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setResponseCode(500))  // HEAD fails

        assertThrows(IllegalStateException::class.java) {
            downloader.download(server.url("/foo.mp3").toString(), tmpFile)
        }
    }
}
