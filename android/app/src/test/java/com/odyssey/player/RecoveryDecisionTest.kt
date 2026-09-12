package com.odyssey.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the recovery policy shared by every provider:
 *
 *   - A row with no file on disk is nothing to repair.
 *   - A file that really is an MP3 stays put — ExoPlayer rejecting it
 *     means re-fetching would just hand us the same bytes back.
 *   - Junk bytes on a CDN-sourced row → re-download.
 *   - Junk bytes on a `backup://` row → restore from the NAS. Routing
 *     these through the download worker would delete the bad file and
 *     never replace it: DownloadEpisodeWorker fail-fasts on
 *     `backup://` URLs by design (v0.1.75).
 */
class RecoveryDecisionTest {

    private val html = "<!DOCTYPE html>".toByteArray()
    private val id3 = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00)

    @Test
    fun `no filePath is a skip`() {
        val out = decideRecovery(null, "https://cdn.example/ep.mp3", html)
        assertEquals(
            RecoveryAction.Skip("row has no filePath (already streaming) — nothing to recover"),
            out,
        )
    }

    @Test
    fun `valid mp3 is left alone`() {
        val out = decideRecovery("/data/ep.mp3", "https://cdn.example/ep.mp3", id3)
        assertEquals(
            RecoveryAction.Skip(
                "file is a valid MP3 — re-fetching would return the same bytes",
            ),
            out,
        )
    }

    @Test
    fun `html error page on a cdn row re-downloads`() {
        assertEquals(
            RecoveryAction.Redownload,
            decideRecovery("/data/ep.mp3", "https://cdn.example/ep.mp3", html),
        )
    }

    @Test
    fun `html error page on a backup row restores from the NAS`() {
        assertEquals(
            RecoveryAction.Restore,
            decideRecovery("/data/ep.mp3", "backup://ysh-sku-447", html),
        )
    }

    @Test
    fun `unreadable file counts as corrupt`() {
        // firstBytesOf() hands over an empty array when the file is
        // missing or unreadable — that must not read as a valid MP3.
        assertEquals(
            RecoveryAction.Redownload,
            decideRecovery("/data/ep.mp3", "https://cdn.example/ep.mp3", ByteArray(0)),
        )
    }

    @Test
    fun `a backup row that holds a real mp3 is still left alone`() {
        assertEquals(
            RecoveryAction.Skip(
                "file is a valid MP3 — re-fetching would return the same bytes",
            ),
            decideRecovery("/data/ep.mp3", "backup://ysh-sku-447", id3),
        )
    }
}
