package com.odyssey.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumOwnershipTest {

    private fun ep(name: String, short: String = "") = AioCatalogEpisode(name = name, shortName = short)

    private fun album(num: String?, vararg eps: AioCatalogEpisode) =
        AioAlbum(albumNumber = num, name = "#$num: Test", episodes = eps.toList())

    private val sampleCatalog = AioCatalog(
        scrapedAtMs = 0L, albumCount = 3,
        albums = listOf(
            album(
                "51",
                ep("Clutter", "#657: Clutter"),
                ep("War of the Words", "#658: War of the Words"),
                ep("Naturally, I Assumed", "#659: Naturally, I Assumed"),
            ),
            album(
                "81",
                ep("Never a Dull Moment", "#900: Never a Dull Moment"),
            ),
            album(
                "OHC",
                ep("Bonus Track", ""),
            ),
        ),
    )

    @Test
    fun `compareAlbumNumber sorts numeric desc with non-numeric at the bottom`() {
        val nums = listOf("OHC", "78.5", "81", null, "0", "FP", "1").sortedWith { a, b ->
            compareAlbumNumber(a, b)
        }
        // Numeric desc: 81, 78.5, 1, 0; then alphabetical FP, OHC; null last.
        assertEquals(listOf("81", "78.5", "1", "0", "FP", "OHC", null), nums)
    }

    @Test
    fun `numeric beats non-numeric regardless of value`() {
        // Even "0" sorts before "AAA" because numeric beats non-numeric.
        assertEquals(-1, compareAlbumNumber("0", "AAA"))
        assertEquals(1, compareAlbumNumber("AAA", "0"))
    }

    @Test
    fun `joinAlbumOwnership marks downloaded streamable and unavailable correctly`() {
        // Local DB:
        //   Clutter           — has filePath → DOWNLOADED
        //   War of the Words  — no filePath  → STREAMABLE
        //   Naturally I Assumed — not in DB  → UNAVAILABLE
        val locals = listOf(
            LocalEpisodeKey("Clutter", hasFile = true),
            LocalEpisodeKey("War of the Words", hasFile = false),
        )

        val joined = joinAlbumOwnership(sampleCatalog, locals)
        // Sort puts album 81 first, then 51, then OHC at the bottom.
        assertEquals(listOf("81", "51", "OHC"), joined.map { it.album.albumNumber })

        val a51 = joined.first { it.album.albumNumber == "51" }
        assertEquals(3, a51.totalCount)
        assertEquals(1, a51.downloadedCount)
        assertEquals(1, a51.streamableCount)
        assertEquals(EpisodeOwnership.DOWNLOADED, a51.episodes[0].ownership)
        assertEquals(EpisodeOwnership.STREAMABLE, a51.episodes[1].ownership)
        assertEquals(EpisodeOwnership.UNAVAILABLE, a51.episodes[2].ownership)
    }

    @Test
    fun `match falls back to short_name with number prefix stripped`() {
        // Catalog episode with NAME blank but short_name "#42: Foo";
        // local titled simply "Foo" should still match.
        val cat = AioCatalog(0, 1, listOf(album("42", ep("", "#42: Foo"))))
        val joined = joinAlbumOwnership(cat, listOf(LocalEpisodeKey("Foo", hasFile = true)))
        assertEquals(EpisodeOwnership.DOWNLOADED, joined[0].episodes[0].ownership)
    }

    @Test
    fun `empty local list yields all-UNAVAILABLE counts`() {
        val joined = joinAlbumOwnership(sampleCatalog, emptyList())
        for (a in joined) {
            assertEquals(0, a.downloadedCount)
            assertEquals(0, a.streamableCount)
            for (e in a.episodes) assertEquals(EpisodeOwnership.UNAVAILABLE, e.ownership)
        }
    }

    @Test
    fun `empty catalog yields empty result regardless of locals`() {
        val empty = AioCatalog(0, 0, emptyList())
        assertEquals(0, joinAlbumOwnership(empty, listOf(LocalEpisodeKey("X", true))).size)
    }

    // ---- ownershipSummary ----

    @Test
    fun `ownershipSummary always shows downloaded count even at zero`() {
        // Album with 3 catalog eps, none owned locally.
        val joined = joinAlbumOwnership(sampleCatalog, emptyList())
        val a51 = joined.first { it.album.albumNumber == "51" }
        assertEquals("0 of 3 downloaded", ownershipSummary(a51))
    }

    @Test
    fun `ownershipSummary appends streamable suffix only when non-zero`() {
        val locals = listOf(
            LocalEpisodeKey("Clutter", hasFile = true),
            LocalEpisodeKey("War of the Words", hasFile = false),
        )
        val a51 = joinAlbumOwnership(sampleCatalog, locals).first { it.album.albumNumber == "51" }
        // 1 of 3 downloaded, 1 streamable.
        assertEquals("1 of 3 downloaded • 1 streamable", ownershipSummary(a51))
    }

    @Test
    fun `ownershipSummary omits streamable when fully downloaded`() {
        val locals = listOf(
            LocalEpisodeKey("Clutter", hasFile = true),
            LocalEpisodeKey("War of the Words", hasFile = true),
            LocalEpisodeKey("Naturally, I Assumed", hasFile = true),
        )
        val a51 = joinAlbumOwnership(sampleCatalog, locals).first { it.album.albumNumber == "51" }
        assertEquals("3 of 3 downloaded", ownershipSummary(a51))
    }
}
