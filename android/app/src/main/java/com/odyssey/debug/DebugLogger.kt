package com.odyssey.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-lifetime in-memory log capture. Mirrors entries to logcat
 * (so adb logcat still works) AND keeps the last [CAP] entries in a
 * StateFlow that DebugScreen renders. Use from anywhere — no Hilt
 * needed since it's a singleton-by-language top-level object.
 *
 * Usage:
 *   DebugLogger.d("RecentVm", "play(${ep.episodeId}) — dispatch=stream")
 *   DebugLogger.e("Player", "playStream failed", e)
 */
object DebugLogger {

    /** How many entries to keep in memory. Older entries fall off. */
    const val CAP = 500

    private val _entries = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val entries: StateFlow<List<DebugLogEntry>> = _entries

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.ERROR, tag, message, throwable)

    fun clear() { _entries.value = emptyList() }

    private fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            LogLevel.WARN -> Log.w(tag, message, throwable)
            LogLevel.ERROR -> Log.e(tag, message, throwable)
        }
        val entry = DebugLogEntry(
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwable = throwable?.let { Log.getStackTraceString(it) },
        )
        // Compare-and-set loop in case multiple threads log concurrently.
        while (true) {
            val current = _entries.value
            val next = appendCapped(current, entry, CAP)
            if (_entries.compareAndSet(current, next)) break
        }
    }
}
