package com.odyssey.player

/**
 * "Interruption rewind" contract: when playback is paused because the
 * app lost audio focus (an incoming phone call is the canonical case)
 * and then resumes, we nudge the position back a few seconds so the
 * listener can re-orient in the story instead of picking up mid-word.
 *
 * ExoPlayer, once built with `setAudioAttributes(attrs, handleAudioFocus
 * = true)`, pauses itself on a transient focus loss and resumes on
 * regain. Neither of those built-ins rewinds — that's what this adds.
 *
 * Both pieces are plain JVM (no Media3 types) so the contract is unit
 * tested without a real player, same as [seekTargetMs] and the
 * PositionPersistence helpers.
 */

/**
 * How far to rewind on resume-after-interruption. 15s is enough to
 * re-establish "where was I" for spoken-word drama without replaying so
 * much that it feels like a skip-back.
 */
const val FOCUS_RESUME_REWIND_MS: Long = 15_000L

/**
 * Absolute position to seek to when resuming after a focus-loss pause:
 * [currentPositionMs] minus [rewindMs], clamped so we never seek before
 * the start of the track.
 */
fun rewindTargetMs(currentPositionMs: Long, rewindMs: Long = FOCUS_RESUME_REWIND_MS): Long =
    (currentPositionMs - rewindMs).coerceAtLeast(0L)

/**
 * Tiny state machine that remembers whether the *current* pause was
 * caused by audio-focus loss, so the next resume knows whether to
 * rewind.
 *
 * Feed it every playWhenReady change:
 *   - a pause (`playWhenReady == false`) records the cause;
 *   - a resume (`playWhenReady == true`) consumes the flag and returns
 *     true exactly once if the preceding pause was focus-caused.
 *
 * A user-initiated pause (dueToFocusLoss == false) clears the flag, so
 * manually pausing during a call and later hitting play won't rewind on
 * an unrelated resume. Not thread-safe: all Player.Listener callbacks
 * arrive on the player's application looper (the main thread), so the
 * single owning listener touches it serially.
 */
class FocusPauseTracker {
    private var pausedByFocusLoss = false

    /**
     * @param playWhenReady the new playWhenReady value from the listener
     * @param dueToFocusLoss whether *this* change was attributed to audio
     *   focus loss (reason == PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
     * @return true if the caller should rewind now (only ever on a resume
     *   that followed a focus-loss pause)
     */
    fun onPlayWhenReadyChanged(playWhenReady: Boolean, dueToFocusLoss: Boolean): Boolean {
        if (!playWhenReady) {
            pausedByFocusLoss = dueToFocusLoss
            return false
        }
        val shouldRewind = pausedByFocusLoss
        pausedByFocusLoss = false
        return shouldRewind
    }
}
