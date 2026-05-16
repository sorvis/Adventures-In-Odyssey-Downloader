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
