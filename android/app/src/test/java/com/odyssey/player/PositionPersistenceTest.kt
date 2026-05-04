package com.odyssey.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the per-episode resume contract. The two specific scenarios
 * the user reported:
 *
 *   (a) "Play episode A to 45s, switch to B, come back to A — resume
 *       button takes A back to 0." → covered by `captures previous on
 *       switch` and `does not capture target == current`.
 *
 *   (b) "Position memory should be per episode." → covered by every
 *       case below; the snapshot/threshold helpers always key on
 *       episodeId, never on a global state.
 */
class PositionPersistenceTest {

    // ----- capturePreviousIfDifferent ---------------------------------

    @Test
    fun `captures snapshot when switching from a different active episode`() {
        // Scenario (a): currently playing 1278383 at 45s; user taps 1278377.
        // We must capture (1278383, 45_000) BEFORE swapping the media item.
        val snap = capturePreviousIfDifferent(
            currentMediaId = "1278383",
            currentPositionMs = 45_000L,
            currentDurationMs = 1_530_000L,
            targetEpisodeId = 1278377L,
        )
        assertEquals(
            PositionSnapshot(episodeId = 1278383L, positionMs = 45_000L, durationMs = 1_530_000L),
            snap,
        )
    }

    @Test
    fun `does not capture when target equals current episode (no switch happening)`() {
        // Tapping Continue listening for the same playing episode → NoOp;
        // capturing+persisting would be redundant work.
        assertNull(
            capturePreviousIfDifferent(
                currentMediaId = "1278383",
                currentPositionMs = 45_000L,
                currentDurationMs = 1_530_000L,
                targetEpisodeId = 1278383L,
            )
        )
    }

    @Test
    fun `does not capture when nothing is loaded yet`() {
        assertNull(
            capturePreviousIfDifferent(
                currentMediaId = null,
                currentPositionMs = 0L,
                currentDurationMs = 0L,
                targetEpisodeId = 1278383L,
            )
        )
    }

    @Test
    fun `does not capture when current position is below the meaningful threshold`() {
        // Right after setMediaItem+prepare, pos can be 0 or near-zero
        // for a tick. Capturing that and persisting (oldId, 0) would
        // wipe the real saved position. Reject the capture.
        assertNull(
            capturePreviousIfDifferent(
                currentMediaId = "1278383",
                currentPositionMs = 0L,
                currentDurationMs = 1_530_000L,
                targetEpisodeId = 1278377L,
            )
        )
        assertNull(
            capturePreviousIfDifferent(
                currentMediaId = "1278383",
                currentPositionMs = MIN_PERSIST_POSITION_MS,    // exactly at threshold → not enough
                currentDurationMs = 1_530_000L,
                targetEpisodeId = 1278377L,
            )
        )
    }

    @Test
    fun `non-numeric mediaId yields no snapshot`() {
        // Defensive: mediaIds in our codebase are always episodeId.toString(),
        // but a future caller setting a custom mediaId shouldn't crash us.
        assertNull(
            capturePreviousIfDifferent(
                currentMediaId = "not-a-number",
                currentPositionMs = 45_000L,
                currentDurationMs = 1_530_000L,
                targetEpisodeId = 1278377L,
            )
        )
    }

    @Test
    fun `negative duration is clamped to zero in the snapshot`() {
        // Media3 returns -1 for duration when it's still computing.
        val snap = capturePreviousIfDifferent(
            currentMediaId = "1278383",
            currentPositionMs = 45_000L,
            currentDurationMs = -1L,
            targetEpisodeId = 1278377L,
        )
        assertEquals(0L, snap?.durationMs)
    }

    // ----- shouldPersist ---------------------------------------------

    @Test
    fun `shouldPersist rejects pos = 0 (transition artifact)`() {
        assertFalse(shouldPersist(0L))
    }

    @Test
    fun `shouldPersist rejects exactly at threshold`() {
        // Threshold is "exclusive of below" — exactly threshold = no.
        // Otherwise the threshold value itself would be ambiguous.
        assertFalse(shouldPersist(MIN_PERSIST_POSITION_MS))
    }

    @Test
    fun `shouldPersist accepts real listening positions`() {
        assertTrue(shouldPersist(MIN_PERSIST_POSITION_MS + 1))
        assertTrue(shouldPersist(45_000L))   // 45 seconds — the user's example
        assertTrue(shouldPersist(1_530_000L)) // 25 minutes — full episode
    }

    @Test
    fun `shouldPersist rejects negative input defensively`() {
        assertFalse(shouldPersist(-1L))
    }

    // ----- resumeStartPositionMs -------------------------------------
    //
    // This is the helper PlayerController feeds into setMediaItem(item,
    // startPositionMs). The user-reported "resume doesn't work after
    // relaunch / Continue listening" bug lived in that pipeline — these
    // tests pin the contract.

    @Test
    fun `resumeStartPositionMs is 0 when nothing was ever saved`() {
        // Fresh-install or brand-new episode: no row in playback table.
        assertEquals(0L, resumeStartPositionMs(null))
    }

    @Test
    fun `resumeStartPositionMs returns saved offset for a real listening position`() {
        // The realistic scenario from the user report: closed app at
        // 10:00 of a 25:00 episode, reopen, tap → resume.
        val tenMin = 10 * 60_000L
        assertEquals(tenMin, resumeStartPositionMs(tenMin))
        // And a couple representative offsets to make sure we're not
        // accidentally clamping or rounding.
        assertEquals(45_000L, resumeStartPositionMs(45_000L))
        assertEquals(1_530_000L, resumeStartPositionMs(1_530_000L))
    }

    @Test
    fun `resumeStartPositionMs ignores transient sub-threshold artifacts`() {
        // The same threshold logic as shouldPersist — a saved offset
        // below MIN_PERSIST_POSITION_MS came from a setMediaItem
        // transient and should not be honored as a resume target.
        assertEquals(0L, resumeStartPositionMs(0L))
        assertEquals(0L, resumeStartPositionMs(MIN_PERSIST_POSITION_MS))
        assertEquals(0L, resumeStartPositionMs(500L))
        // Just above threshold: honored.
        assertNotEquals(0L, resumeStartPositionMs(MIN_PERSIST_POSITION_MS + 1))
    }
}
