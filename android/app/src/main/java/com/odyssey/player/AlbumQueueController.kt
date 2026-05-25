package com.odyssey.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One entry in an album-playback queue. (providerId, externalId) is the
 * canonical key — episodeId is the LocalEpisodeEntity-computed Long
 * (numeric for AIO, externalId.hashCode() for YSH) and is what
 * PlayerController writes into MediaItem.mediaId, so it's what we get
 * back from the player when a track ends.
 */
data class AlbumQueueEntry(
    val episodeId: Long,
    val providerId: String,
    val externalId: String,
)

/**
 * Singleton that holds the ordered episode list for the currently
 * playing album. Set by AlbumDetailVm (AIO + YSH) on a play tap;
 * consumed by AutoAdvanceController when the player fires STATE_ENDED.
 *
 * Singleton lifetime (not VM-scoped) so the queue survives the user
 * navigating away from AlbumDetailScreen mid-playback — autoplay keeps
 * advancing even with the screen torn down.
 *
 * Queue clears automatically when [nextAfter] runs off the end. Callers
 * can also clear() explicitly when starting standalone playback (Recent
 * tab tap on a single episode, etc.) so an old album queue doesn't
 * resurface.
 */
@Singleton
class AlbumQueueController @Inject constructor() {

    private val _queue = MutableStateFlow<List<AlbumQueueEntry>>(emptyList())
    val queue: StateFlow<List<AlbumQueueEntry>> = _queue.asStateFlow()

    /**
     * Replace the queue with [entries]. Caller is responsible for the
     * order (catalog-order for AIO, orderIndex for YSH) and for
     * including only entries that are actually playable (on-disk or
     * resolvable via NAS/CDN).
     */
    fun setQueue(entries: List<AlbumQueueEntry>) {
        _queue.value = entries
    }

    /**
     * After [currentEpisodeId] finishes, what plays next? Returns null
     * when:
     *   - the queue is empty, or
     *   - [currentEpisodeId] isn't in the queue (user switched albums
     *     or played a standalone track — don't auto-advance into the
     *     stale queue), or
     *   - [currentEpisodeId] is the last entry (end of album).
     *
     * Side effect: clears the queue on end-of-album so a subsequent
     * STATE_ENDED on a non-album track doesn't accidentally re-enter
     * an old queue.
     */
    fun nextAfter(currentEpisodeId: Long): AlbumQueueEntry? {
        val list = _queue.value
        if (list.isEmpty()) return null
        val idx = list.indexOfFirst { it.episodeId == currentEpisodeId }
        if (idx < 0) return null
        val nextIdx = idx + 1
        if (nextIdx >= list.size) {
            _queue.value = emptyList()
            return null
        }
        return list[nextIdx]
    }

    fun clear() {
        _queue.value = emptyList()
    }
}
