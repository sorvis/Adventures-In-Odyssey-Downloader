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

/**
 * Total episode length for the row trailing slot when the user
 * hasn't started playing yet — "25 min", "1hr 12min", or null when
 * duration isn't known. Pairs with formatRemaining: that one shows
 * "X min left" once playback has begun, this one shows the original
 * total before then so the user always sees a length cue.
 */
fun formatTotalDuration(durationMs: Long): String? {
    if (durationMs <= 0L) return null
    val totalMin = durationMs / 60_000L
    if (totalMin < 1L) return null
    val hours = totalMin / 60L
    val mins = totalMin % 60L
    return if (hours > 0) "${hours}hr ${mins}min" else "${mins} min"
}

/**
 * Format the remaining time on a partially-played episode for display
 * in the row trailing slot — BeyondPod-style ("52 min left", "1 hr 12 min left").
 *
 * Returns null when:
 *   - position is at or near zero (haven't started)
 *   - duration is unknown (Media3 hasn't computed it yet)
 *   - we're past the 95% completion threshold (treated as finished)
 *   - remaining is under one minute (not worth showing)
 *
 * The UI uses null to mean "show nothing extra" — fall back to the
 * default trailing chip (▶ stream / ✓ played / etc.).
 */
fun formatRemaining(positionMs: Long, durationMs: Long): String? {
    if (durationMs <= 0L) return null
    if (positionMs < 1_000L) return null                                    // haven't started
    if (shouldMarkComplete(positionMs, durationMs)) return null              // basically done
    val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
    val totalMin = remainingMs / 60_000L
    if (totalMin < 1L) return null                                          // <1 min — too granular
    val hours = totalMin / 60L
    val mins = totalMin % 60L
    return if (hours > 0) "${hours}hr ${mins}min left" else "${mins} min left"
}
