package com.odyssey.show

import com.odyssey.catalog.AlbumFilter
import com.odyssey.catalog.AlbumSort
import com.odyssey.data.local.LocalEpisodeEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM tests for [joinYshAlbumOwnership]. No Robolectric, no Room.
 *
 * Locks in:
 *   - The YSH Albums tab shows EVERY catalog album, even ones with zero
 *     local tracks.
 *   - Per-album `downloadedTracks` comes from counting DB rows whose
 *     skuId-from-externalId appears in the album's catalog tracks.
 *     CRITICAL: this is keyed off skuId, NOT the DB row's `albumName`
 *     field (which DailyCheckWorker leaves null on every YSH row).
 *     A pre-v0.1.58 albumName-keyed join missed every downloaded YSH
 *     episode in the user's library — see the
 *     "albumName-is-null regression" test below.
 *   - Catalog drives the list; DB rows for unknown albums are ignored.
 *   - Sort is case-insensitive ascending.
 */
class YshAlbumOwnershipTest {

    @Test
    fun `empty catalog returns empty list even with DB rows present`() {
        val rows = joinYshAlbumOwnership(
            catalog = catalog(),  // no tracks
            dbRows = listOf(downloadedRow(skuId = 100, albumName = null)),
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
            dbRows = emptyList(),
        )
        assertEquals(2, rows.size)
        assertEquals("Adventures of Asia", rows[0].albumName)
        assertEquals(2, rows[0].totalTracks)
        assertEquals(0, rows[0].downloadedTracks)
        assertEquals("Bible Comes Alive", rows[1].albumName)
        assertEquals(1, rows[1].totalTracks)
        assertEquals(0, rows[1].downloadedTracks)
    }

    /**
     * **Regression test for the v0.1.58 fix.** Pre-fix, the join keyed
     * off `dbSummary.albumName` aggregated from a SQL GROUP BY on
     * `local_episodes.albumName`. But DailyCheckWorker never populated
     * `albumName` on YSH rows (defaults to null), so the SQL aggregation
     * returned zero rows, and every album in the UI showed "0/N
     * downloaded" even when the user had files on disk.
     *
     * Post-fix: the join keys off skuId parsed from `externalId`. The
     * DB row's `albumName` is now irrelevant to the count.
     */
    @Test
    fun `downloadedTracks counts rows by skuId even when DB row has albumName=null`() {
        val rows = joinYshAlbumOwnership(
            catalog = catalog(
                track(skuId = 100, albumId = 1, albumTitle = "Adventures of Asia", title = "T1"),
                track(skuId = 101, albumId = 1, albumTitle = "Adventures of Asia", title = "T2"),
                track(skuId = 102, albumId = 1, albumTitle = "Adventures of Asia", title = "T3"),
            ),
            dbRows = listOf(
                downloadedRow(skuId = 100, albumName = null),
                downloadedRow(skuId = 102, albumName = null),
            ),
        )
        val adventures = rows.single()
        assertEquals("totalTracks comes from catalog", 3, adventures.totalTracks)
        assertEquals(
            "downloadedTracks must equal the number of DB rows whose skuId is in the album — albumName=null in DB is irrelevant",
            2, adventures.downloadedTracks,
        )
    }

    @Test
    fun `DB rows from other albums or providers do not pollute the count`() {
        val rows = joinYshAlbumOwnership(
            catalog = catalog(
                track(skuId = 100, albumId = 1, albumTitle = "Target Album", title = "T1"),
            ),
            dbRows = listOf(
                downloadedRow(skuId = 100, albumName = null),                       // matches
                downloadedRow(skuId = 999, albumName = null),                       // sku not in catalog
                downloadedRow(skuId = 100, providerId = "aio", albumName = null),  // AIO row, ignored
            ),
        )
        assertEquals(1, rows.single().downloadedTracks)
    }

    @Test
    fun `rows without a file are not counted as downloaded`() {
        val rows = joinYshAlbumOwnership(
            catalog = catalog(
                track(skuId = 100, albumId = 1, albumTitle = "A", title = "T1"),
            ),
            dbRows = listOf(
                streamableRow(skuId = 100, albumName = null),  // ingested but no file -> streamable
            ),
        )
        assertEquals("STREAMABLE rows are not on phone", 0, rows.single().downloadedTracks)
    }

    @Test
    fun `case-insensitive alphabetical sort`() {
        val rows = joinYshAlbumOwnership(
            catalog = catalog(
                track(skuId = 1, albumId = 30, albumTitle = "zebra", title = "x"),
                track(skuId = 2, albumId = 20, albumTitle = "Apple", title = "x"),
                track(skuId = 3, albumId = 10, albumTitle = "MIDDLE", title = "x"),
            ),
            dbRows = emptyList(),
        )
        assertEquals(listOf("Apple", "MIDDLE", "zebra"), rows.map { it.albumName })
    }

    @Test
    fun `null album image survives through to the row`() {
        val rows = joinYshAlbumOwnership(
            catalog = catalog(
                track(skuId = 1, albumId = 1, albumTitle = "Coverless", title = "x", albumImageUrl = null),
            ),
            dbRows = emptyList(),
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

    private fun downloadedRow(
        skuId: Long,
        providerId: String = "ysh",
        albumName: String? = null,
    ) = baseRow(
        skuId = skuId,
        providerId = providerId,
        albumName = albumName,
        filePath = "/data/local/ysh-sku-$skuId.mp3",
    )

    private fun streamableRow(
        skuId: Long,
        providerId: String = "ysh",
        albumName: String? = null,
    ) = baseRow(
        skuId = skuId,
        providerId = providerId,
        albumName = albumName,
        filePath = null,
    )

    private fun baseRow(
        skuId: Long,
        providerId: String,
        albumName: String?,
        filePath: String?,
    ) = LocalEpisodeEntity(
        providerId = providerId,
        externalId = if (providerId == "ysh") "ysh-sku-$skuId" else skuId.toString(),
        title = "T$skuId",
        airDate = "2021-01-01",
        description = "stub",
        sourceUrl = "https://source/$skuId",
        downloadUrl = "https://zcast/$skuId.mp3",
        filePath = filePath,
        fileSize = if (filePath != null) 1024L else 0L,
        durationMs = 30 * 60_000L,
        downloadedAt = if (filePath != null) 1_700_000_000_000L else null,
        archivedAt = null,
        imageUrl = null,
        albumName = albumName,
        albumImageUrl = null,
        albumTrackOrder = null,
    )
}
