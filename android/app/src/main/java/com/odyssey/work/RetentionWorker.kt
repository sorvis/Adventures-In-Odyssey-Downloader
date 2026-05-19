package com.odyssey.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.download.EpisodeDownloader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Prunes oldest local downloads beyond the configured retention count.
 *
 * Two modes (per locked-in plan):
 *  - NAS configured     → never prune un-archived episodes (waits for archive)
 *  - NAS not configured → prune oldest regardless of archive status
 *
 * Pruning shape (NAS-configured): the row is NOT deleted from the DB.
 * The file is deleted and the row is converted into a backup-mirror
 * ghost (filePath=null, sourceUrl/downloadUrl="backup://<id>",
 * archivedAt preserved). Keeping the row stops DailyCheckWorker from
 * re-ingesting the episode as a "new" row on the next pull-to-refresh
 * and re-downloading from the CDN — the loop that the user surfaced
 * after v0.1.59. The existing ghost-promotion path in DailyCheckWorker
 * refreshes the row's metadata on the next provider fetch without
 * re-enqueueing a download.
 *
 * Pruning shape (NAS not configured): the row IS deleted, same as
 * before — without a NAS to fall back on, leaving a row pointing at a
 * file we just deleted is just noise.
 */
@HiltWorker
class RetentionWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val episodes: EpisodeDao,
    private val downloader: EpisodeDownloader,
    private val settings: SettingsRepo,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val s = settings.flow.first()
        val downloaded = episodes.downloadedOldestFirst()
        val excess = downloaded.size - s.retentionCount
        if (excess <= 0) return Result.success()

        val candidates = if (s.nasConfigured) {
            downloaded.filter { it.archivedAt != null }
        } else {
            downloaded
        }
        val toPrune = candidates.take(excess)
        for (ep in toPrune) {
            ep.filePath?.let { downloader.delete(File(it)) }
            if (s.nasConfigured) {
                episodes.convertToBackupGhost(ep.providerId, ep.externalId)
            } else {
                episodes.delete(ep.episodeId)
            }
        }
        return Result.success()
    }
}
