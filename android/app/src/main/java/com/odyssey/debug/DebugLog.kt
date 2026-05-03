package com.odyssey.debug

/**
 * Pure data + helper for the in-app debug log. Kept Android-free so the
 * ring-buffer cap behavior is JVM-testable. The Android-coupled glue
 * (StateFlow + android.util.Log forwarding) lives in DebugLogger.
 */

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class DebugLogEntry(
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: String? = null,   // pre-rendered stack trace, optional
)

/**
 * Append [entry] to [buffer] and trim to at most [cap] entries (oldest
 * dropped first). Returns a NEW list — caller swaps it into a StateFlow.
 */
fun appendCapped(
    buffer: List<DebugLogEntry>,
    entry: DebugLogEntry,
    cap: Int,
): List<DebugLogEntry> {
    require(cap >= 0) { "cap must be non-negative, got $cap" }
    if (cap == 0) return emptyList()
    val next = buffer + entry
    return if (next.size <= cap) next else next.takeLast(cap)
}
