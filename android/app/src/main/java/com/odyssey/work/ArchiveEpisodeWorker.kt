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
import com.odyssey.app.SettingsRepo
import com.odyssey.nas.NasHttpException
import com.odyssey.player.RecoveryAction
import com.odyssey.player.decideRecovery
import kotlinx.coroutines.flow.first
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
    private val settings: SettingsRepo,
) : CoroutineWorker(ctx, params) {

    /**
     * Mirrors PlaybackRecovery's default: when the setting can't be
     * read, assume metered downloads are NOT allowed, so a repair can
     * never surprise the user with cellular data.
     */
    private suspend fun settingsAllowMetered(): Boolean =
        runCatching { settings.flow.first().allowMeteredDownloads }.getOrDefault(false)

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
            // v0.1.74 self-heal: the row says it's downloaded but the
            // file is gone (user cleanup, OS purge, partial-restore
            // crash). Without this clear-step the row sits forever on
            // the Sync screen as "queued" because
            // observeUnarchivedDownloaded() still returns it
            // (filePath != null && archivedAt == null) and WorkManager
            // has long since dropped the failed entry. Reset the
            // download state so the row falls off the queued list;
            // user can re-download to pick it back up if they want it.
            DebugLogger.w(
                "ArchiveWorker",
                "doWork($resolvedProvider:$resolvedExternalId) — file gone from disk: $path; " +
                    "clearing download state so the row stops looping as 'queued'",
            )
            episodes.markUndownloadedByKey(resolvedProvider, resolvedExternalId)
            // Success rather than failure: nothing more for this worker
            // run to do, and there's no retry that could help.
            return Result.success()
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
                // Only a successful archive clears the rejection count,
                // so a genuinely transient bad download doesn't hold the
                // cap against the row for the rest of its life.
                episodes.resetRedownloadAttempts(resolvedProvider, resolvedExternalId)
                // Stamp the only honest "backups are working" signal we
                // have. Written here and nowhere else, so if this value
                // goes stale while episodes pile up unarchived, backups
                // really have stopped — which is what went unnoticed for
                // 9 days during the 2026-09-14 archive-service outage.
                settings.recordBackupSuccess()
                scheduler.enqueueRetention()
                Result.success()
            },
            onFailure = { e ->
                when {
                    e is NasNotConfiguredException -> {
                        DebugLogger.d("ArchiveWorker", "doWork($resolvedProvider:$resolvedExternalId) — NAS unconfigured mid-flight")
                        Result.success()
                    }
                    e is NasHttpException && e.isPayloadRejected -> {
                        // 422: the archive's completeness gate refused
                        // these exact bytes as a truncated file.
                        // Re-sending the identical bytes
                        // cannot succeed, so a retry would only burn the
                        // full WorkManager backoff while the row sat on
                        // the Sync screen as "queued" (the v0.1.75
                        // backup:// pattern, which ate ~10h that way).
                        //
                        // Clear the download state so the row falls off
                        // observeUnarchivedDownloaded and the user can
                        // re-download a good copy, and delete the bad
                        // file rather than orphan bytes that nothing
                        // references and that the server has already
                        // judged incomplete.
                        val attempts = ep.redownloadAttempts + 1
                        DebugLogger.w(
                            "ArchiveWorker",
                            "doWork($resolvedProvider:$resolvedExternalId) — archive rejected the upload " +
                                "(HTTP ${e.code}: ${e.bodyPreview}); attempt $attempts of " +
                                "$MAX_REDOWNLOAD_ATTEMPTS",
                        )
                        episodes.incrementRedownloadAttempts(resolvedProvider, resolvedExternalId)
                        episodes.markUndownloadedByKey(resolvedProvider, resolvedExternalId)
                        runCatching { file.delete() }

                        if (attempts > MAX_REDOWNLOAD_ATTEMPTS) {
                            // The source itself is almost certainly the
                            // problem — it has now produced incomplete
                            // audio this many times. Stop. The row stays
                            // visible as not-downloaded so the user can
                            // retry deliberately; what we refuse to do is
                            // keep re-fetching bytes that never improve.
                            DebugLogger.w(
                                "ArchiveWorker",
                                "doWork($resolvedProvider:$resolvedExternalId) — giving up after " +
                                    "$attempts rejected download(s); not re-fetching again",
                            )
                        } else {
                            // Deliberately pass NO bytes to the recovery
                            // policy. Its MP3 sniff only reads the first
                            // 8 bytes, and a truncated file's header is
                            // perfectly valid — sniffing would return
                            // Skip and we'd never repair the exact case
                            // we're handling. The archive has already
                            // judged these bytes; all we need from the
                            // policy is WHERE to re-fetch from.
                            val allowMetered = settingsAllowMetered()
                            when (decideRecovery(path, ep.downloadUrl, ByteArray(0))) {
                                RecoveryAction.Restore -> {
                                    scheduler.enqueueRestoreByKey(
                                        providerId = resolvedProvider,
                                        externalId = resolvedExternalId,
                                        title = ep.title,
                                        airDate = ep.airDate,
                                        album = ep.albumName,
                                        description = ep.description,
                                        durationSecs = ep.durationMs / 1000,
                                        allowMetered = allowMetered,
                                    )
                                    DebugLogger.i(
                                        "ArchiveWorker",
                                        "doWork($resolvedProvider:$resolvedExternalId) — re-pulling from " +
                                            "backup (attempt $attempts)",
                                    )
                                }
                                RecoveryAction.Redownload -> {
                                    scheduler.enqueueDownload(
                                        resolvedProvider, resolvedExternalId, allowMetered,
                                    )
                                    DebugLogger.i(
                                        "ArchiveWorker",
                                        "doWork($resolvedProvider:$resolvedExternalId) — re-downloading " +
                                            "(attempt $attempts)",
                                    )
                                }
                                is RecoveryAction.Skip -> DebugLogger.w(
                                    "ArchiveWorker",
                                    "doWork($resolvedProvider:$resolvedExternalId) — nothing to re-fetch",
                                )
                            }
                        }
                        Result.failure()
                    }
                    else -> {
                        DebugLogger.w("ArchiveWorker", "doWork($resolvedProvider:$resolvedExternalId) — upload failed, retrying", e)
                        Result.retry()
                    }
                }
            },
        )
    }

    companion object {
        /**
         * How many times the archive may reject this row's audio as
         * incomplete before the app stops re-fetching it.
         *
         * The bound is what makes the repair loop safe. A source that
         * is itself truncated downloads cleanly every time and only
         * fails at the archive's completeness gate, so an unbounded
         * retry would cycle download → reject → download forever.
         *
         * `attempts` counts rejections including the current one, so
         * this permits exactly this many re-fetches and refuses the
         * one after — capping the waste at two extra episodes of
         * bandwidth while still recovering the common case, a single
         * bad transfer.
         */
        const val MAX_REDOWNLOAD_ATTEMPTS = 2

        /** v0.1.72: providerId of the row to archive. Required for new ingests. */
        const val KEY_PROVIDER_ID = "providerId"
        /** v0.1.72: externalId of the row to archive. Required for new ingests. */
        const val KEY_EXTERNAL_ID = "externalId"
    }
}
