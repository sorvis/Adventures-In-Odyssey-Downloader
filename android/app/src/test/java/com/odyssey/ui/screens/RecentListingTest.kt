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

    // ---- recentlyPlayedFor --------------------------------------------

    private data class Pos(val episodeId: Long, val updatedAt: Long)

    private fun recentlyPlayed(
        eps: List<Ep>,
        positions: List<Pos>,
        exclude: Long? = null,
        max: Int = 5,
    ): List<Ep> = recentlyPlayedFor(
        episodes = eps,
        positions = positions,
        excludeEpisodeId = exclude,
        maxItems = max,
        episodeId = Ep::episodeId,
        positionEpisodeId = Pos::episodeId,
        updatedAt = Pos::updatedAt,
    )

    @Test
    fun `recentlyPlayedFor orders by updatedAt DESC across all episodes`() {
        // Positions arrive newest-first from the DAO query, but the
        // helper re-sorts defensively in case a caller passes them
        // ungrouped. Verify the strip would draw newest-touched-first
        // regardless of input order.
        val eps = listOf(Ep(1, "a"), Ep(2, "b"), Ep(3, "c"))
        val positions = listOf(
            Pos(episodeId = 2, updatedAt = 100L),
            Pos(episodeId = 3, updatedAt = 300L),
            Pos(episodeId = 1, updatedAt = 200L),
        )
        val result = recentlyPlayed(eps, positions)
        assertEquals(listOf(3L, 1L, 2L), result.map { it.episodeId })
    }

    @Test
    fun `recentlyPlayedFor excludes the Continue listening episode so the strip does not duplicate the card`() {
        // The most-recent row already lives in the Continue listening
        // ElevatedCard above the strip — if we showed it here too, the
        // user would see the same item twice on a single screen.
        val eps = listOf(Ep(10, "x"), Ep(20, "y"), Ep(30, "z"))
        val positions = listOf(
            Pos(episodeId = 10, updatedAt = 500L),  // newest → shown in Continue listening
            Pos(episodeId = 20, updatedAt = 400L),
            Pos(episodeId = 30, updatedAt = 300L),
        )
        val result = recentlyPlayed(eps, positions, exclude = 10L)
        assertEquals("excluded episode must not appear", listOf(20L, 30L), result.map { it.episodeId })
    }

    @Test
    fun `recentlyPlayedFor caps the result at maxItems`() {
        val eps = (1L..10L).map { Ep(it, "ep$it") }
        val positions = (1L..10L).map { Pos(episodeId = it, updatedAt = it * 100L) }
        val result = recentlyPlayed(eps, positions, max = 3)
        assertEquals(3, result.size)
        // Newest three (updatedAt 1000, 900, 800).
        assertEquals(listOf(10L, 9L, 8L), result.map { it.episodeId })
    }

    @Test
    fun `recentlyPlayedFor skips positions whose episode is no longer in the catalog`() {
        // A position can outlive its episode row (manual DB delete,
        // cross-show contamination cleanup, etc.). The helper must
        // tolerate that without throwing or returning a partially-formed
        // row — silently drop it.
        val eps = listOf(Ep(1, "a"), Ep(3, "c"))  // ep 2 was deleted
        val positions = listOf(
            Pos(episodeId = 1, updatedAt = 300L),
            Pos(episodeId = 2, updatedAt = 200L),   // orphan
            Pos(episodeId = 3, updatedAt = 100L),
        )
        val result = recentlyPlayed(eps, positions)
        assertEquals(listOf(1L, 3L), result.map { it.episodeId })
    }

    @Test
    fun `recentlyPlayedFor returns empty list when no positions exist (fresh install)`() {
        val eps = listOf(Ep(1, "a"), Ep(2, "b"))
        val result = recentlyPlayed(eps, positions = emptyList())
        assertTrue("no plays → empty strip → UI hides the section entirely", result.isEmpty())
    }

    @Test
    fun `recentlyPlayedFor returns empty list when maxItems is zero or negative`() {
        // Defensive — the UI caller passes a const but a future caller
        // might fence the section off behind a feature flag by setting
        // max=0 and expect a clean no-op.
        val eps = listOf(Ep(1, "a"))
        val positions = listOf(Pos(1, 100L))
        assertTrue(recentlyPlayed(eps, positions, max = 0).isEmpty())
        assertTrue(recentlyPlayed(eps, positions, max = -1).isEmpty())
    }

    @Test
    fun `recentlyPlayedFor deduplicates if the same episode appears twice in positions`() {
        // Shouldn't happen with the (providerId, externalId) primary key
        // on the table, but be defensive — if it ever does, the strip
        // should still show the episode once.
        val eps = listOf(Ep(1, "a"), Ep(2, "b"))
        val positions = listOf(
            Pos(episodeId = 1, updatedAt = 300L),
            Pos(episodeId = 1, updatedAt = 200L),  // hypothetical duplicate
            Pos(episodeId = 2, updatedAt = 100L),
        )
        val result = recentlyPlayed(eps, positions)
        assertEquals(listOf(1L, 2L), result.map { it.episodeId })
    }

    // ---- formatRelativePlayedAt ---------------------------------------

    @Test
    fun `formatRelativePlayedAt bucket -- just now under one minute`() {
        val now = 1_700_000_000_000L
        assertEquals("just now", formatRelativePlayedAt(updatedAtMs = now - 30_000L, nowMs = now))
        assertEquals("just now", formatRelativePlayedAt(updatedAtMs = now - 59_000L, nowMs = now))
    }

    @Test
    fun `formatRelativePlayedAt bucket -- minutes under one hour`() {
        val now = 1_700_000_000_000L
        assertEquals("1m ago", formatRelativePlayedAt(updatedAtMs = now - 60_000L, nowMs = now))
        assertEquals("45m ago", formatRelativePlayedAt(updatedAtMs = now - 45L * 60_000L, nowMs = now))
        assertEquals("59m ago", formatRelativePlayedAt(updatedAtMs = now - 59L * 60_000L, nowMs = now))
    }

    @Test
    fun `formatRelativePlayedAt bucket -- hours under one day`() {
        val now = 1_700_000_000_000L
        assertEquals("1h ago", formatRelativePlayedAt(updatedAtMs = now - 60L * 60_000L, nowMs = now))
        assertEquals("23h ago", formatRelativePlayedAt(updatedAtMs = now - 23L * 3_600_000L, nowMs = now))
    }

    @Test
    fun `formatRelativePlayedAt bucket -- days under one week`() {
        val now = 1_700_000_000_000L
        assertEquals("1d ago", formatRelativePlayedAt(updatedAtMs = now - 24L * 3_600_000L, nowMs = now))
        assertEquals("6d ago", formatRelativePlayedAt(updatedAtMs = now - 6L * 24L * 3_600_000L, nowMs = now))
    }

    @Test
    fun `formatRelativePlayedAt bucket -- absolute date beyond one week`() {
        // 8 days back from a known anchor — verify we switched to the
        // "MMM d" absolute format. Exact text depends on the anchor; just
        // assert it stopped being "Nd ago" and looks like a calendar date.
        val now = 1_700_000_000_000L  // 2023-11-14 ~22:13 UTC
        val eightDaysBack = now - 8L * 24L * 3_600_000L
        val result = formatRelativePlayedAt(eightDaysBack, now)
        assertTrue(
            "expected 'Nov 6' or 'Nov 7' depending on TZ, got '$result'",
            result.matches(Regex("^[A-Z][a-z]{2} \\d{1,2}$")),
        )
    }

    @Test
    fun `formatRelativePlayedAt returns empty string for future timestamps (clock skew)`() {
        // If the position's updatedAt is in the future relative to now
        // (clock skew, test-injected weirdness), don't render a
        // nonsensical "-3m ago"; render nothing and let the UI hide
        // the chip.
        val now = 1_700_000_000_000L
        assertEquals("", formatRelativePlayedAt(updatedAtMs = now + 60_000L, nowMs = now))
    }
}
