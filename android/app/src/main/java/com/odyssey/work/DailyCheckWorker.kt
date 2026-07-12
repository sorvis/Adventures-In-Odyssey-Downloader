package com.odyssey.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.debug.DebugLogger
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
    private val playedThroughSweep: PlayedThroughSweep,
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
        DebugLogger.i(
            "DailyCheckWorker",
            "doWork start — lastSeen=${s.lastSeenEpisodeId} maxFetch=$maxFetch " +
                "enabled=$enabled activeProviders=${activeProviders.map { it.id }}",
        )

        val fetched = activeProviders.flatMap { provider ->
            val lastSeen = if (provider.id == AioOneplaceProvider.ID) {
                s.lastSeenEpisodeId.takeIf { it != 0L }?.toString()
            } else null
            val list = provider.newSince(lastSeen, maxFetch)
            DebugLogger.i(
                "DailyCheckWorker",
                "provider '${provider.id}' newSince(lastSeen=$lastSeen) returned ${list.size} episodes" +
                    if (list.isNotEmpty()) {
                        " (newest='${list.first().externalId}:${list.first().title}' " +
                            "airDate='${list.first().airDate}'); " +
                            "summary=[" +
                            list.joinToString(", ") { "${it.externalId}@${it.airDate ?: "null"}" } +
                            "]"
                    } else "",
            )
            list.map { provider to it }
        }

        if (fetched.isEmpty()) {
            settings.setLastRun(System.currentTimeMillis())
            DebugLogger.i("DailyCheckWorker", "doWork done — fetched=0, publishing newCount=0")
            return@runCatching Result.success(workDataOf(KEY_NEW_COUNT to 0))
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

        var newCount = 0
        var promotedCount = 0
        for ((provider, ep) in fetched) {
            val isExisting = (provider.id to ep.externalId) in existing
            if (isExisting) {
                // Backup-mirror ghost promotion: BrowseNasScreen's
                // mirrorServerEpisodes() pre-inserts every server-side
                // episode as a stub row (sourceUrl='backup://<id>',
                // filePath=null) so the Albums tab can show the "☁ on
                // backup" badge. When the daily-check worker later
                // fetches the SAME episode from oneplace.com, the
                // previous `continue` left the row stuck with the
                // placeholder sourceUrl + whatever airDate the NAS
                // server reported (often year-only "2011" which doesn't
                // parse to a real millis). Recent's v0.1.48 filter
                // then hid it as a ghost. Result: newly-aired episodes
                // 266/267/268 invisible to the user even though the
                // worker correctly fetched them (user report
                // 2026-05-13).
                //
                // Fix: refresh the row's source-of-truth metadata
                // (sourceUrl, downloadUrl, airDate, title, description,
                // imageUrl, durationMs) with what the provider just
                // returned. PRESERVE filePath/fileSize/downloadedAt/
                // archivedAt so we don't clobber on-phone state. Only
                // touch rows that look like ghosts (still null filePath
                // and backup:// sourceUrl) to avoid pointless churn.
                val current = episodes.byKey(provider.id, ep.externalId)
                if (current != null && current.filePath == null &&
                    current.sourceUrl.startsWith("backup://")
                ) {
                    episodes.upsert(
                        current.copy(
                            title = ep.title,
                            airDate = ep.airDate,
                            description = ep.description,
                            sourceUrl = ep.sourceUrl,
                            downloadUrl = ep.downloadUrl,
                            durationMs = ep.durationSeconds * 1000,
                            imageUrl = ep.imageUrl,
                            albumName = ep.albumName ?: current.albumName,
                            albumImageUrl = ep.albumImageUrl ?: current.albumImageUrl,
                            albumTrackOrder = ep.albumTrackOrder ?: current.albumTrackOrder,
                            // archivedAt stays — the NAS server still
                            // has the audio, so the album badge stays.
                        ),
                    )
                    promotedCount++
                }
                // NOT a new ingest — don't enqueue download, don't
                // bump newCount. Promotion is silent.
                continue
            }
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
                    albumName       = ep.albumName,
                    albumImageUrl   = ep.albumImageUrl,
                    albumTrackOrder = ep.albumTrackOrder,
                )
            )
            // DownloadEnqueuer is now provider-aware — every newly
            // ingested row queues a download regardless of provider.
            scheduler.enqueueDownload(provider.id, ep.externalId, allowMetered = s.allowMeteredDownloads)
            newCount++
        }

        // lastSeen is AIO-only until per-provider state lands. The first
        // AIO row in `fetched` is newest because OneplaceClient returns
        // newest-first.
        fetched.firstOrNull { (p, _) -> p.id == AioOneplaceProvider.ID }?.let { (_, ep) ->
            settings.setLastSeen(ep.externalId.toLong())
        }
        settings.setLastRun(System.currentTimeMillis())

        // Make room for the new ones: ghost any already-archived row
        // the user has effectively finished (≤ 1 min remaining). Tighter
        // than the 95%-played "✓ played" rule on purpose — short remainders
        // on long episodes can still be worth finishing. Only runs when
        // we actually ingested something, per user spec: "when downloading
        // new episodes ... it should delete that episode to make room."
        val sweptCount = if (newCount > 0) playedThroughSweep.sweep() else 0
        DebugLogger.i(
            "DailyCheckWorker",
            "doWork done — fetched=${fetched.size} " +
                "alreadyExisting=${fetched.size - newCount} " +
                "promotedFromBackupGhost=$promotedCount " +
                "newRows=$newCount " +
                "playedThroughGhosted=$sweptCount, " +
                "publishing outputData[$KEY_NEW_COUNT]=$newCount",
        )
        // Publish the new-row count via WorkInfo.outputData. The UI
        // collects from the SAME WorkInfo flow it already uses for the
        // refresh spinner, so the count and the SUCCEEDED state arrive
        // in one atomic emission — no Room-vs-WorkManager race.
        Result.success(workDataOf(KEY_NEW_COUNT to newCount))
    }.getOrElse { t ->
        DebugLogger.e("DailyCheckWorker", "doWork threw — returning Result.retry", t)
        Result.retry()
    }

    companion object {
        const val KEY_NEW_COUNT = "newCount"
    }
}
