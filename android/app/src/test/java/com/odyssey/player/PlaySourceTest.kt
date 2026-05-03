package com.odyssey.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins down RecentVm's play-target dispatch — regression test for the
 * bug where undownloaded episodes were silently un-tappable because
 * `play()` short-circuited on `filePath == null`.
 */
class PlaySourceTest {

    @Test
    fun `downloaded episode plays from local file`() {
        val src = playSourceFor(filePath = "/data/odyssey/123.mp3", downloadUrl = "https://example.com/123.mp3")
        assertEquals(PlaySource.Local("/data/odyssey/123.mp3"), src)
    }

    @Test
    fun `not-yet-downloaded episode streams from download URL`() {
        val src = playSourceFor(filePath = null, downloadUrl = "https://example.com/123.mp3")
        assertEquals(PlaySource.Stream("https://example.com/123.mp3"), src)
    }

    @Test
    fun `empty filePath string is treated as a local file path, not as missing`() {
        // We use null to mean "not downloaded yet" — an empty string is some
        // other state that callers should not produce. Test pins the contract.
        val src = playSourceFor(filePath = "", downloadUrl = "https://example.com/123.mp3")
        assertEquals(PlaySource.Local(""), src)
    }

    @Test
    fun `downloaded episode replays from local file and never re-streams`() {
        // Regression lock: once an episode is on disk (filePath set), play
        // dispatch must use the local file, NOT re-fetch from downloadUrl.
        // This is the core "download → replay offline" guarantee — a bug
        // here would silently waste data and break offline playback.
        val downloadUrl = "https://example.com/should-not-be-used.mp3"
        val src = playSourceFor(filePath = "/data/odyssey/123.mp3", downloadUrl = downloadUrl)

        assertEquals(PlaySource.Local("/data/odyssey/123.mp3"), src)
        assertTrue("expected Local for downloaded episode, got $src", src is PlaySource.Local)
        assertFalse(
            "downloaded episode must not be dispatched as Stream",
            src is PlaySource.Stream,
        )
    }
}
