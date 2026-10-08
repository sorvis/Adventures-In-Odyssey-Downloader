package com.odyssey.player

import com.odyssey.data.local.LocalEpisodeEntity
import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal play surface that RecentVm depends on. Existing only so a fake
 * can be substituted in tests — PlayerController is the production impl
 * and is bound to this interface in PlayerModule.
 *
 * Other surfaces (connect, position-tracker, transport controls used by
 * NowPlayingScreen) stay on PlayerController itself; this interface is
 * just the dispatch boundary.
 *
 * Named EpisodePlayer (not Player) to avoid a clash with
 * androidx.media3.common.Player which is referenced inside
 * PlayerController for the Player.Listener nested type.
 */
interface EpisodePlayer {
    /**
     * @param artworkUrl optional override for the artwork on the
     * MediaItem's metadata (used by lockscreen, MiniPlayer, NowPlaying
     * screen). When null, falls back to the entity's own imageUrl.
     */
    suspend fun playLocal(ep: LocalEpisodeEntity, artworkUrl: String? = null)
    suspend fun playStream(
        episodeId: Long,
        streamUrl: String,
        title: String,
        artworkUrl: String? = null,
        /**
         * Which provider this stream belongs to. Drives the artist
         * string in MediaMetadata so lockscreens display the correct
         * show name. Defaults to AIO so existing AIO-only call sites
         * (BrowseNasScreen, AlbumDetailScreen, RecentScreen,
         * DownloadedScreen) keep working unchanged.
         */
        providerId: String = "aio",
        /**
         * Episode synopsis surfaced by NowPlayingScreen. Optional —
         * not every call site has it (NAS browse rows that haven't
         * been mirrored to LocalEpisode yet may lack one); when null
         * or blank the player screen just hides the description block.
         */
        description: String? = null,
    )

    /**
     * Pauses whatever is currently playing. No-op when nothing is loaded
     * or playback is already paused. Used by row-level Play/Pause toggles.
     */
    suspend fun pause()

    /**
     * Live snapshot of "what's loaded" + "is it playing right now."
     * Row UIs collect this so the play button can flip to a pause icon
     * when the row's episode IS the one currently playing.
     */
    val state: StateFlow<PlayerStateSnapshot>

    /**
     * Load a whole album as the player's playlist and start at
     * [startIndex].
     *
     * Previously the player held exactly one MediaItem, and advancing
     * was done by hand: AlbumQueueController kept a parallel list and
     * AutoAdvanceController swapped the item on STATE_ENDED. That works
     * on-screen but gives the MediaSession nothing to advertise, so
     * next/previous were missing everywhere it matters — lockscreen,
     * notification, Bluetooth headphones, car head unit, Android Auto.
     *
     * With a real playlist ExoPlayer advances natively and the session
     * exposes seek-to-next/previous for free.
     *
     * Callers resolve URIs before calling (see [PlayableItem]) so the
     * player stays ignorant of the NAS and the catalogs.
     */
    suspend fun playAlbum(items: List<PlayableItem>, startIndex: Int)
}

/**
 * One fully-resolved entry in a playlist.
 *
 * "Resolved" is the point: [uri] is ready to hand to ExoPlayer, so the
 * player never has to know that a `backup://` row means "ask NasClient
 * for the URL". That resolution is cheap — NasClient.audioUrl() builds
 * a string from settings and makes no network call — and the bearer
 * token is attached at open time by MediaCache's host-scoped HTTP
 * factory, so a whole album resolves without a single round-trip.
 */
data class PlayableItem(
    val episodeId: Long,
    val providerId: String,
    val title: String,
    val uri: String,
    val artworkUrl: String? = null,
    val description: String? = null,
)

/**
 * What the player is doing. Updated whenever a track loads or the
 * play/pause state changes — on a 500ms-ish cadence at worst, since
 * Media3's onIsPlayingChanged fires synchronously on transport ticks.
 */
data class PlayerStateSnapshot(
    val currentEpisodeId: Long?,
    val isPlaying: Boolean,
) {
    companion object {
        val IDLE = PlayerStateSnapshot(currentEpisodeId = null, isPlaying = false)
    }
}
