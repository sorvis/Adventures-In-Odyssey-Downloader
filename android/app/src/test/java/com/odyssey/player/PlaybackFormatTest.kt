package com.odyssey.player

import org.junit.Assert.assertEquals
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
}
