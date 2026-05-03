package com.odyssey.player

import org.junit.Assert.assertEquals
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
}
