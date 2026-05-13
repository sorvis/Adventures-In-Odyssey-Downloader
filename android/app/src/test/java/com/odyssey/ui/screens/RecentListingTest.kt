package com.odyssey.ui.screens

import com.odyssey.data.local.LocalEpisodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentListingTest {

    private data class Ep(val episodeId: Long, val title: String)

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
     * Build a LocalEpisodeEntity with the fields the filter+sort cares
     * about. Other fields get cheap defaults so the test reads cleanly.
     */
    private fun ep(
        externalId: String,
        airDate: String?,
        providerId: String = "aio",
        filePath: String? = null,
        sourceUrl: String = "https://oneplace.com/$externalId",
        title: String = "ep-$externalId",
    ) = LocalEpisodeEntity(
        providerId = providerId,
        externalId = externalId,
        title = title,
        airDate = airDate,
        description = null,
        sourceUrl = sourceUrl,
        downloadUrl = sourceUrl,
        filePath = filePath,
        fileSize = 0L,
        durationMs = 0L,
        downloadedAt = null,
        archivedAt = null,
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
    fun `recentItemsFor drops backup-mirror ghost rows`() {
        // The screenshot bug (2026-05-13): BrowseNasScreen
        // .mirrorServerEpisodes() inserts old episodes purely to power
        // the Albums "☁ on backup" badge. They carry
        // sourceUrl="backup://<id>" + filePath=null and a year-only
        // airDate ("2011") that fails to parse — without the filter
        // they pile up under #261 in the Recent list as junk.
        val list = listOf(
            ep(externalId = "265", airDate = "May 8, 2026"),
            ep(
                externalId = "010",
                airDate = "2011",
                sourceUrl = "backup://010",
                filePath = null,
            ),
            ep(
                externalId = "140",
                airDate = "2011",
                sourceUrl = "backup://140",
                filePath = null,
            ),
        )
        val result = recentItemsFor(list, activeShow = "aio")
        assertEquals(
            "backup-mirror rows with no on-phone file must NOT appear in Recent",
            listOf("265"),
            result.map { it.externalId },
        )
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
