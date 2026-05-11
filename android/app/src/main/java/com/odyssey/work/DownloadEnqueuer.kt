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
    /**
     * Schedule a download for the given (providerId, externalId) row.
     * AIO episodes pass `providerId="aio"` + the oneplace CMS id
     * stringified; YSH episodes pass `providerId="ysh"` +
     * `"ysh-sku-<n>"`. The work-unique key encodes both so two shows
     * with overlapping numeric externalIds don't collapse to the same
     * unique-work entry.
     */
    fun enqueueDownload(providerId: String, externalId: String, allowMetered: Boolean)

    /** Legacy AIO-only convenience. Delegates to the provider-aware
     *  overload with `providerId="aio"`. Kept so PlaybackRecovery (and
     *  any other Long-keyed call site) doesn't need to plumb provider
     *  through right now. */
    fun enqueueDownload(episodeId: Long, allowMetered: Boolean) =
        enqueueDownload("aio", episodeId.toString(), allowMetered)
}

/**
 * Same testability seam for archive uploads. ArchiveBackfill uses this
 * (without taking a hard dep on WorkManager-bound WorkScheduler) so the
 * "scan for unarchived files and push" loop is JVM-testable.
 */
interface ArchiveEnqueuer {
    fun enqueueArchive(episodeId: Long, allowMetered: Boolean)
}
