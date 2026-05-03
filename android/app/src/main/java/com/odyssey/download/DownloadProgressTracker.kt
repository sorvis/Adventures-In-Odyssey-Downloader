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
