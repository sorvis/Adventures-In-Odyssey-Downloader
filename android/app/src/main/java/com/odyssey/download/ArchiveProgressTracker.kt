package com.odyssey.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirror of [DownloadProgressTracker] but for **uploads to the backup
 * service** (ArchiveEpisodeWorker → NasClient.upload). Same in-memory,
 * process-lifetime, single-Map StateFlow shape so the Transfers UI can
 * render both sides identically.
 *
 * Lives in `download/` next to its sibling — kept here instead of in
 * `nas/` because the package "download" already houses transfer
 * tracking infrastructure and DownloadProgressEntry is shared.
 */
@Singleton
class ArchiveProgressTracker @Inject constructor() {

    private val _progress = MutableStateFlow<Map<Long, DownloadProgressEntry>>(emptyMap())
    val progress: StateFlow<Map<Long, DownloadProgressEntry>> = _progress

    fun update(episodeId: Long, bytesUploaded: Long, totalBytes: Long) {
        while (true) {
            val current = _progress.value
            val next = current + (episodeId to DownloadProgressEntry(bytesUploaded, totalBytes))
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
