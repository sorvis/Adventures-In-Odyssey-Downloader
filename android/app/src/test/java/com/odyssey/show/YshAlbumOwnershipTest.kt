package com.odyssey.show

import com.odyssey.data.local.YshAlbumSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM tests for [joinYshAlbumOwnership]. No Robolectric, no Room.
 *
 * Locks in:
 *   - The YSH Albums tab shows EVERY catalog album, even ones with zero
 *     local tracks (the previous behavior — empty list until ingestion
 *     happens — is the bug this helper fixes).
 *   - Per-album `downloadedTracks` is overlaid from the DB summary;
 *     albums with no DB match get 0.
 *   - Albums in the DB but not in the catalog are dropped (catalog is
 *     the source of truth, so a stale-DB row for a discontinued album
 *     doesn't pollute the list).
 *   - Sort is case-insensitive ascending so "Beyond the Hidden Door"
 *     and "beyond the hidden door" don't reorder if YSH ever changes
 *     casing on us.
 */
class YshAlbumOwnershipTest {

    @Test
    fun `empty catalog returns empty list even with DB rows present`() {
        val rows = joinYshAlbumOwnership(
            catalog = catalog(),  // no tracks
            dbSummaries = listOf(summary("Phantom Album", trackCount = 3, downloadedCount = 2)),
        )
        assertEquals(emptyList<YshAlbumCatalogRow>(), rows)
    }

    @Test
    fun `catalog populated, DB empty -- every album shows with zero downloads`() {
        val rows = joinYshAlbumOwnership(
            catalog = catalog(
                track(skuId = 100, albumId = 1, albumTitle = "Adventures of Asia", title = "Track A"),
                track(skuId = 101, albumId = 1, albumTitle = "Adventures of Asia", title = "Track B"),
                track(skuId = 200, albumId = 2, albumTitle = "Bible Comes Alive", title = "Genesis 1"),
            ),
            dbSummaries = emptyList(),
        )
        assertEquals(2, rows.size)
        assertEquals("Adventures of Asia", rows[0].albumName)
        assertEquals(2, rows[0].totalTracks)
        assertEquals(0, rows[0].downloadedTracks)
        assertEquals("Bible Comes Alive", rows[1].albumName)
        assertEquals(1, rows[1].totalTracks)
        assertEquals(0, rows[1].downloadedTracks)
    }

    @Test
    fun `DB downloadedCount overlays on the matching catalog row`() {
        val rows = joinYshAlbumOwnership(
            catalog = catalog(
                track(skuId = 100, albumId = 1, albumTitle = "Adventures of Asia", title = "T1"),
                track(skuId = 101, albumId = 1, albumTitle = "Adventures of Asia", title = "T2"),
                track(skuId = 102, albumId = 1, albumTitle = "Adventures of Asia", title = "T3"),
                track(skuId = 200, albumId = 2, albumTitle = "Bible Comes Alive", title = "B1"),
            ),
            dbSummaries = listOf(
                summary("Adventures of Asia", trackCount = 2, downloadedCount = 2),
            ),
        )
        val adventures = rows.single { it.albumId == 1L }
        assertEquals("totalTracks comes from catalog (3), not the DB summary (2)", 3, adventures.totalTracks)
        assertEquals("downloadedTracks comes from the DB summary", 2, adventures.downloadedTracks)
        val bible = rows.single { it.albumId == 2L }
        assertEquals(1, bible.totalTracks)
        assertEquals("no DB summary -> zero downloads", 0, bible.downloadedTracks)
    }

    @Test
    fun `DB-only album not in catalog is dropped from the listing`() {
        val rows = joinYshAlbumOwnership(
            catalog = catalog(
                track(skuId = 100, albumId = 1, albumTitle = "In Catalog", title = "T1"),
            ),
            dbSummaries = listOf(
                summary("Stale Album From Old DB", trackCount = 5, downloadedCount = 1),
            ),
        )
        assertEquals(listOf("In Catalog"), rows.map { it.albumName })
    }

    @Test
    fun `cover URL flows from the catalog -- not from the DB summary`() {
        val rows = joinYshAlbumOwnership(
            catalog = catalog(
                track(
                    skuId = 100, albumId = 1, albumTitle = "Adventures of Asia",
                    title = "T1", albumImageUrl = "https://catalog.example/cover.png",
                ),
            ),
            dbSummaries = listOf(
                summary("Adventures of Asia", trackCount = 0, downloadedCount = 0,
                    coverUrl = "https://db.example/different.png"),
            ),
        )
        assertEquals("https://catalog.example/cover.png", rows.single().coverUrl)
    }

    @Test
    fun `case-insensitive alphabetical sort`() {
        val rows = joinYshAlbumOwnership(
            catalog = catalog(
                track(skuId = 1, albumId = 30, albumTitle = "zebra", title = "x"),
                track(skuId = 2, albumId = 20, albumTitle = "Apple", title = "x"),
                track(skuId = 3, albumId = 10, albumTitle = "MIDDLE", title = "x"),
            ),
            dbSummaries = emptyList(),
        )
        assertEquals(listOf("Apple", "MIDDLE", "zebra"), rows.map { it.albumName })
    }

    @Test
    fun `null album image survives through to the row`() {
        val rows = joinYshAlbumOwnership(
            catalog = catalog(
                track(skuId = 1, albumId = 1, albumTitle = "Coverless", title = "x", albumImageUrl = null),
            ),
            dbSummaries = emptyList(),
        )
        assertEquals(null, rows.single().coverUrl)
    }

    // ----- helpers --------------------------------------------------------

    private fun catalog(vararg tracks: YshCatalogTrack) = YshCatalogIndex(
        scrapedAtMs = 1_700_000_000_000L,
        tracks = tracks.toList(),
    )

    private fun track(
        skuId: Long,
        albumId: Long,
        albumTitle: String,
        title: String,
        albumImageUrl: String? = "https://example/$albumId.png",
        orderIndex: Int = 0,
    ) = YshCatalogTrack(
        skuId = skuId,
        title = title,
        albumId = albumId,
        albumTitle = albumTitle,
        albumSlug = albumTitle.lowercase().replace(" ", "-"),
        albumImageUrl = albumImageUrl,
        orderIndex = orderIndex,
    )

    private fun summary(
        albumName: String,
        trackCount: Int,
        downloadedCount: Int,
        coverUrl: String? = null,
    ) = YshAlbumSummary(
        albumName = albumName,
        coverUrl = coverUrl,
        trackCount = trackCount,
        downloadedCount = downloadedCount,
    )
}
