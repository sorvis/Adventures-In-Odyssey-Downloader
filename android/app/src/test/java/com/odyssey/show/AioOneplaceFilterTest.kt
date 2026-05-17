package com.odyssey.show

import com.odyssey.scrape.OneplaceEpisode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the AIO showId filter.
 *
 * The bug this prevents: oneplace.com's `/api/related-episodes`
 * endpoint doesn't restrict to the requested show. When
 * `AioOneplaceProvider` walked back from the AIO latest cursor, the
 * API returned episodes from any oneplace ministry — Jay Sekulow
 * Live (showId=663), Focus on the Family, etc. Without this filter
 * those rows landed in the DB with `providerId="aio"` and showed up
 * in the AIO Library tab. User report 2026-05-17: "Sekulow is in my
 * Adventures in Odyssey library."
 *
 * Verified live 2026-05-17: GETting
 * `/api/related-episodes?eid=1278390&ps=20` (one past the actual AIO
 * latest) returned 20 Sekulow episodes (showId=663) — proving the
 * API offers no show-side filtering on a non-existent seed eid.
 */
class AioOneplaceFilterTest {

    @Test
    fun `AIO episode passes the filter via showId`() {
        assertTrue(
            isAio(episode(showId = 777L, downloadUrl = "https://zcast.swncdn.com/episodes/zcast/adventures-in-odyssey/2026/05-11/1278389/777_x.mp3")),
        )
    }

    @Test
    fun `Sekulow episode is filtered out -- showId=663 is not AIO`() {
        assertFalse(
            "Sekulow episode (showId=663) must not be treated as AIO -- this is the leak the bug fix prevents",
            isAio(episode(showId = 663L, downloadUrl = "https://zcast.swncdn.com/episodes/zcast/jay-sekulow-live/2026/04-16/1278252/663_x.mp3")),
        )
    }

    @Test
    fun `Focus on the Family episode is filtered out -- showId mismatch`() {
        assertFalse(
            isAio(episode(showId = 555L, downloadUrl = "https://zcast.swncdn.com/episodes/zcast/focus-on-the-family/2026/05-11/x/555_x.mp3")),
        )
    }

    @Test
    fun `null showId falls back to URL slug match -- AIO downloadUrl is accepted`() {
        assertTrue(
            "API future-proofing: if showId is dropped, the AIO path slug still identifies the episode",
            isAio(episode(showId = null, downloadUrl = "https://zcast.swncdn.com/episodes/zcast/adventures-in-odyssey/2026/05-11/1278389/777_x.mp3")),
        )
    }

    @Test
    fun `null showId AND non-AIO slug is filtered out -- defensive default-deny`() {
        assertFalse(
            isAio(episode(showId = null, downloadUrl = "https://zcast.swncdn.com/episodes/zcast/jay-sekulow-live/2026/04-16/x/663_x.mp3")),
        )
    }

    // ----- helpers --------------------------------------------------------

    private fun episode(
        showId: Long?,
        downloadUrl: String,
        episodeId: Long = 1L,
        title: String = "Some Title",
    ) = OneplaceEpisode(
        episodeId = episodeId,
        title = title,
        downloadFileUrl = downloadUrl,
        url = "https://oneplace.com/whatever",
        showId = showId,
    )
}
