package com.odyssey.work

import kotlinx.coroutines.sync.Semaphore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide cap on how many [DownloadEpisodeWorker] coroutines are
 * actually moving bytes at once.
 *
 * Background: without it, the launch-time [com.odyssey.download.DownloadReconciler]
 * re-kicks every stuck row in parallel. On 2026-05-31 a fresh install
 * with 13 wedged YSH partials opened 13 simultaneous HTTPS streams
 * across two CDNs (S3 + zcast.swncdn.com); the OS killed every socket
 * at the same millisecond and twelve workers logged
 * `SocketException: Software caused connection abort` at 12:59:07.543–549
 * — symptom report was "fresh install can't download anything." Each
 * worker then re-entered WorkManager exponential backoff, so the user
 * never recovered without manual intervention.
 *
 * Cap = 2: enough to overlap connect/SSL handshake of N+1 with byte
 * transfer of N on a single fat WAN, few enough to stay under consumer-
 * router NAT-table burst guards and per-IP CDN connection budgets.
 *
 * WorkManager still enqueues every worker independently — the
 * semaphore just gates the actual byte-moving inside doWork(), so
 * blocked workers sit cheap (no socket open, no read pending) instead
 * of competing for bandwidth and connection slots.
 */
@Singleton
class DownloadConcurrencyGate @Inject constructor() {
    internal val semaphore = Semaphore(MAX_CONCURRENT)

    companion object {
        /**
         * Max simultaneous in-flight downloads. Empirically two parallel
         * keeps a ~50 Mbps fiber link saturated for ~5 MB MP3s without
         * tripping the synchronized-socket-abort pattern observed in the
         * 13-parallel case.
         */
        const val MAX_CONCURRENT = 2
    }
}
