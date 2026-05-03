package com.odyssey.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the play-action decision contract. The "Continue listening
 * resets to 0" bug was specifically the LoadFresh-when-NoOp case —
 * regression here will instantly fail this test class.
 */
class PlayActionTest {

    @Test
    fun `same episode and playing - NoOp (don't restart)`() {
        // Tapping Continue listening mid-play must not reset position.
        assertEquals(
            PlayAction.NoOp,
            decidePlayAction(currentMediaId = "1234", currentlyPlaying = true, targetMediaId = "1234"),
        )
    }

    @Test
    fun `same episode and paused - Resume`() {
        // Same episode loaded, paused — just unpause; don't reload.
        assertEquals(
            PlayAction.Resume,
            decidePlayAction(currentMediaId = "1234", currentlyPlaying = false, targetMediaId = "1234"),
        )
    }

    @Test
    fun `different episode - LoadFresh`() {
        assertEquals(
            PlayAction.LoadFresh,
            decidePlayAction(currentMediaId = "1234", currentlyPlaying = true, targetMediaId = "5678"),
        )
        assertEquals(
            PlayAction.LoadFresh,
            decidePlayAction(currentMediaId = "1234", currentlyPlaying = false, targetMediaId = "5678"),
        )
    }

    @Test
    fun `no current episode - LoadFresh`() {
        // First play in a session — controller has no item loaded.
        assertEquals(
            PlayAction.LoadFresh,
            decidePlayAction(currentMediaId = null, currentlyPlaying = false, targetMediaId = "1234"),
        )
    }

    @Test
    fun `same id but currentlyPlaying false - Resume not NoOp`() {
        // Edge: paused at 30s, tap Continue listening — should resume from 30s,
        // NOT load fresh and seek to last persisted position (which lags 5s).
        assertEquals(
            PlayAction.Resume,
            decidePlayAction(currentMediaId = "1278383", currentlyPlaying = false, targetMediaId = "1278383"),
        )
    }
}
