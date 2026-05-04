package com.odyssey.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekTargetTest {

    @Test
    fun `fraction 0 returns 0`() {
        assertEquals(0L, seekTargetMs(0f, 60_000L))
    }

    @Test
    fun `fraction 1 returns full duration`() {
        assertEquals(60_000L, seekTargetMs(1f, 60_000L))
    }

    @Test
    fun `fraction 0_5 returns half the duration`() {
        assertEquals(30_000L, seekTargetMs(0.5f, 60_000L))
    }

    @Test
    fun `fraction below 0 is clamped to 0`() {
        assertEquals(0L, seekTargetMs(-0.5f, 60_000L))
    }

    @Test
    fun `fraction above 1 is clamped to durationMs`() {
        assertEquals(60_000L, seekTargetMs(1.5f, 60_000L))
    }

    @Test
    fun `unknown duration returns 0`() {
        // ExoPlayer reports duration = -1 (UNSET) before media is loaded.
        // Don't seek to a wild value in that state.
        assertEquals(0L, seekTargetMs(0.5f, -1L))
        assertEquals(0L, seekTargetMs(0.5f, 0L))
    }

    @Test
    fun `long-episode math doesn't overflow`() {
        // 90-minute audiobook chapter — make sure Float→Long math
        // is precise enough at this scale.
        val ninetyMin = 90L * 60 * 1000  // 5_400_000
        assertEquals(2_700_000L, seekTargetMs(0.5f, ninetyMin))
    }
}
