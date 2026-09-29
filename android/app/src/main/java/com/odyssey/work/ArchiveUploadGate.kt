package com.odyssey.work

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Caps how many episode uploads may be in flight at once, process-wide.
 *
 * WorkManager happily starts every enqueued [ArchiveEpisodeWorker] more
 * or less at once — CoroutineWorkers suspend rather than hold threads,
 * so nothing throttles them. On 2026-09-28 that meant ~35 simultaneous
 * POSTs of 45–57 MB each. Two consequences:
 *
 *  - Off-network, all 35 burn a 15s connect timeout in parallel and all
 *    fail together, so every job's runAttemptCount advances in lockstep
 *    and the whole queue lands in the same (long) backoff bucket.
 *  - On-network, they contend for the same uplink, so nothing finishes
 *    quickly and a stall affects everything rather than one upload.
 *
 * Draining a few at a time is both gentler and — because failures stop
 * being simultaneous — much less likely to park the entire backlog.
 *
 * Note the wait happens inside the worker, so a queued upload still
 * counts against WorkManager's ~10-minute execution window. With a
 * small permit count and LAN-speed uploads that's ample; if uploads
 * ever get slow enough for the tail to time out, they return retry and
 * are picked up later rather than being lost.
 */
object ArchiveUploadGate {

    /** Concurrent uploads allowed. Small on purpose — see class doc. */
    const val MAX_CONCURRENT_UPLOADS = 3

    private val gate = Semaphore(MAX_CONCURRENT_UPLOADS)

    /** Visible for tests/diagnostics. */
    val availablePermits: Int get() = gate.availablePermits

    /** Run [block] holding one upload slot, suspending until one frees. */
    suspend fun <T> withSlot(block: suspend () -> T): T = gate.withPermit { block() }
}
