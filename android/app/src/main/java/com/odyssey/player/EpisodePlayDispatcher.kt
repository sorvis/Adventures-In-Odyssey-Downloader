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
     * Start [ep]. Returns true when playback was dispatched; false when
     * it couldn't be (currently only the backup:// row whose NAS is
     * unconfigured or unreachable), so callers can surface that rather
     * than leaving a button that silently does nothing.
     *
     * @param tag log prefix identifying the calling surface.
     */
    suspend fun play(ep: LocalEpisodeEntity, tag: String = "PlayDispatcher"): Boolean {
        val artwork = artworkFor(ep)
        // Install the album queue BEFORE dispatch so the STATE_ENDED hook
        // has somewhere to advance to. Best-effort: losing auto-advance
        // must never stop the episode from starting.
        runCatching { queuePrimer.primeFor(ep) }
            .onFailure { DebugLogger.e(tag, "queue prime failed", it) }

        return try {
            when {
                ep.filePath != null -> {
                    DebugLogger.i(tag, "play(${ep.episodeId}) — local")
                    player.playLocal(ep, artwork)
                    true
                }
                ep.downloadUrl.startsWith("backup://") -> {
                    val audio = nas.audioUrl(ep.episodeId).getOrNull()
                    if (audio == null) {
                        DebugLogger.w(
                            tag,
                            "play(${ep.episodeId}) — backup:// row but NAS unconfigured/unreachable",
                        )
                        return false
                    }
                    DebugLogger.i(tag, "play(${ep.episodeId}) — stream from NAS")
                    player.playStream(
                        ep.episodeId, audio.url, ep.title, artwork,
                        providerId = ep.providerId, description = ep.description,
                    )
                    true
                }
                else -> {
                    DebugLogger.i(tag, "play(${ep.episodeId}) — stream from CDN")
                    when (val src = playSourceFor(ep.filePath, ep.downloadUrl)) {
                        is PlaySource.Local -> player.playLocal(ep, artwork)
                        is PlaySource.Stream -> player.playStream(
                            ep.episodeId, src.url, ep.title, artwork,
                            providerId = ep.providerId, description = ep.description,
                        )
                    }
                    true
                }
            }
        } catch (t: Throwable) {
            DebugLogger.e(tag, "play(${ep.episodeId}) — dispatch threw", t)
            false
        }
    }
}
