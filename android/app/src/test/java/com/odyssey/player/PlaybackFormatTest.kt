package com.odyssey.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFormatTest {

    @Test
    fun `formatPosition under one hour`() {
        assertEquals("0:00", formatPosition(0))
        assertEquals("0:09", formatPosition(9_000))
        assertEquals("0:59", formatPosition(59_999))
        assertEquals("1:00", formatPosition(60_000))
        assertEquals("12:34", formatPosition(12 * 60_000 + 34_000))
    }

    @Test
    fun `formatPosition past one hour switches to H_MM_SS`() {
        assertEquals("1:00:00", formatPosition(3_600_000))
        assertEquals("1:02:03", formatPosition(3_723_000))
    }

    @Test
    fun `formatPosition clamps negative values to zero`() {
        assertEquals("0:00", formatPosition(-1))
        assertEquals("0:00", formatPosition(Long.MIN_VALUE))
    }

    @Test
    fun `formatResumeSubtitle uses total when duration is known`() {
        assertEquals("0:30 / 25:00", formatResumeSubtitle(30_000, 25 * 60_000))
    }

    @Test
    fun `formatResumeSubtitle falls back to position-only when duration unknown`() {
        assertEquals("0:30 in", formatResumeSubtitle(30_000, 0))
        assertEquals("0:30 in", formatResumeSubtitle(30_000, -1))
    }

    // ---- shouldMarkComplete (drives the ✓ played chip) -------------------

    @Test
    fun `shouldMarkComplete is false at zero progress`() {
        assertFalse(shouldMarkComplete(positionMs = 0, durationMs = 60_000))
    }

    @Test
    fun `shouldMarkComplete is false halfway through`() {
        assertFalse(shouldMarkComplete(positionMs = 30_000, durationMs = 60_000))
    }

    @Test
    fun `shouldMarkComplete is false just below the 95% threshold`() {
        // 94.99% — below threshold by a hair.
        assertFalse(shouldMarkComplete(positionMs = 56_993, durationMs = 60_000))
    }

    @Test
    fun `shouldMarkComplete is true exactly at the 95% threshold`() {
        // 60_000 * 0.95 = 57_000 — equality must count as complete.
        assertTrue(shouldMarkComplete(positionMs = 57_000, durationMs = 60_000))
    }

    @Test
    fun `shouldMarkComplete is true past the end`() {
        assertTrue(shouldMarkComplete(positionMs = 60_000, durationMs = 60_000))
        assertTrue(shouldMarkComplete(positionMs = 99_999, durationMs = 60_000))
    }

    @Test
    fun `shouldMarkComplete is false when duration is unknown`() {
        // Media3 can report 0 before the first frame decodes — must not
        // mark every just-started episode complete.
        assertFalse(shouldMarkComplete(positionMs = 0, durationMs = 0))
        assertFalse(shouldMarkComplete(positionMs = 100_000, durationMs = 0))
        assertFalse(shouldMarkComplete(positionMs = 100_000, durationMs = -1))
    }

    // ---- formatRemaining ("52 min left" / "1hr 12min left") ----

    @Test
    fun `formatRemaining null when not started`() {
        assertEquals(null, formatRemaining(0L, 60_000L))
        assertEquals(null, formatRemaining(500L, 60_000L))
    }

    @Test
    fun `formatRemaining null when duration is unknown`() {
        assertEquals(null, formatRemaining(30_000L, 0L))
        assertEquals(null, formatRemaining(30_000L, -1L))
    }

    @Test
    fun `formatRemaining null when episode is essentially done`() {
        // Past 95% threshold → treated as completed → no chip.
        assertEquals(null, formatRemaining(95_000L, 100_000L))
        assertEquals(null, formatRemaining(99_999L, 100_000L))
    }

    @Test
    fun `formatRemaining null when under one minute remains`() {
        // 30s remaining is too granular for a row chip — caller falls
        // back to the default trailing label.
        assertEquals(null, formatRemaining(60_000L * 25 - 30_000L, 60_000L * 25))
    }

    @Test
    fun `formatRemaining shows minutes only for sub-hour episodes`() {
        // 25-min episode, 8 min in → ~17 min left.
        assertEquals("17 min left", formatRemaining(8L * 60_000L, 25L * 60_000L))
    }

    @Test
    fun `formatRemaining splits hours and minutes for long content`() {
        // 90-min audiobook chapter, 17 min in → 1hr 12min left (90 - 17 = 73 = 1*60 + 13... oh).
        // 90 - 17 = 73 = 1 hour 13 minutes (NOT 12). Adjusting test expectation.
        assertEquals("1hr 13min left", formatRemaining(17L * 60_000L, 90L * 60_000L))
    }

    @Test
    fun `shouldMarkComplete honors a custom threshold`() {
        // 50%-mark threshold for a hypothetical "halfway done" use case.
        assertFalse(shouldMarkComplete(positionMs = 29_999, durationMs = 60_000, threshold = 0.50))
        assertTrue(shouldMarkComplete(positionMs = 30_000, durationMs = 60_000, threshold = 0.50))
    }
}
