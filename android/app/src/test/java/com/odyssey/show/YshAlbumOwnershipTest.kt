package com.odyssey.show

import com.odyssey.catalog.AlbumFilter
import com.odyssey.catalog.AlbumSort
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

    // ----- filterYshAlbums -----------------------------------------------

    @Test
    fun `filter All returns every row`() {
        val rows = listOf(row(albumId = 1, downloaded = 0), row(albumId = 2, downloaded = 3))
        assertEquals(rows, filterYshAlbums(rows, AlbumFilter.All))
    }

    @Test
    fun `filter HasOnPhone keeps only rows with at least one downloaded track`() {
        val rows = listOf(
            row(albumId = 1, name = "A", downloaded = 0),
            row(albumId = 2, name = "B", downloaded = 1),
            row(albumId = 3, name = "C", downloaded = 12),
        )
        val out = filterYshAlbums(rows, AlbumFilter.HasOnPhone)
        assertEquals(listOf(2L, 3L), out.map { it.albumId })
    }

    @Test
    fun `filter HasOnBackup is defensively empty for YSH -- no backup path yet`() {
        val rows = listOf(row(albumId = 1, downloaded = 5))
        assertEquals(emptyList<YshAlbumCatalogRow>(), filterYshAlbums(rows, AlbumFilter.HasOnBackup))
    }

    // ----- sortYshAlbums -------------------------------------------------

    @Test
    fun `sort Default is case-insensitive alphabetical`() {
        val rows = listOf(
            row(albumId = 30, name = "zebra"),
            row(albumId = 20, name = "Apple"),
            row(albumId = 10, name = "MIDDLE"),
        )
        val out = sortYshAlbums(rows, AlbumSort.Default)
        assertEquals(listOf("Apple", "MIDDLE", "zebra"), out.map { it.albumName })
    }

    @Test
    fun `sort Chronological is by albumId ascending`() {
        val rows = listOf(
            row(albumId = 30, name = "zebra"),
            row(albumId = 10, name = "Apple"),
            row(albumId = 20, name = "MIDDLE"),
        )
        val out = sortYshAlbums(rows, AlbumSort.Chronological)
        assertEquals(listOf(10L, 20L, 30L), out.map { it.albumId })
    }

    @Test
    fun `sort MostDownloaded floats highest ratio first with stable alpha tiebreak`() {
        val rows = listOf(
            row(albumId = 1, name = "Zebra Zoo", total = 10, downloaded = 0),    // 0%
            row(albumId = 2, name = "Apple Acres", total = 4, downloaded = 4),   // 100%
            row(albumId = 3, name = "Middle Mile", total = 10, downloaded = 5),  // 50%
            row(albumId = 4, name = "Allenburg", total = 8, downloaded = 8),     // 100% — alpha-first on tie
        )
        val out = sortYshAlbums(rows, AlbumSort.MostDownloaded)
        assertEquals(
            "100% albums first (alpha tiebreak), 50% next, 0% last",
            listOf("Allenburg", "Apple Acres", "Middle Mile", "Zebra Zoo"),
            out.map { it.albumName },
        )
    }

    @Test
    fun `sort MostDownloaded treats totalTracks=0 as zero ratio (no div by zero)`() {
        val rows = listOf(
            row(albumId = 1, name = "Empty Album", total = 0, downloaded = 0),
            row(albumId = 2, name = "Has Tracks", total = 6, downloaded = 3),
        )
        val out = sortYshAlbums(rows, AlbumSort.MostDownloaded)
        assertEquals(listOf("Has Tracks", "Empty Album"), out.map { it.albumName })
    }

    // ----- helpers --------------------------------------------------------

    private fun row(
        albumId: Long,
        name: String = "Album $albumId",
        coverUrl: String? = null,
        total: Int = 6,
        downloaded: Int = 0,
    ) = YshAlbumCatalogRow(
        albumId = albumId,
        albumName = name,
        coverUrl = coverUrl,
        totalTracks = total,
        downloadedTracks = downloaded,
    )

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
