package com.odyssey.player

import com.odyssey.catalog.AioCatalogRepo
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.debug.DebugLogger
import com.odyssey.nas.NasClient
import com.odyssey.show.YshCatalog
import com.odyssey.show.yshAlbumImageUrlForRow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Start playing this episode" — the full dispatch, in one place.
 *
 * Three sources, in priority order:
 *   1. on-disk file
 *   2. `backup://` ghost — a retention-pruned local copy or a
 *      NasMirror-only row; resolved through NasClient and streamed from
 *      the bearer-protected /audio endpoint
 *   3. public CDN stream
 *
 * This logic used to live inside `RecentVm.play`, which meant only the
 * Recent tab could start an arbitrary episode correctly. NowPlayingScreen
 * needs it too: when Android kills the playback service in the
 * background, the screen reconnects to an empty controller and its play
 * button no-ops — it has nothing loaded and no way to load anything
 * (reported 2026-10-04 with a screenshot of "Nothing playing" and a dead
 * play button, taken mid-navigation).
 *
 * Also primes the album auto-advance queue, so playback started from
 * anywhere rolls on to the next track.
 *
 * Deliberately does NOT own the pause-in-place toggle (tapping the row
 * of the episode already playing). That's per-surface UI policy and
 * stays in the ViewModels; this class only starts things.
 */
@Singleton
class EpisodePlayDispatcher @Inject constructor(
    private val player: EpisodePlayer,
    private val nas: NasClient,
    private val catalog: AioCatalogRepo,
    private val yshCatalog: YshCatalog,
    private val queuePrimer: AlbumQueuePrimer,
) {

    /**
     * Artwork for [ep]: catalog thumbnail, else the row's own image,
     * else the YSH album cover. Exposed because callers render it
     * before (and independently of) dispatch.
     */
    fun artworkFor(ep: LocalEpisodeEntity): String? =
        catalog.match(ep.title)?.thumbnailUrl
            ?: ep.imageUrl
            ?: yshAlbumImageUrlForRow(ep, yshCatalog.state.value)

    /**
     * Start [ep], loading its whole album as the player's playlist so
     * ExoPlayer advances natively and the MediaSession can expose
     * next/previous everywhere (lockscreen, Bluetooth, car).
     *
     * Returns true when playback was dispatched; false when it could
     * not be — currently only an episode whose audio cannot be resolved
     * (a backup:// row with the NAS unconfigured or unreachable) — so
     * callers can surface that instead of leaving a button that
     * silently does nothing.
     *
     * Album members that cannot be resolved are dropped from the
     * playlist rather than failing the whole play. Losing one pruned
     * track should not stop the album.
     *
     * @param tag log prefix identifying the calling surface.
     */
    suspend fun play(ep: LocalEpisodeEntity, tag: String = "PlayDispatcher"): Boolean {
        val album = runCatching { queuePrimer.orderedAlbumFor(ep) }
            .onFailure { DebugLogger.e(tag, "album lookup failed", it) }
            .getOrDefault(emptyList())

        // Keep the legacy AlbumQueueController in step with the playlist.
        // It is redundant while a playlist is loaded (STATE_ENDED only
        // fires at the end of the whole list, where nextAfter returns
        // null), but the album-detail screens still drive it, and a
        // stale queue left pointing at a different album is the one way
        // this could advance somewhere wrong.
        runCatching { queuePrimer.primeFor(ep, ordered = album) }
            .onFailure { DebugLogger.e(tag, "queue prime failed", it) }

        // No album resolved: play the single episode as a one-item list.
        val rows = album.ifEmpty { listOf(ep) }
        val playables = rows.mapNotNull { toPlayable(it) }
        val startIndex = playables.indexOfFirst { it.episodeId == ep.episodeId }
        if (startIndex < 0) {
            DebugLogger.w(
                tag,
                "play(${ep.episodeId}) - could not resolve audio (backup:// with NAS unreachable?)",
            )
            return false
        }
        DebugLogger.i(
            tag,
            "play(${ep.episodeId}) - album playlist of ${playables.size}, starting at $startIndex",
        )
        return try {
            player.playAlbum(playables, startIndex)
            true
        } catch (t: Throwable) {
            DebugLogger.e(tag, "play(${ep.episodeId}) - dispatch threw", t)
            false
        }
    }

    /**
     * Resolve one row to a playlist entry, or null when its audio
     * cannot be located.
     *
     * Priority matches what the single-episode path always did: an
     * on-disk file beats everything, then a backup:// ghost resolved
     * through the NAS, then the public CDN URL. None of these touch the
     * network — NasClient.audioUrl() builds a string from settings —
     * so a full album resolves without a round-trip, and the bearer
     * token is attached at open time by the host-scoped HTTP factory.
     */
    private suspend fun toPlayable(ep: LocalEpisodeEntity): PlayableItem? {
        val uri = when {
            ep.filePath != null ->
                android.net.Uri.fromFile(java.io.File(ep.filePath)).toString()
            ep.downloadUrl.startsWith("backup://") ->
                nas.audioUrl(ep.episodeId).getOrNull()?.url ?: return null
            else -> when (val src = playSourceFor(ep.filePath, ep.downloadUrl)) {
                is PlaySource.Local ->
                    android.net.Uri.fromFile(java.io.File(src.filePath)).toString()
                is PlaySource.Stream -> src.url
            }
        }
        return PlayableItem(
            episodeId = ep.episodeId,
            providerId = ep.providerId,
            title = ep.title,
            uri = uri,
            artworkUrl = artworkFor(ep),
            description = ep.description,
        )
    }
}
