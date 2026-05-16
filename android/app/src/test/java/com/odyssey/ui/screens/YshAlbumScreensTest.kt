package com.odyssey.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.show.YshAlbumCatalogRow
import com.odyssey.show.YshAlbumDetailRow
import com.odyssey.show.YshTrackOwnership
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * YSH album list + detail UI contracts (step 10a of the YSH plan).
 *
 * Pure-helper tests (`trackCountLabel`, `yshTrackSubtitle`) lock the
 * subtitle copy without rendering Compose; Compose tests below
 * exercise the row composables directly so we don't depend on Hilt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class YshAlbumScreensTest {

    @get:Rule val composeRule = createComposeRule()

    // ----- pure helpers ---------------------------------------------------

    @Test
    fun trackCountLabel_pluralization_and_all_downloaded_marker() {
        assertEquals(
            "1 track · all downloaded",
            trackCountLabel(catalogRow(totalTracks = 1, downloadedTracks = 1)),
        )
        assertEquals(
            "6 tracks · all downloaded",
            trackCountLabel(catalogRow(totalTracks = 6, downloadedTracks = 6)),
        )
        assertEquals(
            "2 of 6 tracks downloaded",
            trackCountLabel(catalogRow(totalTracks = 6, downloadedTracks = 2)),
        )
        assertEquals(
            "0 of 12 tracks downloaded",
            trackCountLabel(catalogRow(totalTracks = 12, downloadedTracks = 0)),
        )
    }

    @Test
    fun yshTrackSubtitle_includesOrderAndOwnershipState() {
        // Downloaded.
        assertEquals(
            "#3 · downloaded",
            yshTrackSubtitle(detailRow(orderIndex = 2, ownership = YshTrackOwnership.DOWNLOADED)),
        )
        // Streamable (DB row, no file).
        assertEquals(
            "#5 · stream",
            yshTrackSubtitle(detailRow(orderIndex = 4, ownership = YshTrackOwnership.STREAMABLE)),
        )
        // Catalog-only: no DB row, no stream URL -- track exists in
        // the album but isn't in the rotating free pool. Distinct
        // copy so the user can tell which tracks are reachable.
        assertEquals(
            "#1 · not in free pool",
            yshTrackSubtitle(detailRow(orderIndex = 0, ownership = YshTrackOwnership.UNAVAILABLE)),
        )
    }

    // ----- Compose row contracts ------------------------------------------

    @Test
    fun yshAlbumRow_renders_album_name_and_count_and_invokes_callback_on_click() {
        var clicks = 0
        composeRule.setContent {
            YshAlbumRow(
                album = catalogRow(
                    albumName = "Bible Comes Alive - Album 4",
                    totalTracks = 6,
                    downloadedTracks = 2,
                ),
                onClick = { clicks++ },
            )
        }
        composeRule.onNodeWithText("Bible Comes Alive - Album 4").assertIsDisplayed()
        composeRule.onNodeWithText("2 of 6 tracks downloaded").assertIsDisplayed()
        composeRule.onNodeWithTag("ysh-album-row-Bible Comes Alive - Album 4").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun yshTrackRow_renders_title_and_play_button_and_fires_onPlay() {
        var plays = 0
        composeRule.setContent {
            YshTrackRow(
                row = detailRow(
                    skuId = 1958,
                    title = "Madeleine's Courage",
                    orderIndex = 1,
                    ownership = YshTrackOwnership.DOWNLOADED,
                ),
                onPlay = { plays++ },
            )
        }
        composeRule.onNodeWithText("Madeleine's Courage").assertIsDisplayed()
        composeRule.onNodeWithText("#2 · downloaded").assertIsDisplayed()
        composeRule.onNodeWithTag("ysh-track-play-ysh-sku-1958").performClick()
        assertEquals(1, plays)
    }

    @Test
    fun yshTrackRow_hides_play_button_for_UNAVAILABLE_tracks() {
        var plays = 0
        composeRule.setContent {
            YshTrackRow(
                row = detailRow(
                    skuId = 7777,
                    title = "Paid Track",
                    orderIndex = 0,
                    ownership = YshTrackOwnership.UNAVAILABLE,
                ),
                onPlay = { plays++ },
            )
        }
        composeRule.onNodeWithText("Paid Track").assertIsDisplayed()
        composeRule.onNodeWithText("#1 · not in free pool").assertIsDisplayed()
        // Play button is intentionally absent for UNAVAILABLE rows --
        // no DB row means no stream URL.
        composeRule.onAllNodesWithTag("ysh-track-play-ysh-sku-7777").assertCountEquals(0)
        assertEquals(0, plays)
    }

    // ----- helpers --------------------------------------------------------

    private fun catalogRow(
        albumName: String = "Some Album",
        albumId: Long = 1L,
        coverUrl: String? = null,
        totalTracks: Int,
        downloadedTracks: Int,
    ) = YshAlbumCatalogRow(
        albumId = albumId,
        albumName = albumName,
        coverUrl = coverUrl,
        totalTracks = totalTracks,
        downloadedTracks = downloadedTracks,
    )

    private fun detailRow(
        skuId: Long = 1L,
        title: String = "Some YSH Story",
        orderIndex: Int = 0,
        albumImageUrl: String? = null,
        ownership: YshTrackOwnership,
        local: LocalEpisodeEntity? = if (ownership == YshTrackOwnership.UNAVAILABLE) null
                                     else track(externalId = "ysh-sku-$skuId", filePath = if (ownership == YshTrackOwnership.DOWNLOADED) "/x.mp3" else null),
    ) = YshAlbumDetailRow(
        skuId = skuId,
        title = title,
        orderIndex = orderIndex,
        albumImageUrl = albumImageUrl,
        ownership = ownership,
        localRow = local,
    )

    private fun track(
        externalId: String = "ysh-sku-1",
        title: String = "Some YSH Story",
        filePath: String? = null,
        trackOrder: Int? = null,
    ) = LocalEpisodeEntity(
        providerId = "ysh",
        externalId = externalId,
        title = title,
        airDate = "2021-06-01",
        description = "stub",
        sourceUrl = "https://example/$externalId",
        downloadUrl = "https://example/$externalId.mp3",
        filePath = filePath,
        fileSize = if (filePath != null) 100L else 0L,
        durationMs = 30 * 60_000L,
        downloadedAt = if (filePath != null) 1L else null,
        archivedAt = null,
        imageUrl = null,
        albumName = "Some Album",
        albumImageUrl = null,
        albumTrackOrder = trackOrder,
    )
}
