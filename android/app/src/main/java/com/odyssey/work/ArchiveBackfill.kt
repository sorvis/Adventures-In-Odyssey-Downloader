package com.odyssey.work

import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.debug.DebugLogger
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes every locally-downloaded episode that hasn't yet been archived
 * to the backup service. Triggered by Settings → Backup save (so a user
 * who already has 200 downloaded episodes when they first connect their
 * server gets all of them backfilled in one go) and by the manual
 * "Push N waiting" button on the same screen.
 *
 * Idempotency comes from three layers:
 *   1. The DAO query filters `archivedAt IS NULL`, so already-backed-up
 *      rows aren't even candidates.
 *   2. WorkManager dedups by unique work name `archive-$episodeId` (KEEP
 *      policy in WorkScheduler.enqueueArchive), so calling this while a
 *      prior archive job is pending is a no-op.
 *   3. ArchiveEpisodeWorker re-checks `ep.archivedAt != null` on entry,
 *      so even if dedup misses, the upload doesn't double-fire.
 *
 * Returns the count of jobs enqueued so the UI can show "Pushed N
 * episodes to backup."
 */
@Singleton
class ArchiveBackfill @Inject constructor(
    private val episodes: EpisodeDao,
    private val scheduler: ArchiveEnqueuer,
    private val settings: SettingsRepo,
) {
    /**
     * @param force when true, cancel each episode's existing WorkManager
     *   entry before re-enqueueing so the upload runs NOW instead of
     *   waiting out its exponential backoff.
     *
     *   Idempotency layer 2 in the class doc (unique-name + KEEP dedup)
     *   turns into a liability once uploads have started failing: every
     *   pending episode already has a backing-off entry, so a plain
     *   enqueue is discarded and the queue never moves. Pass force=true
     *   for anything the user explicitly asked for (the "Push N to
     *   backup" button) or for a signal that the previous failure cause
     *   is gone (rejoining a network). Leave it false for speculative
     *   background sweeps, where respecting backoff is the point.
     */
    suspend fun run(force: Boolean = false): Int {
        val pending = episodes.unarchivedDownloaded()
        if (pending.isEmpty()) {
            DebugLogger.d("ArchiveBackfill", "no unarchived files — skip")
            return 0
        }
        val allowMetered = settings.flow.first().allowMeteredDownloads
        DebugLogger.i(
            "ArchiveBackfill",
            "enqueuing ${pending.size} archive jobs (allowMetered=$allowMetered force=$force)",
        )
        for (ep in pending) {
            // v0.1.72: route by (providerId, externalId) so YSH rows
            // don't hash-fall-back through the legacy Long path.
            if (force) {
                scheduler.kickArchiveByKey(ep.providerId, ep.externalId, allowMetered)
            } else {
                scheduler.enqueueArchiveByKey(ep.providerId, ep.externalId, allowMetered)
            }
        }
        return pending.size
    }
}
