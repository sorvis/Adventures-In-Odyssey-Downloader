package com.odyssey.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.debug.DebugLogger
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
        // Prefer the new (providerId, externalId) shape. Fall back to
        // the legacy Long episodeId path so a OneTimeWorkRequest that
        // a pre-update build enqueued (and WorkManager replays) still
        // resolves to an AIO row.
        val providerId = inputData.getString(KEY_PROVIDER_ID)
        val externalId = inputData.getString(KEY_EXTERNAL_ID)
        val ep = if (providerId != null && externalId != null) {
            episodes.byKey(providerId, externalId)
        } else {
            val legacyId = inputData.getLong(KEY_EPISODE_ID, -1L)
            if (legacyId <= 0) return Result.failure()
            episodes.byId(legacyId)
        } ?: return Result.failure()
        if (ep.filePath != null) return Result.success()

        DebugLogger.i(TAG, "download start: ${ep.providerId}/${ep.externalId} \"${ep.title}\" url=${ep.downloadUrl}")
        return runCatching {
            val out = downloader.fileFor(ep.providerId, ep.externalId, ep.title)
            // The progress tracker is still Long-keyed. AIO externalIds
            // parse fine; YSH externalIds like "ysh-sku-1958" don't, so
            // we hash them to a stable Long for tracker indexing — the
            // user-visible row id is `(providerId, externalId)` either
            // way, this is purely a tracker map key.
            val progressKey = ep.externalId.toLongOrNull() ?: ep.externalId.hashCode().toLong()
            val size = withContext(Dispatchers.IO) {
                downloader.download(ep.downloadUrl, out) { bytesRead, totalBytes ->
                    progressTracker.update(progressKey, bytesRead, totalBytes)
                }
            }
            progressTracker.clear(progressKey)
            // markDownloaded is still AIO-only legacy. For YSH rows go
            // through the composite-key update path (a new DAO helper
            // would be cleaner; for now, upsert the existing entity
            // with the new file fields filled in).
            if (ep.providerId == "aio") {
                episodes.markDownloaded(ep.externalId.toLong(), out.absolutePath, size, System.currentTimeMillis())
                scheduler.enqueueArchive(ep.externalId.toLong(), allowMetered = settings.flow.first().allowMeteredDownloads)
            } else {
                episodes.upsert(
                    ep.copy(
                        filePath = out.absolutePath,
                        fileSize = size,
                        downloadedAt = System.currentTimeMillis(),
                    ),
                )
                // Archive upload for non-AIO is deferred — server route
                // rewrite for /providers/{provider}/... is step 11b.
            }
            DebugLogger.i(TAG, "download success: ${ep.providerId}/${ep.externalId} bytes=$size")
            Result.success()
        }.getOrElse { t ->
            // Surface the cause. The retry loop is correct for transient
            // failures (5xx, network drop), but without this log a hard
            // failure (403 with bad UA, missing downloadUrl, etc.) was
            // invisible from the device — see the YSH download bug.
            DebugLogger.w(TAG, "download failed (will retry): ${ep.providerId}/${ep.externalId} url=${ep.downloadUrl}", t)
            val progressKey = ep.externalId.toLongOrNull() ?: ep.externalId.hashCode().toLong()
            progressTracker.clear(progressKey)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "DownloadEpisodeWorker"
        const val KEY_EPISODE_ID = "episodeId"     // legacy AIO-only
        const val KEY_PROVIDER_ID = "providerId"
        const val KEY_EXTERNAL_ID = "externalId"
    }
}
