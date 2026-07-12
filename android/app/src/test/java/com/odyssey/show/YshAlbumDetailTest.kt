package com.odyssey.show

import com.odyssey.data.local.LocalEpisodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for [joinYshAlbumDetail].
 *
 * Locks in:
 *   - YSH album detail shows EVERY catalog track for the album, not
 *     just the DB-ingested ones (this is the v0.1.55 bug fix — used
 *     to show only DB rows, so tapping a faded album went to an
 *     empty/near-empty list).
 *   - Per-track ownership reflects DB state: filePath != null →
 *     DOWNLOADED, DB row without file → STREAMABLE, no DB row at all
 *     → UNAVAILABLE.
 *   - Sort is by catalog orderIndex ASC so tracks land in album
 *     order — title secondary so ties don't shuffle on recompose.
 *   - DB rows for other albums in the same input list don't pollute
 *     the result (defensive against `observeYshAlbumTracks` returning
 *     extras).
 */
class YshAlbumDetailTest {

    @Test
    fun `empty catalog yields empty result regardless of DB rows`() {
        val rows = joinYshAlbumDetail(
            catalog = catalog(),
            albumName = "Any",
            dbRowsForAlbum = listOf(localRow(skuId = 1, filePath = "/x")),
        )
        assertEquals(emptyList<YshAlbumDetailRow>(), rows)
    }

    @Test
    fun `every catalog track for the album is returned, no DB rows -- all UNAVAILABLE`() {
        val rows = joinYshAlbumDetail(
            catalog = catalog(
                track(skuId = 100, albumTitle = "Adventures of Asia", title = "T1", orderIndex = 0),
                track(skuId = 101, albumTitle = "Adventures of Asia", title = "T2", orderIndex = 1),
                track(skuId = 102, albumTitle = "Adventures of Asia", title = "T3", orderIndex = 2),
                track(skuId = 200, albumTitle = "Bible Comes Alive", title = "B1", orderIndex = 0),
            ),
            albumName = "Adventures of Asia",
            dbRowsForAlbum = emptyList(),
        )
        assertEquals(3, rows.size)
        assertEquals(listOf("T1", "T2", "T3"), rows.map { it.title })
        for (r in rows) {
            assertEquals(YshTrackOwnership.UNAVAILABLE, r.ownership)
            assertNull("UNAVAILABLE rows carry no DB row", r.localRow)
        }
    }

    @Test
    fun `DB row with filePath marks the matching catalog track DOWNLOADED`() {
        val rows = joinYshAlbumDetail(
            catalog = catalog(
                track(skuId = 100, albumTitle = "A", title = "T1", orderIndex = 0),
                track(skuId = 101, albumTitle = "A", title = "T2", orderIndex = 1),
            ),
            albumName = "A",
            dbRowsForAlbum = listOf(
                localRow(skuId = 100, filePath = "/data/foo.mp3"),
            ),
        )
        val t1 = rows.single { it.skuId == 100L }
        val t2 = rows.single { it.skuId == 101L }
        assertEquals(YshTrackOwnership.DOWNLOADED, t1.ownership)
        assertEquals("/data/foo.mp3", t1.localRow?.filePath)
        assertEquals(YshTrackOwnership.UNAVAILABLE, t2.ownership)
    }

    @Test
    fun `DB row without filePath marks the matching catalog track STREAMABLE`() {
        val rows = joinYshAlbumDetail(
            catalog = catalog(
                track(skuId = 100, albumTitle = "A", title = "T1", orderIndex = 0),
            ),
            albumName = "A",
            dbRowsForAlbum = listOf(localRow(skuId = 100, filePath = null)),
        )
        val r = rows.single()
        assertEquals(YshTrackOwnership.STREAMABLE, r.ownership)
        assertEquals("https://zcast/100.mp3", r.localRow?.downloadUrl)
    }

    @Test
    fun `DB rows for OTHER albums in the input list are ignored`() {
        val rows = joinYshAlbumDetail(
            catalog = catalog(
                track(skuId = 100, albumTitle = "Target Album", title = "T1", orderIndex = 0),
            ),
            albumName = "Target Album",
            dbRowsForAlbum = listOf(
                // sku 999 isn't even in the catalog -- pretend caller
                // accidentally included it. Should not surface.
                localRow(skuId = 999, filePath = "/x"),
            ),
        )
        assertEquals(1, rows.size)
        assertEquals(YshTrackOwnership.UNAVAILABLE, rows.single().ownership)
    }

    @Test
    fun `result is sorted by catalog orderIndex ascending, title secondary`() {
        val rows = joinYshAlbumDetail(
            catalog = catalog(
                track(skuId = 1, albumTitle = "A", title = "Beta", orderIndex = 2),
                track(skuId = 2, albumTitle = "A", title = "Alpha", orderIndex = 0),
                track(skuId = 3, albumTitle = "A", title = "Gamma", orderIndex = 1),
                track(skuId = 4, albumTitle = "A", title = "Apple", orderIndex = 0),  // tie at 0
            ),
            albumName = "A",
            dbRowsForAlbum = emptyList(),
        )
        assertEquals(
            listOf("Alpha", "Apple", "Gamma", "Beta"),
            rows.map { it.title },
        )
    }

    @Test
    fun `albumImageUrl flows from the catalog -- not from any DB row`() {
        val rows = joinYshAlbumDetail(
            catalog = catalog(
                track(
                    skuId = 100, albumTitle = "A", title = "T1", orderIndex = 0,
                    albumImageUrl = "https://catalog/cover.jpg",
                ),
            ),
            albumName = "A",
            dbRowsForAlbum = listOf(
                localRow(skuId = 100, filePath = "/x", imageUrl = "https://db/different.jpg"),
            ),
        )
        assertEquals("https://catalog/cover.jpg", rows.single().albumImageUrl)
    }

