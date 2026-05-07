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

    // ---- sortAlbums --------------------------------------------------

    @Test
    fun `sortAlbums Default matches the joined order`() {
        // joinAlbumOwnership already applies albumOrder; Default mode
        // must be a no-op so users who never touch the menu see
        // unchanged behavior.
        val joined = joinAlbumOwnership(sampleCatalog, emptyList())
        assertEquals(joined, sortAlbums(joined, AlbumSort.Default))
    }

    @Test
    fun `sortAlbums Chronological lists oldest first numerically`() {
        // Default sort returns 81, 51, OHC. Chronological is 51, 81, OHC
        // (numeric asc; non-numeric still at the bottom).
        val joined = joinAlbumOwnership(sampleCatalog, emptyList())
        val chrono = sortAlbums(joined, AlbumSort.Chronological)
        assertEquals(listOf("51", "81", "OHC"), chrono.map { it.album.albumNumber })
    }

    @Test
    fun `sortAlbums MostDownloaded floats high-fraction albums to the top`() {
        // Album 51 has 3 episodes, 2 downloaded → 66%.
        // Album 81 has 1 episode,  1 downloaded → 100%.
        // Album OHC has 1 episode,  0 downloaded → 0%.
        // Expected order: 81 (100%), 51 (66%), OHC (0%).
        val locals = listOf(
            LocalEpisodeKey("Clutter", hasFile = true),
            LocalEpisodeKey("War of the Words", hasFile = true),
            LocalEpisodeKey("Never a Dull Moment", hasFile = true),
        )
        val joined = joinAlbumOwnership(sampleCatalog, locals)
        val byPct = sortAlbums(joined, AlbumSort.MostDownloaded)
        assertEquals(listOf("81", "51", "OHC"), byPct.map { it.album.albumNumber })
    }

    @Test
    fun `sortAlbums MostDownloaded breaks ties stably with albumOrder`() {
        // All three albums at 0% — secondary sort by albumOrder must
        // give a deterministic ordering (numeric desc, non-numeric at
        // bottom) so the list doesn't shuffle on recompose.
        val joined = joinAlbumOwnership(sampleCatalog, emptyList())
        val byPct = sortAlbums(joined, AlbumSort.MostDownloaded)
        assertEquals(listOf("81", "51", "OHC"), byPct.map { it.album.albumNumber })
    }

    // ---- backedUp flag ----------------------------------------------

    @Test
    fun `backedUp surfaces from local key into the joined episode`() {
        // Two locals: Clutter is downloaded AND backed up; War of the
        // Words is on phone but not backed up.
        val locals = listOf(
            LocalEpisodeKey("Clutter", hasFile = true, backedUp = true),
            LocalEpisodeKey("War of the Words", hasFile = true, backedUp = false),
        )
        val a51 = joinAlbumOwnership(sampleCatalog, locals).first { it.album.albumNumber == "51" }
        val byTitle = a51.episodes.associateBy { it.catalogEp.name }

        assertEquals(true, byTitle["Clutter"]?.backedUp)
        assertEquals(EpisodeOwnership.DOWNLOADED, byTitle["Clutter"]?.ownership)

        assertEquals(false, byTitle["War of the Words"]?.backedUp)
        assertEquals(EpisodeOwnership.DOWNLOADED, byTitle["War of the Words"]?.ownership)
    }

    @Test
    fun `backedUp survives when local file is deleted (STREAMABLE plus backed up)`() {
        // Realistic scenario: user downloads, app uploads, user deletes
        // local file. archivedAt stays set on the row → backedUp=true,
        // hasFile=false → STREAMABLE-and-backedUp combo.
        val locals = listOf(LocalEpisodeKey("Clutter", hasFile = false, backedUp = true))
        val a51 = joinAlbumOwnership(sampleCatalog, locals).first { it.album.albumNumber == "51" }
        val clutter = a51.episodes.first { it.catalogEp.name == "Clutter" }
        assertEquals(EpisodeOwnership.STREAMABLE, clutter.ownership)
        assertEquals(true, clutter.backedUp)
    }

    @Test
    fun `default backedUp is false`() {
        val locals = listOf(LocalEpisodeKey("Clutter", hasFile = true))
        val a51 = joinAlbumOwnership(sampleCatalog, locals).first { it.album.albumNumber == "51" }
        assertEquals(false, a51.episodes.first { it.catalogEp.name == "Clutter" }.backedUp)
    }

    // ---- backedUpCount + ownershipSummary backup line ---------------

    @Test
    fun `backedUpCount counts only episodes with backedUp true`() {
        val locals = listOf(
            LocalEpisodeKey("Clutter", hasFile = true, backedUp = true),
            LocalEpisodeKey("War of the Words", hasFile = true, backedUp = false),
            LocalEpisodeKey("Naturally, I Assumed", hasFile = false, backedUp = true),
        )
        val a51 = joinAlbumOwnership(sampleCatalog, locals).first { it.album.albumNumber == "51" }
        assertEquals(2, a51.backedUpCount)
    }

    @Test
    fun `ownershipSummary shows backup count between downloaded and streamable`() {
        // Scenario: user has 2 episodes downloaded (1 also on backup),
        // 1 streamable-only on the server. Summary line should read
        // "2 of 3 downloaded • 1 on backup • 1 streamable".
        val locals = listOf(
            LocalEpisodeKey("Clutter", hasFile = true, backedUp = true),
            LocalEpisodeKey("War of the Words", hasFile = true, backedUp = false),
            LocalEpisodeKey("Naturally, I Assumed", hasFile = false, backedUp = false),
        )
        val a51 = joinAlbumOwnership(sampleCatalog, locals).first { it.album.albumNumber == "51" }
        assertEquals("2 of 3 downloaded • 1 on backup • 1 streamable", ownershipSummary(a51))
    }

    @Test
    fun `ownershipSummary omits backup line when none are backed up`() {
        val locals = listOf(LocalEpisodeKey("Clutter", hasFile = true))
        val a51 = joinAlbumOwnership(sampleCatalog, locals).first { it.album.albumNumber == "51" }
        assertEquals("1 of 3 downloaded", ownershipSummary(a51))
    }

    // ---- filterAlbums -----------------------------------------------

    @Test
    fun `filterAlbums All is identity`() {
        val joined = joinAlbumOwnership(sampleCatalog, emptyList())
        assertEquals(joined, filterAlbums(joined, AlbumFilter.All))
    }

    @Test
    fun `filterAlbums HasOnPhone keeps only albums with downloadedCount over zero`() {
        // Only Clutter is on phone; album 81 + OHC have nothing local.
        val locals = listOf(LocalEpisodeKey("Clutter", hasFile = true))
        val joined = joinAlbumOwnership(sampleCatalog, locals)
        val out = filterAlbums(joined, AlbumFilter.HasOnPhone)
        assertEquals(listOf("51"), out.map { it.album.albumNumber })
    }

    @Test
    fun `filterAlbums HasOnBackup keeps only albums with at least one backedUp episode`() {
        // "Never a Dull Moment" backed up but not on phone — should
        // still surface album 81 under the HasOnBackup filter.
        val locals = listOf(
            LocalEpisodeKey("Never a Dull Moment", hasFile = false, backedUp = true),
        )
        val joined = joinAlbumOwnership(sampleCatalog, locals)
        val out = filterAlbums(joined, AlbumFilter.HasOnBackup)
        assertEquals(listOf("81"), out.map { it.album.albumNumber })
    }

    // ---- duplicate-by-title row merge -------------------------------

    @Test
    fun `joinAlbumOwnership ORs ownership flags when two rows share a title`() {
        // Realistic scenario: phone has Clutter from oneplace
        // (filePath set, archivedAt null) AND a server-mirror row
        // upserted by BrowseVm (filePath null, archivedAt set). The
        // catalog match must show BOTH "on phone" AND "on backup"
        // for that single catalog episode — not whichever happened
        // to bucket first.
        val locals = listOf(
            LocalEpisodeKey("Clutter", hasFile = true, backedUp = false),
            LocalEpisodeKey("Clutter", hasFile = false, backedUp = true),
        )
        val a51 = joinAlbumOwnership(sampleCatalog, locals).first { it.album.albumNumber == "51" }
        val clutter = a51.episodes.first { it.catalogEp.name == "Clutter" }
        assertEquals(EpisodeOwnership.DOWNLOADED, clutter.ownership)
        assertEquals(true, clutter.backedUp)
    }

    @Test
    fun `duplicate-row merge prefers the row with a file for the localEpisode raw payload`() {
        // The play action reads the localEpisode raw — must point at
        // the row that actually has a file, not the title-only mirror.
        val withFile = LocalEpisodeKey("Clutter", hasFile = true, backedUp = false, raw = "FILE")
        val mirror = LocalEpisodeKey("Clutter", hasFile = false, backedUp = true, raw = "MIRROR")

        // file-first
        val out1 = joinAlbumOwnership(sampleCatalog, listOf(withFile, mirror))
            .first { it.album.albumNumber == "51" }.episodes.first { it.catalogEp.name == "Clutter" }
        assertEquals("FILE", out1.localEpisode)

        // mirror-first (test the merge prefers the file row regardless of order)
        val out2 = joinAlbumOwnership(sampleCatalog, listOf(mirror, withFile))
            .first { it.album.albumNumber == "51" }.episodes.first { it.catalogEp.name == "Clutter" }
        assertEquals("FILE", out2.localEpisode)
    }
}
