package com.odyssey.player

/**
 * Pure formatting helpers for playback UI — JVM-testable so the strings
 * shown for "X minutes in" stay locked down without a Compose harness.
 */

/** Format a millisecond position as "M:SS" (or "H:MM:SS" past 1h). */
fun formatPosition(positionMs: Long): String {
    val totalSec = (positionMs.coerceAtLeast(0)) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * "Continue listening" subtitle. Shows position + total when total is
 * known; falls back to position-only otherwise (Media3 sometimes hasn't
 * computed duration before the row first renders).
 */
fun formatResumeSubtitle(positionMs: Long, durationMs: Long): String {
    val pos = formatPosition(positionMs)
    return if (durationMs > 0) "$pos / ${formatPosition(durationMs)}" else "$pos in"
}

/**
 * True when an episode has been listened far enough to count as "played"
 * (drives the ✓ played chip on the Recent list).
 *
 * Returns false when duration isn't known yet — Media3 sometimes reports
 * 0 or a negative sentinel before the first frame decodes, and we don't
 * want to mark every just-started episode complete just because position
 * happens to be ≥ 0 * threshold.
 */
fun shouldMarkComplete(
    positionMs: Long,
    durationMs: Long,
    threshold: Double = 0.95,
): Boolean = durationMs > 0 && positionMs >= durationMs * threshold