    @Test
    fun `yshAlbumImageUrlForRow returns the catalog cover for a YSH row missing imageUrl`() {
        val catalog = catalog(
            track(skuId = 1958, albumTitle = "A", title = "T", orderIndex = 0,
                albumImageUrl = "https://catalog/cover.jpg"),
        )
        val row = localRow(skuId = 1958, imageUrl = null)
        assertEquals("https://catalog/cover.jpg", yshAlbumImageUrlForRow(row, catalog))
    }

    @Test
    fun `yshAlbumImageUrlForRow returns null when row is not YSH`() {
        val catalog = catalog(
            track(skuId = 1, albumTitle = "A", title = "T", orderIndex = 0),
        )
        val aioRow = localRow(skuId = 1).copy(providerId = "aio")
        assertNull(yshAlbumImageUrlForRow(aioRow, catalog))
    }

    @Test
    fun `yshAlbumImageUrlForRow returns null when catalog not yet loaded`() {
        assertNull(yshAlbumImageUrlForRow(localRow(skuId = 1), catalog = null))
    }

    @Test
    fun `yshAlbumImageUrlForRow returns null when skuId is not in the catalog`() {
        val catalog = catalog(
            track(skuId = 100, albumTitle = "A", title = "T", orderIndex = 0),
        )
        assertNull(yshAlbumImageUrlForRow(localRow(skuId = 999), catalog))
    }

    @Test
    fun `DB row whose externalId doesn't parse as ysh-sku-N is ignored defensively`() {
        val rows = joinYshAlbumDetail(
            catalog = catalog(
                track(skuId = 100, albumTitle = "A", title = "T1", orderIndex = 0),
            ),
            albumName = "A",
            dbRowsForAlbum = listOf(
                // malformed externalId, e.g. a row from a different provider
                // that somehow got included
                localRow(skuId = 100).copy(externalId = "not-a-ysh-sku"),
            ),
        )
        // Catalog row is still returned; DB row doesn't match so
        // ownership is UNAVAILABLE.
        assertEquals(YshTrackOwnership.UNAVAILABLE, rows.single().ownership)
    }

    @Test
    fun `downloaded row whose album matches but sku is not in the catalog still surfaces`() {
        // Robustness for the "some YSH have no album" fix: a row whose
        // stored albumName matches this album but whose skuId the deep
        // catalog dropped must not vanish from its own album — else
        // "Go to album" lands on a screen missing the very episode.
        val rows = joinYshAlbumDetail(
            catalog = catalog(
                track(skuId = 100, albumTitle = "A", title = "T1", orderIndex = 0),
            ),
            albumName = "A",
            dbRowsForAlbum = listOf(
                localRow(skuId = 100, filePath = "/data/t1.mp3"),      // in catalog
                localRow(skuId = 900, filePath = "/data/orphan.mp3"),  // stored album "A", not in catalog
            ),
        )
        assertEquals(2, rows.size)
        val orphan = rows.single { it.skuId == 900L }
        assertEquals(YshTrackOwnership.DOWNLOADED, orphan.ownership)
        assertEquals("/data/orphan.mp3", orphan.localRow?.filePath)
    }

    @Test
    fun `yshAlbumNameForRow prefers the album name stored on the row`() {
        // Even with a catalog present, the row's own albumName wins so
        // free-stream-only rows (absent from the deep catalog) resolve.
        val row = localRow(skuId = 900).copy(albumName = "Stored Album")
        assertEquals("Stored Album", yshAlbumNameForRow(row, catalog(track(skuId = 1, albumTitle = "X", title = "T", orderIndex = 0))))
    }

    @Test
    fun `yshAlbumNameForRow falls back to the catalog by skuId when albumName is null`() {
        val row = localRow(skuId = 100).copy(albumName = null)
        val cat = catalog(track(skuId = 100, albumTitle = "Catalog Album", title = "T", orderIndex = 0))
        assertEquals("Catalog Album", yshAlbumNameForRow(row, cat))
    }

    @Test
    fun `yshAlbumNameForRow returns null for AIO rows and for unresolvable YSH rows`() {
        assertNull(yshAlbumNameForRow(localRow(skuId = 1).copy(providerId = "aio", albumName = null), catalog()))
        // YSH row, no stored album, sku not in catalog, catalog null → null
        assertNull(yshAlbumNameForRow(localRow(skuId = 999).copy(albumName = null), catalog = null))
    }

    // ----- helpers --------------------------------------------------------

    private fun catalog(vararg tracks: YshCatalogTrack) = YshCatalogIndex(
        scrapedAtMs = 1_700_000_000_000L,
        tracks = tracks.toList(),
    )

    private fun track(
        skuId: Long,
        albumTitle: String,
        title: String,
        orderIndex: Int,
        albumImageUrl: String? = "https://catalog/$albumTitle.jpg",
    ) = YshCatalogTrack(
        skuId = skuId,
        title = title,
        albumId = 1L,
        albumTitle = albumTitle,
        albumSlug = albumTitle.lowercase().replace(" ", "-"),
        albumImageUrl = albumImageUrl,
        orderIndex = orderIndex,
    )

    private fun localRow(
        skuId: Long,
        filePath: String? = null,
        imageUrl: String? = null,
    ) = LocalEpisodeEntity(
        providerId = "ysh",
        externalId = "ysh-sku-$skuId",
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
        imageUrl = imageUrl,
        albumName = "A",
        albumImageUrl = null,
        albumTrackOrder = null,
    )
}
