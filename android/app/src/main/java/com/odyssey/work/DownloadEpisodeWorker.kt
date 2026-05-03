package com.odyssey.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.download.DownloadProgressTracker
import com.odyssey.download.EpisodeDownloader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@HiltWorker
class DownloadEpisodeWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val episodes: EpisodeDao,
    private val downloader: EpisodeDownloader,
    private val scheduler: WorkScheduler,
    private val settings: SettingsRepo,
    private val progressTracker: DownloadProgressTracker,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_EPISODE_ID, -1L)
        if (id <= 0) return Result.failure()
        val ep = episodes.byId(id) ?: return Result.failure()
        if (ep.filePath != null) return Result.success()

        return runCatching {
            val out = downloader.fileFor(ep.episodeId, ep.title)
            val size = withContext(Dispatchers.IO) {
                downloader.download(ep.downloadUrl, out) { bytesRead, totalBytes ->
                    progressTracker.update(ep.episodeId, bytesRead, totalBytes)
                }
            }
            progressTracker.clear(ep.episodeId)
            episodes.markDownloaded(ep.episodeId, out.absolutePath, size, System.currentTimeMillis())
            scheduler.enqueueArchive(ep.episodeId, allowMetered = settings.flow.first().allowMeteredDownloads)
            Result.success()
        }.getOrElse {
            // Worker may retry; wipe the progress entry so the row's bar
            // doesn't sit at a stale percent until the next run picks up.
            progressTracker.clear(ep.episodeId)
            Result.retry()
        }
    }

    companion object { const val KEY_EPISODE_ID = "episodeId" }
}
