package com.odyssey.work

import android.content.Context
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
        wm.enqueueUniqueWork(CHECK_NOW_WORK, ExistingWorkPolicy.REPLACE, req)
    }

    /**
     * `true` while a Check-now (or daily) check is enqueued or actively
     * running, `false` once it terminates. Drives the pull-to-refresh
     * spinner on the Recent screen so the user can SEE the worker is
     * active without watching adb.
     *
     * Lazy so that constructing WorkScheduler in unit tests doesn't
     * trip WorkManager's "not initialized" check — tests that don't
     * touch this property never hit WorkManager.getInstance().
     */
    val dailyCheckActive: Flow<Boolean> by lazy {
        wm.getWorkInfosForUniqueWorkFlow(CHECK_NOW_WORK)
            .map { infos -> infos.any { !it.state.isFinished } }
    }

    private companion object {
        const val CHECK_NOW_WORK = "odyssey-check-now"
    }

    override fun enqueueDownload(providerId: String, externalId: String, allowMetered: Boolean) {
        val req = OneTimeWorkRequestBuilder<DownloadEpisodeWorker>()
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
        wm.enqueueUniqueWork("download-$providerId-$externalId", ExistingWorkPolicy.KEEP, req)
    }

    override fun enqueueArchive(episodeId: Long, allowMetered: Boolean) {
        val req = OneTimeWorkRequestBuilder<ArchiveEpisodeWorker>()
            .setInputData(workDataOf(DownloadEpisodeWorker.KEY_EPISODE_ID to episodeId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork("archive-$episodeId", ExistingWorkPolicy.KEEP, req)
    }

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
