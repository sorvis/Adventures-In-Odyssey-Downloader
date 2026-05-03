package com.odyssey.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.odyssey.debug.DebugLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * MediaSessionService keeps playback alive in background, surfaces lockscreen
 * + notification controls, and handles ±30s skip via custom commands.
 *
 * Position is persisted by PositionTracker (attached when the session is bound).
 *
 * @AndroidEntryPoint required for Hilt to inject MediaCache — the service is
 * an Android-managed entry point, not constructed via Dagger.
 */
@AndroidEntryPoint
class OdysseyPlaybackService : MediaSessionService() {

    @Inject lateinit var mediaCache: MediaCache

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        DebugLogger.i("PlaybackService", "onCreate — Hilt graph + MediaCache injected OK")
        try {
            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(mediaCache.mediaSourceDataFactory())
            DebugLogger.d("PlaybackService", "onCreate — DataSourceFactory built")
            val player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .setSeekForwardIncrementMs(30_000)
                .setSeekBackIncrementMs(30_000)
                .build()
            DebugLogger.d("PlaybackService", "onCreate — ExoPlayer built")
            session = MediaSession.Builder(this, player).build()
            DebugLogger.i("PlaybackService", "onCreate — MediaSession built (player ready)")
        } catch (t: Throwable) {
            DebugLogger.e(
                "PlaybackService",
                "onCreate — service init threw; play() will hang forever",
                t,
            )
            throw t
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player: Player? = session?.player
        if (player != null && !player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
