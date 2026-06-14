package com.odyssey.work

import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.PlaybackDao
import com.odyssey.debug.DebugLogger
import com.odyssey.download.EpisodeDownloader
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Free disk for incoming downloads by ghosting any row that's both
 * (a) virtually finished (within [DEFAULT_THRESHOLD_MS] of the end)
 * and (b) safe on the NAS backup. Local audio is deleted; the row
 * is converted to a backup-mirror ghost (filePath=null,
 * sourceUrl/downloadUrl="backup://<id>"), so tapping it in Recent
 * still streams from NAS — same shape RetentionWorker produces for
 * capped-out rows.
 *
 * Triggered from [DailyCheckWorker] only when the worker actually
 * ingested new episodes — the user spec is "delete to make room for
 * the new one." Idle refreshes shouldn't churn the catalog.
 *
 * The 1-minute threshold is tighter than the existing 95% completion
 * threshold ([com.odyssey.player.PlaybackFormat.shouldMarkComplete]):
 * a 25-min AIO episode at 95% still has ~75 s left, which the user
 * may want to finish. Restricting to ≤60 s left keeps the sweep
 * conservative.
 *
 * Skips rows where:
 *   - durationMs ≤ 0 (Media3 hasn't computed it yet — can't know
 *     "1 min left" without knowing total)
 *   - no playback position exists (user never tapped play)
 *   - archivedAt is null (no backup to fall back on; we'd be
 *     destroying data, not freeing space)
 *   - filePath is null (already a ghost — nothing to free)
 */
@Singleton
class PlayedThroughSweep @Inject constructor(
    private val episodes: EpisodeDao,
    private val playback: PlaybackDao,
    private val downloader: EpisodeDownloader,
) {

    /**
     * Returns the number of rows pruned.
     */
    suspend fun sweep(thresholdMs: Long = DEFAULT_THRESHOLD_MS): Int {
        val candidates = episodes.downloadedOldestFirst()
            .filter { it.archivedAt != null && it.filePath != null }
        if (candidates.isEmpty()) return 0

        var pruned = 0
        for (ep in candidates) {
            val pos = playback.getByKey(ep.providerId, ep.externalId) ?: continue
            if (pos.durationMs <= 0L) continue
            val remaining = pos.durationMs - pos.positionMs
            if (remaining > thresholdMs) continue

            DebugLogger.i(
                TAG,
                "ghosting ${ep.providerId}:${ep.externalId} \"${ep.title}\" — " +
                    "remaining=${remaining}ms (≤${thresholdMs}ms), archived, " +
                    "freeing ${ep.fileSize}B",
            )
            ep.filePath?.let { downloader.delete(File(it)) }
            episodes.convertToBackupGhost(ep.providerId, ep.externalId)
            pruned++
        }
        if (pruned > 0) DebugLogger.i(TAG, "sweep done — pruned=$pruned")
        return pruned
    }

    companion object {
        private const val TAG = "PlayedThroughSweep"
        const val DEFAULT_THRESHOLD_MS: Long = 60_000L
    }
}
