package com.odyssey.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.show.AioOneplaceProvider
import com.odyssey.show.ShowProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Runs once a day. Iterates the registered ShowProviders, asks each
 * one for episodes newer than the last seen, and inserts placeholder
 * rows. Triggers downloads for each new episode (which then chain to
 * archive + retention).
 *
 * Multi-show note (H-lite): the Set<ShowProvider> abstraction lands
 * here today with one entry (AIO). `lastSeen` state in SettingsRepo
 * is still single-valued, AIO-namespaced — non-AIO providers always
 * pull "fresh" until per-provider lastSeen state lands alongside the
 * second concrete provider.
 *
 * Does NOT require a NAS — the app must work standalone.
 */
@HiltWorker
class DailyCheckWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val providers: Set<@JvmSuppressWildcards ShowProvider>,
    private val episodes: EpisodeDao,
    private val settings: SettingsRepo,
    private val scheduler: DownloadEnqueuer,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = runCatching {
        val s = settings.flow.first()
        val maxFetch = if (s.lastSeenEpisodeId == 0L) 7 else 50
        // YSH (and any future show) only ingests once the user has
        // turned it on via the show-switcher dropdown's "Manage shows…"
        // entry (which deep-links to Settings → Shows). AIO defaults
        // to enabled for everyone.
        val enabled = settings.enabledProviders.first()
        val activeProviders = providers.filter { it.id in enabled }

        val fetched = activeProviders.flatMap { provider ->
            val lastSeen = if (provider.id == AioOneplaceProvider.ID) {
                s.lastSeenEpisodeId.takeIf { it != 0L }?.toString()
            } else null
            provider.newSince(lastSeen, maxFetch).map { provider to it }
        }

        if (fetched.isEmpty()) {
            settings.setLastRun(System.currentTimeMillis())
            return@runCatching Result.success()
        }

        // Per-provider dedup by (providerId, externalId). Critically,
        // this MUST cover every provider — non-AIO providers used to
        // bypass the dedup, which meant every daily check re-upserted
        // YSH rows with filePath=null/fileSize=0/downloadedAt=null,
        // wiping out completed (or in-flight) download metadata. User
        // bug report (v0.1.38): "downloads disappear on refresh."
        val perProviderExternalIds = fetched.groupBy({ it.first.id }, { it.second.externalId })
        val existing: Set<Pair<String, String>> = buildSet {
            for ((providerId, ids) in perProviderExternalIds) {
                if (ids.isEmpty()) continue
                episodes.existingKeys(providerId, ids).forEach { add(providerId to it) }
            }
        }

        for ((provider, ep) in fetched) {
            if ((provider.id to ep.externalId) in existing) continue
            episodes.upsert(
                LocalEpisodeEntity(
                    providerId   = provider.id,
                    externalId   = ep.externalId,
                    title        = ep.title,
                    airDate      = ep.airDate,
                    description  = ep.description,
                    sourceUrl    = ep.sourceUrl,
                    downloadUrl  = ep.downloadUrl,
                    filePath     = null,
                    fileSize     = 0L,
                    durationMs   = ep.durationSeconds * 1000,
                    downloadedAt = null,
                    archivedAt   = null,
                    imageUrl     = ep.imageUrl,
                )
            )
            // DownloadEnqueuer is now provider-aware — every newly
            // ingested row queues a download regardless of provider.
            scheduler.enqueueDownload(provider.id, ep.externalId, allowMetered = s.allowMeteredDownloads)
        }

        // lastSeen is AIO-only until per-provider state lands. The first
        // AIO row in `fetched` is newest because OneplaceClient returns
        // newest-first.
        fetched.firstOrNull { (p, _) -> p.id == AioOneplaceProvider.ID }?.let { (_, ep) ->
            settings.setLastSeen(ep.externalId.toLong())
        }
        settings.setLastRun(System.currentTimeMillis())
        Result.success()
    }.getOrElse { Result.retry() }
}
