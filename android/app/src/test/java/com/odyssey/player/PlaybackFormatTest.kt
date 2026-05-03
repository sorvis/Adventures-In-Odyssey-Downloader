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

    @Test
    fun `shouldMarkComplete honors a custom threshold`() {
        // 50%-mark threshold for a hypothetical "halfway done" use case.
        assertFalse(shouldMarkComplete(positionMs = 29_999, durationMs = 60_000, threshold = 0.50))
        assertTrue(shouldMarkComplete(positionMs = 30_000, durationMs = 60_000, threshold = 0.50))
    }
}
