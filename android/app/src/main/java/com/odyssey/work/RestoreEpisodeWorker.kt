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
 * NasClient.audioUrl + bearer token, and the inserted local row may
 * be brand new (BrowseNasScreen lets users pin episodes the phone
 * has never seen).
 *
 * Output state when successful:
 *   - File on disk at ExternalFiles/Episodes/<id>-<title>.mp3
 *   - LocalEpisodeEntity row (inserted or replaced) with
 *     filePath/fileSize/downloadedAt set AND archivedAt set (the
 *     server already has it).
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
        val id = inputData.getLong(KEY_EPISODE_ID, -1L)
        if (id <= 0) {
            DebugLogger.w("RestoreWorker", "doWork — invalid episodeId=$id")
            return Result.failure()
        }
        val title = inputData.getString(KEY_TITLE) ?: ""
        val airDate = inputData.getString(KEY_AIR_DATE)
        val album = inputData.getString(KEY_ALBUM)
        val description = inputData.getString(KEY_DESCRIPTION)
        val durationSecs = inputData.getLong(KEY_DURATION_SECS, 0L)

        // If a local row already exists with a file on disk, treat as
        // success — restoring a file we already have is a no-op.
        val existing = episodes.byId(id)
        if (existing != null && existing.filePath != null) {
            DebugLogger.d("RestoreWorker", "doWork($id) — already on phone, skipping")
            return Result.success()
        }

        val nasAudio = nas.audioUrl(id).getOrNull()
        if (nasAudio == null) {
            DebugLogger.w("RestoreWorker", "doWork($id) — NAS not configured / unreachable")
            return Result.failure()
        }

        DebugLogger.i("RestoreWorker", "doWork($id) — pulling \"$title\" from backup")
        val out = downloader.fileFor(id, title.ifBlank { "episode-$id" })
        return runCatching {
            val size = withContext(Dispatchers.IO) {
                downloader.download(
                    url = nasAudio.url,
                    out = out,
                    authHeader = nasAudio.authHeader,
                    onProgress = { bytesRead, totalBytes ->
                        progress.update(id, bytesRead, totalBytes)
                    },
                )
            }
            progress.clear(id)
            val now = System.currentTimeMillis()
            val row = (existing ?: LocalEpisodeEntity(
                providerId = "aio",
                externalId = id.toString(),
                title = title.ifBlank { "Episode $id" },
                airDate = airDate,
                description = description,
                // We don't have a real source URL or download URL —
                // these fields are NOT NULL in the schema, fill with
                // backup-scoped placeholders so playback paths know
                // not to treat them as oneplace URLs.
                sourceUrl = "backup://$id",
                downloadUrl = "backup://$id",
                filePath = null,
                fileSize = 0L,
                durationMs = durationSecs * 1000,
                downloadedAt = null,
                archivedAt = null,
            )).copy(
                title = (existing?.title ?: title).ifBlank { "Episode $id" },
                filePath = out.absolutePath,
                fileSize = size,
                durationMs = if (durationSecs > 0L) durationSecs * 1000 else (existing?.durationMs ?: 0L),
                downloadedAt = now,
                // The server has it (that's where we just pulled from)
                // — flag as archived so the album dual-badge UX shows
                // both "✓ on phone" and "☁ on backup".
                archivedAt = existing?.archivedAt ?: now,
                airDate = existing?.airDate ?: airDate,
                description = existing?.description ?: description,
            )
            episodes.upsert(row)
            DebugLogger.i("RestoreWorker", "doWork($id) — restored ${size}B to ${out.absolutePath}")
            Result.success()
        }.getOrElse { e ->
            progress.clear(id)
            DebugLogger.w("RestoreWorker", "doWork($id) — restore failed, retrying", e)
            if (e is NasNotConfiguredException) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val KEY_EPISODE_ID = "episodeId"
        const val KEY_TITLE = "title"
        const val KEY_AIR_DATE = "airDate"
        const val KEY_ALBUM = "album"
        const val KEY_DESCRIPTION = "description"
        const val KEY_DURATION_SECS = "durationSecs"
    }
}
