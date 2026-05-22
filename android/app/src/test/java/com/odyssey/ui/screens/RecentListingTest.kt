package com.odyssey.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentListingTest {

    private data class Ep(val episodeId: Long, val title: String)

    /**
     * Mirrors the LocalEpisodeEntity fields recentItemsFor() reads, but
     * keeps this test Android-free so it runs in the JVM-only fast lane.
     */
    private data class RecentEp(
        val externalId: String,
        val airDate: String?,
        val providerId: String = "aio",
        val filePath: String? = null,
        val sourceUrl: String = "https://oneplace.com/$externalId",
    )

    /**
     * Adapter that wires RecentEp accessors into the generic helper —
     * keeps every test case below as a one-line `recentItemsFor(list, "aio")`.
     */
    private fun recentItemsFor(list: List<RecentEp>, activeShow: String): List<RecentEp> =
        recentItemsFor(
            eps = list,
            activeShow = activeShow,
            providerId = RecentEp::providerId,
            filePath = RecentEp::filePath,
            sourceUrl = RecentEp::sourceUrl,
            airDate = RecentEp::airDate,
            externalId = RecentEp::externalId,
        )

    // ---- dedupResume ---------------------------------------------------

    @Test
    fun `dedupResume returns input as-is when resumeId is null`() {
        val items = listOf(Ep(1, "a"), Ep(2, "b"))
        assertEquals(items, dedupResume(items, resumeId = null) { it.episodeId })
    }

    @Test
    fun `dedupResume removes the matching episode`() {
        val items = listOf(Ep(1, "a"), Ep(2, "b"), Ep(3, "c"))
        val result = dedupResume(items, resumeId = 2L) { it.episodeId }
        assertEquals(listOf(Ep(1, "a"), Ep(3, "c")), result)
    }

    @Test
    fun `dedupResume preserves order of remaining items`() {
        val items = listOf(Ep(5, "e"), Ep(4, "d"), Ep(3, "c"), Ep(2, "b"), Ep(1, "a"))
        val result = dedupResume(items, resumeId = 3L) { it.episodeId }
        assertEquals(listOf(Ep(5, "e"), Ep(4, "d"), Ep(2, "b"), Ep(1, "a")), result)
    }

    @Test
    fun `dedupResume returns input as-is when resumeId is not in list`() {
        val items = listOf(Ep(1, "a"), Ep(2, "b"))
        assertEquals(items, dedupResume(items, resumeId = 99L) { it.episodeId })
    }

    @Test
    fun `dedupResume on empty list returns empty list`() {
        val empty = emptyList<Ep>()
        assertEquals(empty, dedupResume(empty, resumeId = 1L) { it.episodeId })
    }

    // ---- parseAirDateMillis -------------------------------------------

    @Test
    fun `parseAirDateMillis parses the standard oneplace format`() {
        // "May 8, 2026" must parse to a positive epoch millis value.
        assertTrue(parseAirDateMillis("May 8, 2026") > 0L)
    }

    @Test
    fun `parseAirDateMillis returns 0 for null and blank`() {
        assertEquals(0L, parseAirDateMillis(null))
        assertEquals(0L, parseAirDateMillis(""))
        assertEquals(0L, parseAirDateMillis("   "))
    }

    @Test
    fun `parseAirDateMillis returns 0 for unparseable string`() {
        assertEquals(0L, parseAirDateMillis("not a date"))
        assertEquals(0L, parseAirDateMillis("2026-05-08"))      // ISO format — wrong shape
        assertEquals(0L, parseAirDateMillis("Mai 8, 2026"))     // German month name
    }

    @Test
    fun `parseAirDateMillis sorts cross-year boundary correctly`() {
        // The whole reason this helper exists: alphabetical "December 31, 2025"
        // sorts AFTER "January 1, 2026" (D > J... wait, D < J, so December
        // sorts FIRST alphabetically, giving DESC order December → January
        // which is BACKWARDS). Parse-then-sort fixes it.
        val dec31_2025 = parseAirDateMillis("December 31, 2025")
        val jan01_2026 = parseAirDateMillis("January 1, 2026")
        assertTrue(
            "January 1, 2026 must be later (larger millis) than December 31, 2025",
            jan01_2026 > dec31_2025,
        )
    }

    @Test
    fun `parseAirDateMillis sorts within month correctly`() {
        val may1 = parseAirDateMillis("May 1, 2026")
        val may8 = parseAirDateMillis("May 8, 2026")
        assertTrue("May 8 must be later than May 1", may8 > may1)
    }

    // ---- recentItemsFor (filter + sort) -------------------------------

    /**
     * Build a RecentEp with the fields the filter+sort cares about.
     * Other fields get cheap defaults so the test reads cleanly.
     */
    private fun ep(
        externalId: String,
        airDate: String?,
        providerId: String = "aio",
        filePath: String? = null,
        sourceUrl: String = "https://oneplace.com/$externalId",
    ) = RecentEp(
        externalId = externalId,
        airDate = airDate,
        providerId = providerId,
        filePath = filePath,
        sourceUrl = sourceUrl,
    )

    @Test
    fun `recentItemsFor sorts newest-first by airDate`() {
        val list = listOf(
            ep(externalId = "263", airDate = "May 6, 2026"),
            ep(externalId = "265", airDate = "May 8, 2026"),
            ep(externalId = "264", airDate = "May 7, 2026"),
        )
        val result = recentItemsFor(list, activeShow = "aio")
        assertEquals(listOf("265", "264", "263"), result.map { it.externalId })
    }

    @Test
    fun `recentItemsFor filters by activeShow — flipping to ysh hides aio rows`() {
        val list = listOf(
            ep(externalId = "265", airDate = "May 8, 2026", providerId = "aio"),
            ep(externalId = "ysh-sku-559", airDate = "May 1, 2026", providerId = "ysh"),
        )
        assertEquals(listOf("265"), recentItemsFor(list, "aio").map { it.externalId })
        assertEquals(listOf("ysh-sku-559"), recentItemsFor(list, "ysh").map { it.externalId })
    }

    @Test
    fun `recentItemsFor drops junk backup-mirror ghost rows with unparseable airDates`() {
        // The original screenshot bug (2026-05-13): BrowseNasScreen
        // .mirrorServerEpisodes() ingests carry year-only strings
        // ("2011") that fail to parse. Without filtering, those pile
        // up under #261 in Recent looking like noise. The filter still
        // drops THOSE — but only those — under the v0.1.68 relaxation.
        val list = listOf(
            ep(externalId = "265", airDate = "May 8, 2026"),
            ep(
                externalId = "010",
                airDate = "2011",                   // year-only, doesn't parse
                sourceUrl = "backup://010",
                filePath = null,
            ),
            ep(
                externalId = "140",
                airDate = null,                     // no date at all
                sourceUrl = "backup://140",
                filePath = null,
            ),
        )
        val result = recentItemsFor(list, activeShow = "aio")
        assertEquals(
            "year-only and null airDate ghosts must NOT appear in Recent",
            listOf("265"),
            result.map { it.externalId },
        )
    }

    @Test
    fun `recentItemsFor KEEPS retention-pruned ghosts with parseable airDates`() {
        // v0.1.68 fix to the v0.1.63 ghost-shape regression: after
        // RetentionWorker prunes a row, the original airDate from
        // oneplace is preserved on the ghost. The user wants to see
        // "what aired last Thursday" in Recent even though the local
        // copy is gone — tapping the row should stream from NAS.
        // (User report 2026-05-22: "everything is still really broken"
        // because all their AIO rows were ghosted and hidden.)
        val list = listOf(
            ep(externalId = "274", airDate = "May 20, 2026"),
            ep(
                externalId = "272",
                airDate = "May 18, 2026",           // real date, retention-preserved
                sourceUrl = "backup://272",
                filePath = null,                    // local copy gone
            ),
            ep(
                externalId = "271",
                airDate = "May 15, 2026",
                sourceUrl = "backup://271",
                filePath = null,
            ),
        )
        val result = recentItemsFor(list, activeShow = "aio")
        assertEquals(
            "pruned ghosts with real dates must show in Recent so the user can stream them",
            listOf("274", "272", "271"),
            result.map { it.externalId },
        )
    }

    @Test
    fun `recentItemsFor KEEPS NAS-mirror ghosts when server provides a real airDate`() {
        // v0.1.67 NasMirror copies whatever air_date the server has.
        // Catalog episodes from years past commonly have year-only
        // ("2011") which still drops, but newer mirror rows (server
        // populated from oneplace ingest) carry full dates — those
        // should show in Recent + be streamable.
        val list = listOf(
            ep(
                externalId = "657",
                airDate = "March 11, 2008",         // catalog has a real broadcast date
                sourceUrl = "backup://657",
                filePath = null,
            ),
        )
        val result = recentItemsFor(list, activeShow = "aio")
        assertEquals(listOf("657"), result.map { it.externalId })
    }

    @Test
    fun `recentItemsFor KEEPS backup rows once they've been downloaded to the phone`() {
        // A backup-mirror row with filePath set means the user tapped
        // "Restore from NAS" — the row is real on-phone audio now and
        // belongs in Recent so the user can play it from the home screen.
        val list = listOf(
            ep(
                externalId = "010",
                airDate = "May 1, 2011",                       // properly-formatted Restore-time airDate
                sourceUrl = "backup://010",
                filePath = "/data/.../010-nothing-to-fear.mp3", // on phone
            ),
            ep(externalId = "265", airDate = "May 8, 2026"),
        )
        val result = recentItemsFor(list, activeShow = "aio")
        // 265 is newer, sorts first; 010 is on disk, must remain visible.
        assertEquals(listOf("265", "010"), result.map { it.externalId })
    }

    @Test
    fun `recentItemsFor tiebreaks unparseable airDates by externalId DESC`() {
        // When the worker hasn't backfilled airDate yet — or oneplace
        // ships an off-format date for a newly-aired broadcast — rows
        // with unparseable airDate fall to parseAirDateMillis=0 and
        // tiebreak by externalId. For AIO numeric externalIds, that
        // string DESC happens to match numeric DESC up to width.
        val list = listOf(
            ep(externalId = "266", airDate = null),
            ep(externalId = "267", airDate = null),
            ep(externalId = "265", airDate = "May 8, 2026"),
        )
        val result = recentItemsFor(list, activeShow = "aio")
        // 265 sorts first because its airDate parses to a real (positive)
        // millis vs 0 for the others. The unparseable pair sorts among
        // themselves by externalId DESC. Captures TODAY's behavior —
        // separate follow-up will make new ingests always carry a
        // parseable airDate so this case doesn't happen in practice.
        assertEquals(listOf("265", "267", "266"), result.map { it.externalId })
    }
}
