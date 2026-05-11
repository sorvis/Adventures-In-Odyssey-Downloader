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
}

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
