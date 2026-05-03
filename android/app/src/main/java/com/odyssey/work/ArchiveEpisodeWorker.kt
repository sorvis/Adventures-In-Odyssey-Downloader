package com.odyssey.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odyssey.data.local.EpisodeDao
import com.odyssey.nas.NasClient
import com.odyssey.nas.NasNotConfiguredException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pushes a downloaded episode to the NAS service. If no NAS is configured,
 * skips silently — the episode stays on-device and retention rules adapt.
 *
 * On failure (network, NAS down, auth), retries with exponential backoff
 * via WorkManager. The episode remains downloaded and playable throughout.
 */
@HiltWorker
class ArchiveEpisodeWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val episodes: EpisodeDao,
    private val nas: NasClient,
    private val scheduler: WorkScheduler,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(DownloadEpisodeWorker.KEY_EPISODE_ID, -1L)
        if (id <= 0) return Result.failure()

        val ep = episodes.byId(id) ?: return Result.failure()
        val path = ep.filePath ?: return Result.failure()  // download must precede archive
        if (ep.archivedAt != null) return Result.success()

        if (!nas.isConfigured()) {
            // No NAS — schedule retention sweep and exit clean.
            scheduler.enqueueRetention()
            return Result.success()
        }

        val result = withContext(Dispatchers.IO) {
            nas.upload(
                episodeId    = ep.episodeId,
                title        = ep.title,
                airDate      = ep.airDate,
                description  = ep.description,
                durationSecs = ep.durationMs / 1000,
                sourceUrl    = ep.sourceUrl,
                audio        = File(path),
            )
        }
        return result.fold(
            onSuccess = {
                episodes.markArchived(ep.episodeId, System.currentTimeMillis())
                scheduler.enqueueRetention()
                Result.success()
            },
            onFailure = { e ->
                if (e is NasNotConfiguredException) Result.success() else Result.retry()
            },
        )
    }
}
