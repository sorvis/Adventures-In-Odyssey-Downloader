package com.odyssey.player

/**
 * Convert a slider fraction (0..1) into an absolute media position
 * (clamped to [0, durationMs]). Pure JVM, testable.
 *
 * The Material3 Slider in NowPlayingScreen reports its value as a
 * Float in [0, 1]. The MediaController seeks in milliseconds. This
 * helper bridges the two and clamps both inputs defensively.
 */
fun seekTargetMs(fraction: Float, durationMs: Long): Long {
    if (durationMs <= 0L) return 0L
    val clampedFrac = fraction.coerceIn(0f, 1f)
    val target = (clampedFrac.toDouble() * durationMs).toLong()
    return target.coerceIn(0L, durationMs)
}
