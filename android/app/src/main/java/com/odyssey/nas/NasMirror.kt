package com.odyssey.nas

import com.odyssey.catalog.AioCatalogRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.debug.DebugLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls the NAS catalog into the local DB as backup-mirror ghost rows.
 *
 * Each NAS-side episode becomes a `LocalEpisodeEntity` with
 * `sourceUrl`/`downloadUrl` = `"backup://<id>"`, `filePath = null`,
 * and `archivedAt` set — exactly the shape BrowseNasScreen and
 * RetentionWorker already produce, so Albums' "☁ on backup" badge,
 * Recent's ghost filter, and DailyCheckWorker's ghost-promotion path
 * all keep working unchanged.
 *
 * Why extract this out of BrowseVm: pre-v0.1.67, mirror only ran when
 * the user opened the Sync tab. Users with full NAS libraries (>300
 * episodes) but only a handful of recent re-downloads in local DB saw
 * "0 on backup" everywhere, and there was no obvious in-app action to
 * fix it. Now OdysseyApp.onCreate kicks an opportunistic mirror at
 * launch, and the Library tab's pull-to-refresh re-runs it on demand.
 *
 * AIO-only: the archive-service serves AIO files today (per design
 * step 11b). YSH catalog comes from yourstoryhour.org, not the NAS,
 * so mirror-from-NAS doesn't apply to it.
 */
@Singleton
class NasMirror @Inject constructor(
    private val nas: NasClient,
    private val episodes: EpisodeDao,
    private val aioCatalog: AioCatalogRepo,
) {
    /**
     * Returns the number of rows inserted as ghosts (existing rows are
     * just touched to set `archivedAt`, which doesn't count). Suspends
     * — caller decides whether to log, surface, or ignore the result.
     * NasClient errors propagate as a failed `Result`.
     */
    suspend fun run(): Result<MirrorOutcome> = runCatching {
        val eps = nas.listAllEpisodes().getOrThrow()
        val now = System.currentTimeMillis()
        var inserted = 0
        var touched = 0
        var skipped = 0
        for (ep in eps) {
            // Non-AIO leaks (Sekulow etc. that pre-v0.1.59 ingest
            // missed) — title catalog-match is the source-of-truth.
            // Without this filter, mirror keeps re-creating rows that
            // DownloadReconciler.cleanupCrossShowContamination would
            // then re-clean on next launch — infinite ping-pong.
            if (aioCatalog.match(ep.title) == null) {
                skipped++
                continue
            }
            val existing = episodes.byId(ep.episode_id)
            if (existing != null) {
                // Don't clobber filePath/title — just ensure archivedAt
                // is set so the Album view's badge lights up.
                if (existing.archivedAt == null) {
                    episodes.markArchived(ep.episode_id, now)
                    touched++
                }
                continue
            }
            episodes.upsert(
                LocalEpisodeEntity(
                    providerId = "aio",
                    externalId = ep.episode_id.toString(),
                    title = ep.title,
                    airDate = ep.air_date,
                    description = ep.description,
                    sourceUrl = "backup://${ep.episode_id}",
                    downloadUrl = "backup://${ep.episode_id}",
                    filePath = null,
                    fileSize = ep.file_size,
                    durationMs = (ep.duration_secs ?: 0L) * 1000,
                    downloadedAt = null,
                    archivedAt = now,
                ),
            )
            inserted++
        }
        DebugLogger.i(
            "NasMirror",
            "run() — fetched=${eps.size} inserted=$inserted touched=$touched skipped(non-AIO)=$skipped",
        )
        MirrorOutcome(fetched = eps.size, inserted = inserted, touched = touched, skipped = skipped)
    }
}

data class MirrorOutcome(
    val fetched: Int,
    val inserted: Int,
    val touched: Int,
    val skipped: Int,
)
