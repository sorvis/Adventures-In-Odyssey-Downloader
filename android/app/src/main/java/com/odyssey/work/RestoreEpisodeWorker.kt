package com.odyssey.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.debug.DebugLogger
import com.odyssey.download.EpisodeDownloader
import com.odyssey.download.RestoreProgressTracker
import com.odyssey.nas.NasClient
import com.odyssey.nas.NasNotConfiguredException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pulls a backup-service episode onto the phone for offline play.
 * Mirror of DownloadEpisodeWorker but the source URL comes from
 * NasClient.audioUrlByKey + bearer token, and the inserted local row
 * may be brand new (BrowseNasScreen lets users pin episodes the phone
 * has never seen).
 *
 * v0.1.73: provider-aware. Reads `KEY_PROVIDER_ID + KEY_EXTERNAL_ID`
 * from worker data so YSH episodes (non-numeric externalIds like
 * `ysh-sku-1958`) can be restored too. Falls back to the legacy
 * `KEY_EPISODE_ID` Long path when the v2 keys aren't set — preserves
 * any in-flight WorkManager entries enqueued by pre-v0.1.73 callers.
 *
 * Output state when successful:
 *   - File on disk at ExternalFiles/Episodes/<providerId>/<id>-<title>.mp3
 *   - LocalEpisodeEntity row (inserted or replaced) with the correct
 *     providerId + externalId, filePath/fileSize/downloadedAt set AND
 *     archivedAt set (the server already has it).
 *
 * Re-running on an episode that's already downloaded is a no-op.
 */
@HiltWorker
class RestoreEpisodeWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val episodes: EpisodeDao,
    private val downloader: EpisodeDownloader,
    private val nas: NasClient,
    private val progress: RestoreProgressTracker,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // Prefer the v2 (providerId, externalId) input shape; fall
        // back to legacy AIO-only Long for in-flight WorkManager
        // entries from pre-v0.1.73 enqueues.
        val providerIdInput = inputData.getString(KEY_PROVIDER_ID)
        val externalIdInput = inputData.getString(KEY_EXTERNAL_ID)
        val legacyId = inputData.getLong(KEY_EPISODE_ID, -1L)

        val providerId: String
        val externalId: String
        if (!providerIdInput.isNullOrBlank() && !externalIdInput.isNullOrBlank()) {
            providerId = providerIdInput
            externalId = externalIdInput
        } else if (legacyId > 0L) {
            providerId = "aio"
            externalId = legacyId.toString()
        } else {
            DebugLogger.w(
                "RestoreWorker",
                "doWork — invalid input: providerId=$providerIdInput externalId=$externalIdInput legacyId=$legacyId",
            )
            return Result.failure()
        }

        val title = inputData.getString(KEY_TITLE) ?: ""
        val airDate = inputData.getString(KEY_AIR_DATE)
        val album = inputData.getString(KEY_ALBUM)
        val description = inputData.getString(KEY_DESCRIPTION)
        val durationSecs = inputData.getLong(KEY_DURATION_SECS, 0L)

        // If a local row already exists with a file on disk, treat as
        // success — restoring a file we already have is a no-op.
        val existing = episodes.byKey(providerId, externalId)
        if (existing != null && existing.filePath != null) {
            DebugLogger.d("RestoreWorker", "doWork($providerId:$externalId) — already on phone, skipping")
            return Result.success()
        }

        val nasAudio = nas.audioUrlByKey(providerId, externalId).getOrNull()
        if (nasAudio == null) {
            DebugLogger.w("RestoreWorker", "doWork($providerId:$externalId) — NAS not configured / unreachable")
            return Result.failure()
        }

        DebugLogger.i("RestoreWorker", "doWork($providerId:$externalId) — pulling \"$title\" from backup")
        val out = downloader.fileFor(providerId, externalId, title.ifBlank { "episode-$externalId" })
        // Track in-flight bytes by the row's episodeId (Long) so the
        // existing progress UI (keyed on Long) still matches up. AIO
        // externalIds parse cleanly; YSH falls back to hashCode().
        val trackerKey = externalId.toLongOrNull() ?: externalId.hashCode().toLong()
        return runCatching {
            val size = withContext(Dispatchers.IO) {
                downloader.download(
                    url = nasAudio.url,
                    out = out,
                    authHeader = nasAudio.authHeader,
                    onProgress = { bytesRead, totalBytes ->
                        progress.update(trackerKey, bytesRead, totalBytes)
                    },
                )
            }
            progress.clear(trackerKey)
            val now = System.currentTimeMillis()
            val row = (existing ?: LocalEpisodeEntity(
                providerId = providerId,
                externalId = externalId,
                title = title.ifBlank { "Episode $externalId" },
                airDate = airDate,
                description = description,
                // No real CDN URL for restored backups — these placeholders
                // tell playback paths to resolve via NasClient.audioUrlByKey
                // instead of trying an HTTP CDN download.
                sourceUrl = "backup://$externalId",
                downloadUrl = "backup://$externalId",
                filePath = null,
                fileSize = 0L,
                durationMs = durationSecs * 1000,
                downloadedAt = null,
                archivedAt = null,
            )).copy(
                title = (existing?.title ?: title).ifBlank { "Episode $externalId" },
                filePath = out.absolutePath,
                fileSize = size,
                durationMs = if (durationSecs > 0L) durationSecs * 1000 else (existing?.durationMs ?: 0L),
                downloadedAt = now,
                // The server has it (that's where we just pulled from) —
                // flag as archived so the row's "✓ on phone" + "☁ on
                // backup" badges both light up.
                archivedAt = existing?.archivedAt ?: now,
                airDate = existing?.airDate ?: airDate,
                description = existing?.description ?: description,
            )
            episodes.upsert(row)
            DebugLogger.i("RestoreWorker", "doWork($providerId:$externalId) — restored ${size}B to ${out.absolutePath}")
            Result.success()
        }.getOrElse { e ->
            progress.clear(trackerKey)
            DebugLogger.w("RestoreWorker", "doWork($providerId:$externalId) — restore failed, retrying", e)
            if (e is NasNotConfiguredException) Result.failure() else Result.retry()
        }
    }

    companion object {
        /** v0.1.73: providerId of the row to restore. Required for new ingests. */
        const val KEY_PROVIDER_ID = "providerId"
        /** v0.1.73: externalId of the row to restore. Required for new ingests. */
        const val KEY_EXTERNAL_ID = "externalId"
        /** Legacy AIO-only Long episodeId. Kept for in-flight pre-v0.1.73 enqueues. */
        const val KEY_EPISODE_ID = "episodeId"
        const val KEY_TITLE = "title"
        const val KEY_AIR_DATE = "airDate"
        const val KEY_ALBUM = "album"
        const val KEY_DESCRIPTION = "description"
        const val KEY_DURATION_SECS = "durationSecs"
    }
}
