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

    /**
     * Force a fresh enqueue for a row whose previous download work is
     * stuck — typically in WorkManager's exponential backoff after a
     * stream of retry()s. Cancels any existing unique-work entry with
     * the same name FIRST so the regular `KEEP` policy in
     * [enqueueDownload] doesn't no-op, then enqueues. The backoff
     * timer resets and the new code path runs at the next opportunity.
     * Used by DownloadReconciler on app launch to unstick rows where
     * the bytes are already on disk but filePath stayed null.
     *
     * Default implementation just calls [enqueueDownload] — fine for
     * test recorders that don't model WorkManager state, since they
     * have no backoff to break. Production [com.odyssey.work.WorkScheduler]
     * overrides with cancel-then-enqueue semantics.
     */
    fun kickDownload(providerId: String, externalId: String, allowMetered: Boolean) =
        enqueueDownload(providerId, externalId, allowMetered)
}

/**
 * Same testability seam for archive uploads. ArchiveBackfill uses this
 * (without taking a hard dep on WorkManager-bound WorkScheduler) so the
 * "scan for unarchived files and push" loop is JVM-testable.
 */
interface ArchiveEnqueuer {
    fun enqueueArchive(episodeId: Long, allowMetered: Boolean)

    /**
     * Force a fresh enqueue for an upload whose previous work is stuck
     * in WorkManager's exponential backoff (typically the case after
     * the NAS was unreachable for a while). Cancels the existing
     * unique-work entry FIRST so the KEEP policy in [enqueueArchive]
     * doesn't no-op, then enqueues. Used by the Sync screen's
     * pull-to-refresh to drain the queue on-demand when the user
     * reconnects to the home LAN.
     *
     * Default implementation just calls [enqueueArchive] — fine for
     * test recorders that don't model WorkManager backoff. Production
     * WorkScheduler overrides with cancel-then-enqueue semantics.
     */
    fun kickArchive(episodeId: Long, allowMetered: Boolean) =
        enqueueArchive(episodeId, allowMetered)
}
