package com.odyssey.player

/**
 * Pure helpers for the "save resume position per episode" contract.
 * Two specific bugs they fix:
 *
 *   1. Tapping a different episode while one was playing destroyed the
 *      target's saved position because the spurious onIsPlayingChanged
 *      that fires during setMediaItem transitions caused persist() to
 *      write `(target, 0)` into the playback table — wiping any prior
 *      resume position for that episode.
 *
 *   2. The OLD episode's last-played position was never captured at the
 *      moment of switching — only by the periodic 5s save loop, which
 *      could be up to 5s stale (or 0 if the user navigated away within
 *      the first save interval).
 *
 * Both are JVM-testable so we can pin the contract without holding a
 * real MediaController.
 */

/**
 * If a switch is happening (target differs from currently-loaded), and
 * the current position is meaningful (> [MIN_PERSIST_POSITION_MS]),
 * return a snapshot of the OLD episode's state so the caller can
 * persist it before swapping in the new MediaItem. Returns null if
 * there's nothing worth saving.
 */
fun capturePreviousIfDifferent(
    currentMediaId: String?,
    currentPositionMs: Long,
    currentDurationMs: Long,
    targetEpisodeId: Long,
): PositionSnapshot? {
    val oldId = currentMediaId?.toLongOrNull() ?: return null
    if (oldId == targetEpisodeId) return null
    if (currentPositionMs <= MIN_PERSIST_POSITION_MS) return null
    return PositionSnapshot(
        episodeId = oldId,
        positionMs = currentPositionMs,
        durationMs = currentDurationMs.coerceAtLeast(0),
    )
}

/**
 * True when the current position is far enough into an episode to be
 * worth persisting. Filters out the transient pos=0 state during
 * setMediaItem/prepare, which would otherwise overwrite real saved
 * positions with zero.
 */
fun shouldPersist(positionMs: Long): Boolean = positionMs > MIN_PERSIST_POSITION_MS

data class PositionSnapshot(
    val episodeId: Long,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * Threshold below which a position read is treated as a transient
 * artifact of the transition machinery rather than a real listening
 * position. 1 second — anything below that is almost certainly the
 * post-prepare "pos=0" or post-seek "pos=savedPos but didn't play yet".
 */
const val MIN_PERSIST_POSITION_MS: Long = 1_000L

/**
 * Where playback should start when a user taps an episode. PlayerController
 * passes this to [androidx.media3.common.Player.setMediaItem] (the
 * `mediaItem, startPositionMs` overload) so the load + seek happen
 * atomically — eliminating the race where the player previously briefly
 * played from 0 before our seekTo arrived.
 *
 * Pure so the resume contract can be JVM-tested without a MediaController.
 *
 * Returns 0 when there's no saved position OR the saved position is below
 * MIN_PERSIST_POSITION_MS (transient artifacts that should not be honored
 * as a resume point).
 */
fun resumeStartPositionMs(savedPositionMs: Long?): Long {
    val pos = savedPositionMs ?: return 0L
    return if (pos > MIN_PERSIST_POSITION_MS) pos else 0L
}

/**
 * The position to actually save for an episode at [positionMs] of
 * [durationMs].
 *
 * A completed episode saves **0**, not its end position. Storing the
 * end position made "resume" meaningless: tapping a finished episode
 * seeked to ~8 ms before the end, played that, and stopped (observed
 * 2026-09-28 with episode 354 — READY then ENDED 8 ms later). It got
 * worse once auto-advance landed, because that non-play now
 * immediately skips to the next track.
 *
 * Clearing the position rather than special-casing playback keeps the
 * stored data honest: "finished" means "starts from the beginning".
 * [PlaybackPositionEntity.completedAt] still records that it was
 * finished, and the "✓ played" chips read that field, not the
 * position — so nothing visible is lost.
 */
fun positionToPersist(positionMs: Long, durationMs: Long): Long =
    if (shouldMarkComplete(positionMs, durationMs)) 0L else positionMs
