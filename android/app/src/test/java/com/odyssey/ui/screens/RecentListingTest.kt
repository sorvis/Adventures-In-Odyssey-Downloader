package com.odyssey.ui.screens

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
}
