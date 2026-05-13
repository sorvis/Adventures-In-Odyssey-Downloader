package com.odyssey.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-lifetime in-memory map of in-flight download progress, keyed
 * by episodeId. Same pattern as DebugLogger — singleton-by-DI, exposes
 * a StateFlow the UI observes. State is intentionally not persisted:
 * worker resumes from disk after process death anyway, so on restart we
 * just start fresh from 0% and re-fill as the worker ticks.
 */
@Singleton
class DownloadProgressTracker @Inject constructor() {

    private val _progress = MutableStateFlow<Map<Long, DownloadProgressEntry>>(emptyMap())
    val progress: StateFlow<Map<Long, DownloadProgressEntry>> = _progress

    /**
     * Insert a placeholder "queued" entry — (bytesRead=0, totalBytes=0)
     * — so the row shows an indeterminate progress bar IMMEDIATELY after
     * the user taps the pin button, instead of staring at an unchanged
     * row while WorkManager waits for the network constraint to clear.
     *
     * No-op if there's already an entry (the worker may have started
     * before this call lands; don't clobber real bytes-in-flight with
     * zeros).
     */
    fun queue(episodeId: Long) {
        while (true) {
            val current = _progress.value
            if (episodeId in current) return    // worker beat us; keep its real data
            val next = current + (episodeId to DownloadProgressEntry(0L, 0L))
            if (_progress.compareAndSet(current, next)) return
        }
    }

    /** Update or insert an entry for [episodeId]. */
    fun update(episodeId: Long, bytesRead: Long, totalBytes: Long) {
        while (true) {
            val current = _progress.value
            val next = current + (episodeId to DownloadProgressEntry(bytesRead, totalBytes))
            if (_progress.compareAndSet(current, next)) return
        }
    }

    /** Drop [episodeId] from the map — call on completion or failure. */
    fun clear(episodeId: Long) {
        while (true) {
            val current = _progress.value
            if (episodeId !in current) return
            val next = current - episodeId
            if (_progress.compareAndSet(current, next)) return
        }
    }
}
