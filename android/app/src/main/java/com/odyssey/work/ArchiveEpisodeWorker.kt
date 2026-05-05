package com.odyssey.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odyssey.data.local.EpisodeDao
import com.odyssey.debug.DebugLogger
import com.odyssey.download.ArchiveProgressTracker
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
    private val progress: ArchiveProgressTracker,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(DownloadEpisodeWorker.KEY_EPISODE_ID, -1L)
        if (id <= 0) {
            DebugLogger.w("ArchiveWorker", "doWork — invalid episodeId=$id")
            return Result.failure()
        }

        val ep = episodes.byId(id)
        if (ep == null) {
            DebugLogger.w("ArchiveWorker", "doWork($id) — no row in DB")
            return Result.failure()
        }
        val path = ep.filePath
        if (path == null) {
            DebugLogger.w("ArchiveWorker", "doWork($id) — filePath is null, can't archive")
            return Result.failure()
        }
        if (ep.archivedAt != null) {
            DebugLogger.d("ArchiveWorker", "doWork($id) — already archived, skipping")
            return Result.success()
        }

        if (!nas.isConfigured()) {
            DebugLogger.d("ArchiveWorker", "doWork($id) — no NAS configured, skipping")
            scheduler.enqueueRetention()
            return Result.success()
        }

        val file = File(path)
        if (!file.exists()) {
            DebugLogger.w("ArchiveWorker", "doWork($id) — file gone from disk: $path")
            return Result.failure()
        }
        DebugLogger.i(
            "ArchiveWorker",
            "doWork($id) — starting upload of ${file.length()} bytes (\"${ep.title}\")",
        )

        val result = withContext(Dispatchers.IO) {
            nas.upload(
                episodeId    = ep.episodeId,
                title        = ep.title,
                airDate      = ep.airDate,
                description  = ep.description,
                durationSecs = ep.durationMs / 1000,
                sourceUrl    = ep.sourceUrl,
                audio        = file,
                onProgress   = { sent, total -> progress.update(ep.episodeId, sent, total) },
            )
        }
        progress.clear(ep.episodeId)
        return result.fold(
            onSuccess = {
                DebugLogger.i("ArchiveWorker", "doWork($id) — upload OK, marking archived")
                episodes.markArchived(ep.episodeId, System.currentTimeMillis())
                scheduler.enqueueRetention()
                Result.success()
            },
            onFailure = { e ->
                if (e is NasNotConfiguredException) {
                    DebugLogger.d("ArchiveWorker", "doWork($id) — NAS unconfigured mid-flight")
                    Result.success()
                } else {
                    DebugLogger.w("ArchiveWorker", "doWork($id) — upload failed, retrying", e)
                    Result.retry()
                }
            },
        )
    }
}
