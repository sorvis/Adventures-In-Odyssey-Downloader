package com.odyssey.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-flight progress for files being PULLED from the backup service
 * back onto the phone. Sibling to DownloadProgressTracker (oneplace
 * pulls) and ArchiveProgressTracker (phone → backup uploads). Same
 * shape so the Transfers screen can render all three identically.
 */
@Singleton
class RestoreProgressTracker @Inject constructor() {

    private val _progress = MutableStateFlow<Map<Long, DownloadProgressEntry>>(emptyMap())
    val progress: StateFlow<Map<Long, DownloadProgressEntry>> = _progress

    fun update(episodeId: Long, bytesRead: Long, totalBytes: Long) {
        while (true) {
            val current = _progress.value
            val next = current + (episodeId to DownloadProgressEntry(bytesRead, totalBytes))
            if (_progress.compareAndSet(current, next)) return
        }
    }

    fun clear(episodeId: Long) {
        while (true) {
            val current = _progress.value
            if (episodeId !in current) return
            val next = current - episodeId
            if (_progress.compareAndSet(current, next)) return
        }
    }
}
