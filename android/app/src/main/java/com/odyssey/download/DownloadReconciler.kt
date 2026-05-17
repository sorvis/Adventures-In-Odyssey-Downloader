package com.odyssey.download

import com.odyssey.data.local.EpisodeDao
import com.odyssey.debug.DebugLogger
import com.odyssey.work.ArchiveEnqueuer
import com.odyssey.work.DownloadEnqueuer
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recover from "download is wedged" states that the v0.1.51 416 fix
 * can't unstick on its own.
 *
 * The bug background: pre-v0.1.51, a download worker could write the
 * complete file to disk and then die (worker reaped by the OS, throw
 * in the post-download upsert path, swallowed by `runCatching`) before
 * `episodes.upsert(filePath=...)` ran. The DB row stays at
 * `filePath = null`; the next retry sees `out.length() == content-length`,
 * sends `Range: bytes=N-`, gets a 416, and `Result.retry()`s into
 * WorkManager's exponential backoff. After enough cycles the next
 * retry is hours away — so even after the v0.1.51 fix is installed,
 * those rows wait silently for the backoff timer.
 *
 * What this class does: on app launch, iterate every row with
 * `filePath = null`, check whether the expected file is sitting on
 * disk with non-zero bytes, and if so call
 * [DownloadEnqueuer.kickDownload] to cancel the stuck work and enqueue
 * fresh. The new (post-fix) worker runs immediately, hits the
 * 416-recovery path in [EpisodeDownloader], confirms completeness via
 * HEAD, and persists `filePath` on the upsert — breaking the loop.
 *
 * Idempotent. Safe to run every launch — does nothing when there are
 * no orphans. Doesn't touch rows whose file is missing (those download
 * normally on the next worker run) or rows already marked downloaded.
 */
@Singleton
class DownloadReconciler @Inject constructor(
    private val episodes: EpisodeDao,
    private val downloader: EpisodeDownloader,
    private val scheduler: DownloadEnqueuer,
    private val archiveScheduler: ArchiveEnqueuer,
) {
    /**
     * Scan undownloaded rows; kick any whose file already exists on
     * disk. Returns the number of rows kicked, primarily for tests
     * and debug logging.
     */
    suspend fun reconcile(allowMetered: Boolean): Int {
        val rows = episodes.allUndownloaded()
        if (rows.isEmpty()) return 0
        var kicked = 0
        for (row in rows) {
            val file = downloader.fileFor(row.providerId, row.externalId, row.title)
            if (!file.exists() || file.length() == 0L) continue
            DebugLogger.i(
                TAG,
                "kick stuck download ${row.providerId}/${row.externalId} " +
                    "\"${row.title}\" (file on disk=${file.length()}B, filePath=null in DB)",
            )
            scheduler.kickDownload(row.providerId, row.externalId, allowMetered)
            kicked++
        }
        if (kicked > 0) {
            DebugLogger.i(TAG, "reconcile done — kicked $kicked stuck download(s)")
        }
        return kicked
    }

    /**
     * One-shot cleanup of AIO rows whose downloadUrl belongs to a
     * different oneplace show. Pre-v0.1.59 the AioOneplaceProvider
     * didn't filter the related-episodes API by showId, so Sekulow
     * / FOTF / etc. episodes leaked into the DB with providerId="aio"
     * and showed up in the AIO Library. This removes them.
     *
     * Identifies contamination by URL path: an AIO episode's
     * downloadUrl always contains `/adventures-in-odyssey/`; anything
     * else with providerId="aio" is a leak. Deletes the on-disk file
     * (if present) then the DB row. Idempotent — no-ops on a clean
     * DB. Returns the count of rows removed.
     */
    suspend fun cleanupCrossShowContamination(): Int {
        val all = episodes.observeAll().first()
        val contaminated = all.filter {
            it.providerId == "aio" && !it.downloadUrl.contains("/$AIO_SLUG/")
        }
        if (contaminated.isEmpty()) return 0
        for (row in contaminated) {
            DebugLogger.w(
                TAG,
                "cross-show contamination: removing ${row.providerId}/${row.externalId} " +
                    "\"${row.title}\" url=${row.downloadUrl}",
            )
            row.filePath?.let { File(it).delete() }
            // AIO externalIds parse to Long by construction (oneplace
            // CMS ids or broadcast numbers). The leaked rows are AIO-
            // shaped, so this is safe.
            row.externalId.toLongOrNull()?.let { episodeId ->
                // Cancel any pending archive WorkManager entry for this
                // episode FIRST. Otherwise ArchiveEpisodeWorker fires
                // later, finds no DB row, and spams the log with
                // "no row in DB" warnings — observed in user device
                // logs after v0.1.59 cleanup ran.
                archiveScheduler.cancelArchive(episodeId)
                episodes.delete(episodeId)
            }
        }
        DebugLogger.i(TAG, "cross-show cleanup done — removed ${contaminated.size} row(s)")
        return contaminated.size
    }

    private companion object {
        const val TAG = "DownloadReconciler"
        const val AIO_SLUG = "adventures-in-odyssey"
    }
}
