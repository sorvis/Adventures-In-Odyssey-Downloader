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
}
