package com.odyssey.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.debug.DebugLogger
import com.odyssey.download.EpisodeDownloader
import com.odyssey.nas.NasClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Prunes oldest local downloads beyond the configured per-provider
 * retention cap. Each registered show keeps its own ring size — pre-
 * v0.1.66 every provider shared one budget, so YSH downloads (which
 * never archive to NAS) squeezed AIO into a single-episode slot once
 * the legacy `retention_count=7` was hit. See
 * `SettingsRepo.retentionCountFor` for the per-provider key fallback.
 *
 * Verify-before-prune (v0.1.67, default ON): for AIO + NAS configured,
 * each candidate row gets a `HEAD /episodes/{id}` round-trip against
 * the NAS before its file gets deleted. A 404/410 response means the
 * backup is missing — the worker leaves the row alone and clears
 * `archivedAt` so the backfill re-uploads on the next pass. Network
 * errors are treated as "skip prune" (don't reset archivedAt — the
 * backup is likely still fine, just unreachable).
 *
 * Pruning shape per provider:
 *  - AIO + NAS configured + verified  → ghost the row (filePath=null,
 *    sourceUrl/downloadUrl="backup://<id>", archivedAt preserved).
 *    Stops DailyCheckWorker from re-ingesting on the next refresh
 *    (the loop fixed in v0.1.63).
 *  - AIO + NAS not configured  → hard-delete the row (no backup to
 *    fall back on, dangling rows are just noise).
 *  - non-AIO (YSH, future)  → hard-delete; the archive-service is
 *    AIO-only by design, so non-AIO rows can never be "safe on the
 *    NAS" the way AIO rows can.
 */
@HiltWorker
class RetentionWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val episodes: EpisodeDao,
    private val downloader: EpisodeDownloader,
    private val settings: SettingsRepo,
    private val nas: NasClient,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val s = settings.flow.first()
        val downloaded = episodes.downloadedOldestFirst()
        if (downloaded.isEmpty()) return Result.success()

        val byProvider: Map<String, List<LocalEpisodeEntity>> = downloaded.groupBy { it.providerId }
        for ((providerId, rows) in byProvider) {
            val cap = settings.retentionCountFor(providerId).first()
            val excess = rows.size - cap
            if (excess <= 0) continue

            // v0.1.72: any provider can ghost when the NAS is configured
            // AND the row is already archived. Pre-v0.1.72 this was
            // AIO-only because the archive-service didn't accept YSH
            // uploads — now it does via the v2 endpoint, so YSH rows
            // that have been archived can be ghosted (and re-streamed
            // from the NAS) the same way AIO does.
            val ghostable = s.nasConfigured
            val candidates = if (ghostable) {
                rows.filter { it.archivedAt != null }
            } else {
                rows
            }
            val toPrune = candidates.take(excess)
            for (ep in toPrune) {
                if (ghostable && s.verifyBackupBeforePrune) {
                    val verified = nas.episodeExistsOnNasByKey(ep.providerId, ep.externalId)
                    when {
                        verified.isFailure -> {
                            DebugLogger.w(
                                "RetentionWorker",
                                "verify network error for ${ep.providerId}:${ep.externalId} — skipping prune (backup likely fine, will retry)",
                                verified.exceptionOrNull(),
                            )
                            continue
                        }
                        verified.getOrNull() == false -> {
                            DebugLogger.w(
                                "RetentionWorker",
                                "NAS missing ${ep.providerId}:${ep.externalId} \"${ep.title}\" — skipping prune, clearing archivedAt so backfill re-uploads",
                            )
                            episodes.markUnarchivedByKey(ep.providerId, ep.externalId)
                            continue
                        }
                        // verified.getOrNull() == true → fall through to prune.
                    }
                }
                ep.filePath?.let { downloader.delete(File(it)) }
                if (ghostable) {
                    episodes.convertToBackupGhost(ep.providerId, ep.externalId)
                } else {
                    episodes.deleteByKey(ep.providerId, ep.externalId)
                }
            }
        }
        return Result.success()
    }
}
