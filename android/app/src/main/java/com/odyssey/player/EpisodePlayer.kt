package com.odyssey.player

import com.odyssey.data.local.LocalEpisodeEntity

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
    suspend fun playLocal(ep: LocalEpisodeEntity)
    suspend fun playStream(episodeId: Long, streamUrl: String, title: String)
}
