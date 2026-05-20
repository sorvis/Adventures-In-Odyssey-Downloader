package com.odyssey.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.download.EpisodeDownloader
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
 * Pruning shape per provider:
 *  - AIO + NAS configured  → ghost the row (filePath=null, sourceUrl=
 *    "backup://<id>", archivedAt preserved). Stops DailyCheckWorker
 *    from re-ingesting the episode as "new" on the next refresh
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

            // Only AIO rows have a NAS backup to fall back on (and even
            // then only when the NAS is configured AND the row is
            // already archived). Everything else is delete-from-DB on
            // prune.
            val ghostable = s.nasConfigured && providerId == "aio"
            val candidates = if (ghostable) {
                rows.filter { it.archivedAt != null }
            } else {
                rows
            }
            val toPrune = candidates.take(excess)
            for (ep in toPrune) {
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
