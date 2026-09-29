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
    /**
     * Provider-aware archive enqueue (v0.1.72). The work-unique key
     * encodes both providerId and externalId so YSH archives don't
     * collide with AIO archives that happen to share a numeric range.
     */
    fun enqueueArchiveByKey(providerId: String, externalId: String, allowMetered: Boolean)

    /**
     * Legacy AIO-only convenience. Delegates to the v2 overload with
     * `providerId="aio"`. Kept so callers that already have a Long
     * episodeId (DownloadEpisodeWorker, AlbumDetailVm's "re-archive
     * this episode" tap) don't need to plumb providerId through.
     */
    fun enqueueArchive(episodeId: Long, allowMetered: Boolean) =
        enqueueArchiveByKey("aio", episodeId.toString(), allowMetered)

    /**
     * Force a fresh enqueue for an upload whose previous work is stuck
     * in WorkManager's exponential backoff (typically the case after
     * the NAS was unreachable for a while). Cancels the existing
     * unique-work entry FIRST so the KEEP policy in [enqueueArchive]
     * doesn't no-op, then enqueues. Used by the Sync screen's
     * pull-to-refresh to drain the queue on-demand when the user
     * reconnects to the home LAN.
     */
    fun kickArchive(episodeId: Long, allowMetered: Boolean) =
        enqueueArchive(episodeId, allowMetered)

    /**
     * Provider-aware [kickArchive]. The legacy Long-keyed overload above
     * only reaches AIO rows, so a YSH backlog stuck in backoff had no
     * way to be forced — the manual "Push N to backup" button routed
     * through [enqueueArchiveByKey], whose KEEP policy silently discards
     * the request when a backing-off entry already exists under the same
     * unique name. The button logged "enqueuing N archive jobs" and did
     * nothing (observed 2026-09-28: 39 jobs "enqueued", zero
     * ArchiveWorker runs in the following 90 minutes).
     *
     * Implementations MUST cancel the existing unique work before
     * enqueueing. The default here is the no-cancel fallback used by
     * test fakes; [WorkScheduler] overrides it.
     */
    fun kickArchiveByKey(providerId: String, externalId: String, allowMetered: Boolean) =
        enqueueArchiveByKey(providerId, externalId, allowMetered)

    /**
     * Cancel any pending archive work for [episodeId]. Called by
     * DownloadReconciler.cleanupCrossShowContamination when it deletes
     * a row so the corresponding WorkManager entry doesn't fire later.
     */
    fun cancelArchive(episodeId: Long) = Unit
}

/**
 * Testability seam for NAS restores, mirroring [DownloadEnqueuer].
 *
 * PlaybackRecovery uses it to re-pull a corrupt backup-restored file
 * from the archive service instead of the CDN: rows written by
 * RestoreEpisodeWorker carry `downloadUrl = "backup://<id>"`, which
 * DownloadEpisodeWorker fail-fasts on by design (v0.1.75), so routing
 * those through the download path would delete the bad file and never
 * replace it.
 */
interface RestoreEnqueuer {
    /**
     * Pull `(providerId, externalId)` from the backup service onto the
     * phone. The metadata arguments seed the local row when the phone
     * has never seen the episode; for a row that already exists the
     * worker keeps the values it already had.
     */
    fun enqueueRestoreByKey(
        providerId: String,
        externalId: String,
        title: String,
        airDate: String?,
        album: String?,
        description: String?,
        durationSecs: Long,
        allowMetered: Boolean,
    )
}
