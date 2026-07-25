package com.odyssey.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewindTargetMsTest {

    @Test
    fun `rewinds by the default 15s`() {
        assertEquals(45_000L, rewindTargetMs(60_000L))
    }

    @Test
    fun `never seeks before the start`() {
        assertEquals(0L, rewindTargetMs(10_000L))
        assertEquals(0L, rewindTargetMs(0L))
    }

    @Test
    fun `exactly 15s in rewinds to 0`() {
        assertEquals(0L, rewindTargetMs(FOCUS_RESUME_REWIND_MS))
    }

    @Test
    fun `honors a custom rewind amount`() {
        assertEquals(25_000L, rewindTargetMs(30_000L, rewindMs = 5_000L))
    }
}

class FocusPauseTrackerTest {

    @Test
    fun `focus-loss pause then resume asks for a rewind`() {
        val t = FocusPauseTracker()
        assertFalse(t.onPlayWhenReadyChanged(playWhenReady = false, dueToFocusLoss = true))
        assertTrue(t.onPlayWhenReadyChanged(playWhenReady = true, dueToFocusLoss = false))
    }

    @Test
    fun `user pause then resume does not rewind`() {
        val t = FocusPauseTracker()
        assertFalse(t.onPlayWhenReadyChanged(playWhenReady = false, dueToFocusLoss = false))
        assertFalse(t.onPlayWhenReadyChanged(playWhenReady = true, dueToFocusLoss = false))
    }

    @Test
    fun `rewind fires only once per interruption`() {
        val t = FocusPauseTracker()
        t.onPlayWhenReadyChanged(playWhenReady = false, dueToFocusLoss = true)
        assertTrue(t.onPlayWhenReadyChanged(playWhenReady = true, dueToFocusLoss = false))
        // A second resume with no intervening focus-loss pause must not rewind.
        assertFalse(t.onPlayWhenReadyChanged(playWhenReady = true, dueToFocusLoss = false))
    }

    @Test
    fun `manual pause during a call clears a pending focus-loss rewind`() {
        val t = FocusPauseTracker()
        t.onPlayWhenReadyChanged(playWhenReady = false, dueToFocusLoss = true)
        // User taps pause themselves while the call is ongoing.
        t.onPlayWhenReadyChanged(playWhenReady = false, dueToFocusLoss = false)
        assertFalse(t.onPlayWhenReadyChanged(playWhenReady = true, dueToFocusLoss = false))
    }

    @Test
    fun `initial play does not rewind`() {
        val t = FocusPauseTracker()
        assertFalse(t.onPlayWhenReadyChanged(playWhenReady = true, dueToFocusLoss = false))
    }
}
