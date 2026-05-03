package com.odyssey.work

import android.content.Context
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkScheduler @Inject constructor(@ApplicationContext private val ctx: Context) {

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
        wm.enqueueUniqueWork("odyssey-check-now", ExistingWorkPolicy.REPLACE, req)
    }

    fun enqueueDownload(episodeId: Long) {
        val req = OneTimeWorkRequestBuilder<DownloadEpisodeWorker>()
            .setInputData(workDataOf(DownloadEpisodeWorker.KEY_EPISODE_ID to episodeId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork("download-$episodeId", ExistingWorkPolicy.KEEP, req)
    }

    fun enqueueArchive(episodeId: Long) {
        val req = OneTimeWorkRequestBuilder<ArchiveEpisodeWorker>()
            .setInputData(workDataOf(DownloadEpisodeWorker.KEY_EPISODE_ID to episodeId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
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
