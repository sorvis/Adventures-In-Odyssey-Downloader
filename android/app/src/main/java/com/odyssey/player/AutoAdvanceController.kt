package com.odyssey.player

import com.odyssey.data.local.EpisodeDao
import com.odyssey.debug.DebugLogger
import com.odyssey.nas.NasClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Glue between the player's STATE_ENDED event and the
 * [AlbumQueueController] queue: pops the next entry, looks up the row
 * in the DB, and dispatches through the same local / backup:// / stream
 * decision tree the album-detail VMs use when the user taps play
 * directly.
 *
 * Why a separate singleton instead of stuffing this into PlayerController:
 *   - PlayerController is already 400+ LOC and Media3-coupled. Auto-advance
 *     is a pure routing concern (no controller calls beyond the final
 *     player.playLocal/playStream) — keeping it out of the listener body
 *     keeps PlayerController focused on transport.
 *   - The dependencies for routing (EpisodeDao + NasClient) belong here,
 *     not on PlayerController; injecting them there would entangle the
 *     transport layer with provider-aware NAS lookup.
 */
@Singleton
class AutoAdvanceController @Inject constructor(
    private val queue: AlbumQueueController,
    private val episodes: EpisodeDao,
    private val nas: NasClient,
    private val player: EpisodePlayer,
) {
    /**
     * Called by PlayerController on STATE_ENDED. If the queue knows
     * what plays next after [endedEpisodeId], load and start it.
     *
     * Returns true if auto-advance fired (a next track was queued),
     * false if the queue was empty / current track wasn't in the
     * queue / we hit end-of-album. Caller doesn't need to act on the
     * return — exposed for tests + diagnostics.
     */
    suspend fun onTrackEnded(endedEpisodeId: Long): Boolean {
        val next = queue.nextAfter(endedEpisodeId) ?: return false
        val row = episodes.byKey(next.providerId, next.externalId)
        if (row == null) {
            DebugLogger.w(
                TAG,
                "queue had ${next.providerId}:${next.externalId} but DB has no row — skipping",
            )
            return false
        }
        DebugLogger.i(
            TAG,
            "auto-advance: $endedEpisodeId → ${row.providerId}:${row.externalId} \"${row.title}\"",
        )
        when {
            row.filePath != null -> player.playLocal(row)
            row.downloadUrl.startsWith(BACKUP_URL_PREFIX) -> {
                val audio = nas.audioUrlByKey(row.providerId, row.externalId).getOrNull()
                if (audio == null) {
                    DebugLogger.w(
                        TAG,
                        "auto-advance: backup:// row but NAS not configured — stopping queue",
                    )
                    return false
                }
                player.playStream(
                    episodeId = row.episodeId,
                    streamUrl = audio.url,
                    title = row.title,
                    artworkUrl = row.imageUrl,
                    providerId = row.providerId,
                    description = row.description,
                )
            }
            else -> {
                player.playStream(
                    episodeId = row.episodeId,
                    streamUrl = row.downloadUrl,
                    title = row.title,
                    artworkUrl = row.imageUrl,
                    providerId = row.providerId,
                    description = row.description,
                )
            }
        }
        return true
    }

    private companion object {
        const val TAG = "AutoAdvance"
        const val BACKUP_URL_PREFIX = "backup://"
    }
}
