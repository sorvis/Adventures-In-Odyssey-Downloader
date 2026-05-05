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

    override fun enqueueDownload(episodeId: Long, allowMetered: Boolean) {
        val req = OneTimeWorkRequestBuilder<DownloadEpisodeWorker>()
            .setInputData(workDataOf(DownloadEpisodeWorker.KEY_EPISODE_ID to episodeId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork("download-$episodeId", ExistingWorkPolicy.KEEP, req)
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
}
