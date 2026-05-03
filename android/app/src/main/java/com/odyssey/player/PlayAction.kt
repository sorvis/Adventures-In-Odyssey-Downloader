package com.odyssey.player

/**
 * What the controller should do when the user taps Play on an episode.
 * Pure decision based on whether the controller is already playing the
 * same episode — extracted from PlayerController so it's JVM-testable.
 *
 * Background: tapping "Continue listening" while the same episode was
 * already playing called setMediaItem → prepare → seekTo(savedPosition).
 * Saved position lags by up to 5s (save loop interval), so a mid-play
 * tap rewound to a stale position. Fix is to no-op when the controller
 * is already playing the same item.
 */
sealed interface PlayAction {
    /** Currently playing the requested episode — leave it alone. */
    object NoOp : PlayAction
    /** Same episode loaded but paused — flip playWhenReady to true. */
    object Resume : PlayAction
    /** Different (or no) episode — load+prepare+seek+play fresh. */
    object LoadFresh : PlayAction
}

fun decidePlayAction(
    currentMediaId: String?,
    currentlyPlaying: Boolean,
    targetMediaId: String,
): PlayAction = when {
    currentMediaId == targetMediaId && currentlyPlaying -> PlayAction.NoOp
    currentMediaId == targetMediaId && !currentlyPlaying -> PlayAction.Resume
    else -> PlayAction.LoadFresh
}
