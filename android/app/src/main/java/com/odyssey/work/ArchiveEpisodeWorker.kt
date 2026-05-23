package com.odyssey.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odyssey.catalog.AioCatalogRepo
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
 * Pushes a downloaded episode to the NAS service. As of v0.1.72 routes
 * uploads through the provider-aware v2 endpoint
 * `POST /providers/{providerId}/episodes` so YSH archives work
 * alongside AIO.
 *
 * Input data shapes (worker reads whichever pair is set, in priority):
 *   - KEY_PROVIDER_ID + KEY_EXTERNAL_ID  (v2, preferred)
 *   - KEY_EPISODE_ID                     (legacy AIO-only — kept so
 *                                         in-flight WorkManager entries
 *                                         enqueued by pre-v0.1.72
 *                                         WorkScheduler still complete)
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
    private val catalog: AioCatalogRepo,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // Prefer v2 input shape; fall back to legacy AIO-only Long id
        // for in-flight WorkManager entries enqueued before v0.1.72.
        val providerId = inputData.getString(KEY_PROVIDER_ID)
        val externalIdInput = inputData.getString(KEY_EXTERNAL_ID)
        val legacyId = inputData.getLong(DownloadEpisodeWorker.KEY_EPISODE_ID, -1L)

        val resolvedProvider: String
        val resolvedExternalId: String
        if (!providerId.isNullOrBlank() && !externalIdInput.isNullOrBlank()) {
            resolvedProvider = providerId
            resolvedExternalId = externalIdInput
        } else if (legacyId > 0L) {
            resolvedProvider = "aio"
            resolvedExternalId = legacyId.toString()
        } else {
            DebugLogger.w("ArchiveWorker", "doWork — invalid input: providerId=$providerId externalId=$externalIdInput legacyId=$legacyId")
            return Result.failure()
        }

        val ep = episodes.byKey(resolvedProvider, resolvedExternalId)
        if (ep == null) {
            DebugLogger.w("ArchiveWorker", "doWork($resolvedProvider:$resolvedExternalId) — no row in DB")
            return Result.failure()
        }
        val path = ep.filePath
        if (path == null) {
            DebugLogger.w("ArchiveWorker", "doWork($resolvedProvider:$resolvedExternalId) — filePath is null, can't archive")
            return Result.failure()
        }
        if (ep.archivedAt != null) {
            DebugLogger.d("ArchiveWorker", "doWork($resolvedProvider:$resolvedExternalId) — already archived, skipping")
            return Result.success()
        }

        if (!nas.isConfigured()) {
            DebugLogger.d("ArchiveWorker", "doWork($resolvedProvider:$resolvedExternalId) — no NAS configured, skipping")
            scheduler.enqueueRetention()
            return Result.success()
        }

        val file = File(path)
        if (!file.exists()) {
            DebugLogger.w("ArchiveWorker", "doWork($resolvedProvider:$resolvedExternalId) — file gone from disk: $path")
            return Result.failure()
        }
        // AIO uses the bundled catalog to resolve album phone-side.
        // YSH carries album metadata on the row itself (from the
        // yourstoryhour.org catalog joined at ingest time) — there's
        // no AIO-style server-side enrichment fallback, so we send
        // whatever the row has.
        val album = if (resolvedProvider == "aio") {
            catalog.match(ep.title)?.album?.name
        } else {
            ep.albumName
        }
        DebugLogger.i(
            "ArchiveWorker",
            "doWork($resolvedProvider:$resolvedExternalId) — starting upload of ${file.length()} bytes " +
                "(\"${ep.title}\") album=${album ?: "<unmatched>"}",
        )

        // Use long episodeId for the in-flight progress tracker so the
        // existing UI keyed on Long ids still finds the entry.
        val trackerKey = ep.episodeId
        val result = withContext(Dispatchers.IO) {
            nas.uploadV2(
                providerId   = resolvedProvider,
                externalId   = resolvedExternalId,
                title        = ep.title,
                airDate      = ep.airDate,
                description  = ep.description,
                durationSecs = ep.durationMs / 1000,
                sourceUrl    = ep.sourceUrl,
                audio        = file,
                album        = album,
                onProgress   = { sent, total -> progress.update(trackerKey, sent, total) },
            )
        }
        progress.clear(trackerKey)
        return result.fold(
            onSuccess = {
                DebugLogger.i("ArchiveWorker", "doWork($resolvedProvider:$resolvedExternalId) — upload OK, marking archived")
                episodes.markArchivedByKey(resolvedProvider, resolvedExternalId, System.currentTimeMillis())
                scheduler.enqueueRetention()
                Result.success()
            },
            onFailure = { e ->
                if (e is NasNotConfiguredException) {
                    DebugLogger.d("ArchiveWorker", "doWork($resolvedProvider:$resolvedExternalId) — NAS unconfigured mid-flight")
                    Result.success()
                } else {
                    DebugLogger.w("ArchiveWorker", "doWork($resolvedProvider:$resolvedExternalId) — upload failed, retrying", e)
                    Result.retry()
                }
            },
        )
    }

    companion object {
        /** v0.1.72: providerId of the row to archive. Required for new ingests. */
        const val KEY_PROVIDER_ID = "providerId"
        /** v0.1.72: externalId of the row to archive. Required for new ingests. */
        const val KEY_EXTERNAL_ID = "externalId"
    }
}
