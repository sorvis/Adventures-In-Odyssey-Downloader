package com.odyssey.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
            // USAGE_MEDIA + handleAudioFocus lets ExoPlayer request audio
            // focus and, crucially, auto-pause on a transient loss (incoming
            // phone call) and auto-resume when focus returns. Without this the
            // player never requests focus, so a call may talk right over the
            // episode. MUSIC content type keeps notification dings as a duck
            // rather than a full pause; calls are a transient LOSS and pause
            // regardless.
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            val player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .setSeekForwardIncrementMs(30_000)
                .setSeekBackIncrementMs(30_000)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
                .build()
            DebugLogger.d("PlaybackService", "onCreate — ExoPlayer built")
            player.addListener(focusRewindListener(player))
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

    /**
     * Rewinds ~15s when playback resumes after an audio-focus interruption
     * (a phone call, principally) so the listener can re-orient rather than
     * landing mid-word. The auto-pause/auto-resume itself is handled by
     * ExoPlayer's built-in focus management; this listener only adds the
     * rewind on the resume edge. See [FocusPauseTracker] / [rewindTargetMs].
     */
    private fun focusRewindListener(player: ExoPlayer): Player.Listener {
        val tracker = FocusPauseTracker()
        return object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                val dueToFocusLoss =
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
                if (tracker.onPlayWhenReadyChanged(playWhenReady, dueToFocusLoss)) {
                    val target = rewindTargetMs(player.currentPosition)
                    DebugLogger.i(
                        "PlaybackService",
                        "resume after focus loss — rewinding ${player.currentPosition}ms → ${target}ms",
                    )
                    player.seekTo(target)
                }
            }
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
