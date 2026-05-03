package com.odyssey.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.scrape.OneplaceClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Runs once a day. Asks oneplace for the latest episode ID, fetches everything
 * newer than the last-seen ID, and inserts placeholder rows. Triggers downloads
 * for each new episode (which then chain to archive + retention).
 *
 * Does NOT require a NAS — the app must work standalone.
 */
@HiltWorker
class DailyCheckWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val oneplace: OneplaceClient,
    private val episodes: EpisodeDao,
    private val settings: SettingsRepo,
    private val scheduler: WorkScheduler,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = runCatching {
        val s = settings.flow.first()
        val newest = oneplace.newSince(
            lastSeen = s.lastSeenEpisodeId,
            maxFetch = if (s.lastSeenEpisodeId == 0L) 7 else 50,
        )
        if (newest.isEmpty()) {
            settings.setLastRun(System.currentTimeMillis())
            return@runCatching Result.success()
        }

        val existingIds = episodes.existingIds(newest.map { it.episodeId }).toSet()
        for (ep in newest) {
            if (ep.episodeId in existingIds) continue
            episodes.upsert(
                LocalEpisodeEntity(
                    episodeId    = ep.episodeId,
                    title        = ep.title,
                    airDate      = ep.airDate,
                    description  = ep.description ?: ep.descriptionHtml,
                    sourceUrl    = ep.url,
                    downloadUrl  = ep.downloadFileUrl,
                    filePath     = null,
                    fileSize     = 0L,
                    durationMs   = ep.durationSeconds * 1000,
                    downloadedAt = null,
                    archivedAt   = null,
                )
            )
            scheduler.enqueueDownload(ep.episodeId)
        }
        settings.setLastSeen(newest.first().episodeId) // newest-first
        settings.setLastRun(System.currentTimeMillis())
        Result.success()
    }.getOrElse { Result.retry() }
}
