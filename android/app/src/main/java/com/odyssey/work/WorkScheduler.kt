package com.odyssey.work

import android.content.Context
import androidx.work.*
import com.odyssey.debug.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkScheduler @Inject constructor(@ApplicationContext private val ctx: Context) :
    DownloadEnqueuer, ArchiveEnqueuer {

    private val wm get() = WorkManager.getInstance(ctx)

    fun ensureDailyCheck() {
        val req = PeriodicWorkRequestBuilder<DailyCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(
            "odyssey-daily-check",
            ExistingPeriodicWorkPolicy.KEEP,
            req,
        )
    }

    /**
     * Weekly YSH album-catalog refresh. Small JSON, ~5 pages today;
     * weeks of staleness don't break provider behavior because
     * YshOneplaceProvider falls back to the on-disk cache (or the
     * unmatched-titles flow when nothing is cached yet).
     */
    fun ensureYshCatalogRefresh() {
        val req = PeriodicWorkRequestBuilder<YshCatalogRefreshWorker>(7, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()
        wm.enqueueUniquePeriodicWork(
            "odyssey-ysh-catalog-refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            req,
        )
    }

    /**
     * Fire a one-shot YSH catalog refresh — used right after a fresh
     * install so the catalog populates without waiting up to a week
     * for the periodic worker, and from Settings → "Refresh now".
     */
    fun runYshCatalogRefreshNow() {
        val req = OneTimeWorkRequestBuilder<YshCatalogRefreshWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        wm.enqueueUniqueWork(
            "odyssey-ysh-catalog-refresh-now",
            ExistingWorkPolicy.REPLACE,
            req,
        )
    }

    fun runDailyCheckNow() {
        val req = OneTimeWorkRequestBuilder<DailyCheckWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        DebugLogger.i(
            "WorkScheduler",
            "runDailyCheckNow — enqueueUniqueWork($CHECK_NOW_WORK, REPLACE, id=${req.id})",
        )
        wm.enqueueUniqueWork(CHECK_NOW_WORK, ExistingWorkPolicy.REPLACE, req)
    }

    /**
     * Combined "what is the Check-now worker doing right now" state —
     * `active` drives the pull-to-refresh spinner, `newCount` drives
     * the "Refresh complete — N new" snackbar. Both fields are
     * projected from a SINGLE WorkInfo emission, so the UI cannot
     * observe one without the other: no race between the spinner
     * flipping false and the new-row count being readable.
     *
     * Lazy so that constructing WorkScheduler in unit tests doesn't
     * trip WorkManager's "not initialized" check — tests that don't
     * touch this property never hit WorkManager.getInstance().
     */
    val dailyCheckSnapshot: Flow<DailyCheckSnapshot> by lazy {
        wm.getWorkInfosForUniqueWorkFlow(CHECK_NOW_WORK)
            .onEach { infos ->
                // v0.1.45 diagnostic: print the raw WorkInfo set the
                // projection is reasoning over so we can tell whether
                // the snackbar's "no new episodes" is genuine (worker
                // really published 0) or a multi-history pick error
                // (more than one SUCCEEDED entry; firstOrNull picked
                // an old/empty one). One line per emission.
                val states = infos.groupingBy { it.state.name }.eachCount()
                val succeededCounts = infos
                    .filter { it.state == androidx.work.WorkInfo.State.SUCCEEDED }
                    .map { it.outputData.getInt(DailyCheckWorker.KEY_NEW_COUNT, -1) }
                DebugLogger.i(
                    "WorkScheduler",
                    "dailyCheckSnapshot emit — total=${infos.size} states=$states " +
                        "succeededNewCounts=$succeededCounts",
                )
            }
            .map { infos ->
                val active = infos.any { !it.state.isFinished }
                // Read the count from the most recent SUCCEEDED entry.
                // Other states (ENQUEUED, RUNNING) carry no output;
                // they hold the previous successful count steady so the
                // UI doesn't flash a zero while a refresh is in flight.
                //
                // NOTE: WorkManager keeps SUCCEEDED entries in history
                // after REPLACE, so `infos` can contain MULTIPLE
                // succeeded entries with no documented ordering.
                // firstOrNull here is the suspected v0.1.44 production
                // bug — v0.1.45 instruments it; the fix lands after
                // we read live logs.
                val succeeded = infos.firstOrNull {
                    it.state == androidx.work.WorkInfo.State.SUCCEEDED
                }
                val newCount = succeeded?.outputData
                    ?.getInt(DailyCheckWorker.KEY_NEW_COUNT, 0) ?: 0
                DailyCheckSnapshot(active = active, newCount = newCount)
            }
            .distinctUntilChanged()
    }

    companion object {
        /**
         * Unique work name for the manually-triggered "Check now" run.
         * Exposed (visible-for-testing) so race-invariant tests can
         * enqueue stub workers under the same name the production code
         * watches via `dailyCheckSnapshot` — the snapshot binds to this
         * exact string, so the test's stub correctly drives it.
         */
        @androidx.annotation.VisibleForTesting
        const val CHECK_NOW_WORK = "odyssey-check-now"
    }

    override fun enqueueDownload(providerId: String, externalId: String, allowMetered: Boolean) {
        wm.enqueueUniqueWork(
            downloadWorkName(providerId, externalId),
            ExistingWorkPolicy.KEEP,
            buildDownloadRequest(providerId, externalId, allowMetered),
        )
    }

    override fun kickDownload(providerId: String, externalId: String, allowMetered: Boolean) {
        // Cancel first so the subsequent enqueue isn't no-op'd by the
        // unique-name + KEEP policy. cancelUniqueWork is idempotent
        // and a no-op if nothing's enqueued.
        val name = downloadWorkName(providerId, externalId)
        wm.cancelUniqueWork(name)
        wm.enqueueUniqueWork(
            name,
            ExistingWorkPolicy.KEEP,
            buildDownloadRequest(providerId, externalId, allowMetered),
        )
    }

    private fun buildDownloadRequest(
        providerId: String,
        externalId: String,
        allowMetered: Boolean,
    ): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<DownloadEpisodeWorker>()
            .setInputData(
                workDataOf(
                    DownloadEpisodeWorker.KEY_PROVIDER_ID to providerId,
                    DownloadEpisodeWorker.KEY_EXTERNAL_ID to externalId,
                    // Back-compat with the AIO-only Long input — kept so a
                    // pending OneTimeWorkRequest enqueued by a pre-update
                    // build still routes correctly when WorkManager replays
                    // it. New code paths read providerId+externalId.
                    DownloadEpisodeWorker.KEY_EPISODE_ID to (externalId.toLongOrNull() ?: -1L),
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()

    private fun downloadWorkName(providerId: String, externalId: String): String =
        "download-$providerId-$externalId"

    override fun enqueueArchive(episodeId: Long, allowMetered: Boolean) {
        wm.enqueueUniqueWork(
            archiveWorkName(episodeId),
            ExistingWorkPolicy.KEEP,
            buildArchiveRequest(episodeId, allowMetered),
        )
    }

    override fun kickArchive(episodeId: Long, allowMetered: Boolean) {
        // Cancel first so the subsequent enqueue isn't no-op'd by the
        // unique-name + KEEP policy. cancelUniqueWork is idempotent
        // and a no-op if nothing's enqueued. Mirrors kickDownload.
        val name = archiveWorkName(episodeId)
        wm.cancelUniqueWork(name)
        wm.enqueueUniqueWork(
            name,
            ExistingWorkPolicy.KEEP,
            buildArchiveRequest(episodeId, allowMetered),
        )
    }

    private fun buildArchiveRequest(episodeId: Long, allowMetered: Boolean): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<ArchiveEpisodeWorker>()
            .setInputData(workDataOf(DownloadEpisodeWorker.KEY_EPISODE_ID to episodeId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

    private fun archiveWorkName(episodeId: Long): String = "archive-$episodeId"

    fun enqueueRetention() {
        val req = OneTimeWorkRequestBuilder<RetentionWorker>().build()
        wm.enqueueUniqueWork("retention", ExistingWorkPolicy.REPLACE, req)
    }

    /**
     * Pin a server-side episode onto the phone for offline play. Same
     * unique-work key shape as enqueueDownload (`restore-<id>`) so a
     * second pin tap while one is pending is a no-op.
     */
    fun enqueueRestore(
        episodeId: Long,
        title: String,
        airDate: String?,
        album: String?,
        description: String?,
        durationSecs: Long,
        allowMetered: Boolean,
    ) {
        val req = OneTimeWorkRequestBuilder<RestoreEpisodeWorker>()
            .setInputData(
                workDataOf(
                    RestoreEpisodeWorker.KEY_EPISODE_ID to episodeId,
                    RestoreEpisodeWorker.KEY_TITLE to title,
                    RestoreEpisodeWorker.KEY_AIR_DATE to airDate,
                    RestoreEpisodeWorker.KEY_ALBUM to album,
                    RestoreEpisodeWorker.KEY_DESCRIPTION to description,
                    RestoreEpisodeWorker.KEY_DURATION_SECS to durationSecs,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork("restore-$episodeId", ExistingWorkPolicy.KEEP, req)
    }
}

/**
 * Atomic snapshot of "what is the Check-now worker doing." `active`
 * drives the pull-to-refresh spinner; `newCount` is the most recent
 * SUCCEEDED worker's output and survives across the next refresh's
 * pending/running state (keeps the prior count visible rather than
 * flashing 0 while the new run buffers).
 *
 * The two fields ARRIVE TOGETHER because they're projected from a
 * single WorkInfo emission — no Compose-state race possible.
 */
data class DailyCheckSnapshot(val active: Boolean, val newCount: Int)
