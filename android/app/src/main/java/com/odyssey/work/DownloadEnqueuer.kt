package com.odyssey.work

/**
 * Slice of WorkScheduler that DailyCheckWorker uses — extracted so
 * tests can substitute a fake recorder without standing up a real
 * WorkManager. WorkScheduler implements this in production; tests
 * pass an in-memory recorder.
 *
 * Same pattern as EpisodePlayer / PlayerController.
 */
interface DownloadEnqueuer {
    fun enqueueDownload(episodeId: Long, allowMetered: Boolean)
}
