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
            episodes.delete(ep.episodeId)
        }
        return Result.success()
    }
}
