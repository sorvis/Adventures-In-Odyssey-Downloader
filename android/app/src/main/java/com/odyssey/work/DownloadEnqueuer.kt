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

/**
 * Same testability seam for archive uploads. ArchiveBackfill uses this
 * (without taking a hard dep on WorkManager-bound WorkScheduler) so the
 * "scan for unarchived files and push" loop is JVM-testable.
 */
interface ArchiveEnqueuer {
    fun enqueueArchive(episodeId: Long, allowMetered: Boolean)
}
