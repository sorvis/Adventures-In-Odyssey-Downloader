package com.odyssey.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.PlaybackDao
import com.odyssey.data.local.PlaybackPositionEntity
import com.odyssey.debug.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val playback: PlaybackDao,
    private val recovery: PlaybackRecovery,
) : EpisodePlayer {
    private var controller: MediaController? = null
    private var saveJob: Job? = null

    suspend fun connect(): MediaController {
        controller?.let {
            DebugLogger.d("PlayerController", "connect() — returning cached controller")
            return it
        }
        DebugLogger.d("PlayerController", "connect() — building MediaController")
        val token = SessionToken(ctx, ComponentName(ctx, OdysseyPlaybackService::class.java))
        return suspendCoroutine { cont ->
            val future: ListenableFuture<MediaController> =
                MediaController.Builder(ctx, token).buildAsync()
            future.addListener({
                try {
                    val c = future.get()
                    controller = c
                    attachPositionTracker(c)
                    DebugLogger.d("PlayerController", "connect() — controller ready")
                    cont.resume(c)
                } catch (t: Throwable) {
                    DebugLogger.e(
                        "PlayerController",
                        "connect() — MediaController.buildAsync() failed",
                        t,
                    )
                    cont.resumeWithException(t)
                }
            }, ctx.mainExecutor)
        }
    }

    override suspend fun playLocal(ep: LocalEpisodeEntity) {
        DebugLogger.i("PlayerController", "playLocal(${ep.episodeId}) path=${ep.filePath}")
        val c = try {
            connect()
        } catch (t: Throwable) {
            DebugLogger.e("PlayerController", "playLocal — connect() threw", t)
            return
        }
        val path = ep.filePath
        if (path == null) {
            DebugLogger.w("PlayerController", "playLocal called with null filePath — bailing")
            return
        }
        when (decidePlayAction(c.currentMediaItem?.mediaId, c.isPlaying, ep.episodeId.toString())) {
            PlayAction.NoOp -> {
                DebugLogger.d("PlayerController", "playLocal — already playing ${ep.episodeId}, no-op")
                return
            }
            PlayAction.Resume -> {
                DebugLogger.d("PlayerController", "playLocal — resuming ${ep.episodeId}")
                c.playWhenReady = true
                return
            }
            PlayAction.LoadFresh -> Unit
        }
        // Diagnostic: ExoPlayer's UnrecognizedInputFormatException points at
        // the file content not matching any known media format (HTML error
        // page, truncated download, mangled resume-append). First 64 bytes
        // tell us instantly which: ID3 / 0xFF 0xFB → real MP3; <!DOC → HTML.
        runCatching {
            val f = java.io.File(path)
            if (!f.exists()) {
                DebugLogger.w("PlayerController", "playLocal — file missing on disk: $path")
            } else {
                val first = f.inputStream().use { stream ->
                    val buf = ByteArray(64)
                    val n = stream.read(buf)
                    if (n <= 0) ByteArray(0) else buf.copyOf(n)
                }
                val asAscii = first.map { b ->
                    val c = b.toInt() and 0xFF
                    if (c in 0x20..0x7E) c.toChar() else '.'
                }.joinToString("")
                val asHex = first.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
                DebugLogger.i(
                    "PlayerController",
                    "playLocal — file size=${f.length()} bytes first64=[$asAscii] hex=$asHex",
                )
            }
        }.onFailure {
            DebugLogger.w("PlayerController", "playLocal — could not inspect file at $path", it)
        }
        val item = MediaItem.Builder()
            .setMediaId(ep.episodeId.toString())
            .setUri(android.net.Uri.fromFile(java.io.File(path)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ep.title).setArtist("Adventures in Odyssey")
                    .build()
            )
            .build()
        runCatching {
            c.setMediaItem(item)
            c.prepare()
            playback.get(ep.episodeId)?.let { c.seekTo(it.positionMs) }
            c.playWhenReady = true
            DebugLogger.d("PlayerController", "playLocal — prepare+playWhenReady issued")
        }.onFailure {
            DebugLogger.e("PlayerController", "playLocal — controller call threw", it)
        }
    }

    override suspend fun playStream(episodeId: Long, streamUrl: String, title: String) {
        DebugLogger.i("PlayerController", "playStream($episodeId) url=$streamUrl")
        val c = try {
            connect()
        } catch (t: Throwable) {
            DebugLogger.e("PlayerController", "playStream — connect() threw", t)
            return
        }
        when (decidePlayAction(c.currentMediaItem?.mediaId, c.isPlaying, episodeId.toString())) {
            PlayAction.NoOp -> {
                DebugLogger.d("PlayerController", "playStream — already playing $episodeId, no-op")
                return
            }
            PlayAction.Resume -> {
                DebugLogger.d("PlayerController", "playStream — resuming $episodeId")
                c.playWhenReady = true
                return
            }
            PlayAction.LoadFresh -> Unit
        }
        val item = MediaItem.Builder()
            .setMediaId(episodeId.toString())
            .setUri(streamUrl)
            // Pin cache key to the episode ID so MediaCache hits survive
            // CDN-token rotation in the streamUrl (oneplace's mp3 URLs
            // include rotating query params).
            .setCustomCacheKey(episodeId.toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        runCatching {
            c.setMediaItem(item)
            c.prepare()
            playback.get(episodeId)?.let { c.seekTo(it.positionMs) }
            c.playWhenReady = true
            DebugLogger.d("PlayerController", "playStream — prepare+playWhenReady issued")
        }.onFailure {
            DebugLogger.e("PlayerController", "playStream — controller call threw", it)
        }
    }

    private fun attachPositionTracker(c: MediaController) {
        c.addListener(object : Player.Listener {

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                DebugLogger.i("ExoPlayer", "isPlaying=$isPlaying")
                if (isPlaying) startSaveLoop(c) else { saveJob?.cancel(); persist(c) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val name = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                DebugLogger.i("ExoPlayer", "playbackState → $name")
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                DebugLogger.d("ExoPlayer", "playWhenReady=$playWhenReady reason=$reason")
            }

            override fun onPlayerError(error: PlaybackException) {
                // PlaybackException's errorCodeName + cause stack tells us
                // exactly what the upstream pipeline (CacheDataSource →
                // FileDataSource → decoder) blew up on.
                DebugLogger.e(
                    "ExoPlayer",
                    "onPlayerError code=${error.errorCodeName} (${error.errorCode}) msg=${error.message}",
                    error,
                )

                // Self-heal corrupt downloads: when ExoPlayer can't parse
                // the container, the file is usually an HTML error page or
                // a truncated stream. Hand off to PlaybackRecovery, which
                // sniffs magic bytes and re-enqueues if it's not an MP3.
                if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                ) {
                    val episodeId = c.currentMediaItem?.mediaId?.toLongOrNull()
                    if (episodeId != null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            recovery.handleParseError(episodeId)
                        }
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                DebugLogger.d(
                    "ExoPlayer",
                    "mediaItem transition mediaId=${mediaItem?.mediaId ?: "null"} reason=$reason",
                )
            }
        })
    }

    // MediaController has thread affinity — every read of currentMediaItem,
    // currentPosition, duration, etc. must happen on its application looper
    // (the main thread for Media3's MediaSessionService default). The
    // previous save loop ran on Dispatchers.Default and threw
    // IllegalStateException("Player is accessed on the wrong thread") about
    // 5 seconds after playback started, which crashed the process because
    // the coroutine had no exception handler.
    //
    // Fix: run the loop on Main, only TOUCHING the controller from there,
    // then dispatch the DB upsert to IO. Wrap the loop in a SupervisorJob
    // + CoroutineExceptionHandler so any future stray throw lands in the
    // log instead of killing the app.
    private val saveLoopHandler = CoroutineExceptionHandler { _, t ->
        DebugLogger.e("PlayerController", "save loop crashed", t)
    }

    private fun startSaveLoop(c: MediaController) {
        saveJob?.cancel()
        saveJob = savePeriodicallyOnMain(
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + saveLoopHandler),
            intervalMs = SAVE_INTERVAL_MS,
        ) { persist(c) }
    }

    private fun persist(c: MediaController) {
        // Must be called on Main — see startSaveLoop comment.
        val id: Long
        val pos: Long
        val dur: Long
        try {
            id = c.currentMediaItem?.mediaId?.toLongOrNull() ?: return
            pos = c.currentPosition
            dur = c.duration.coerceAtLeast(0)
        } catch (t: Throwable) {
            DebugLogger.e("PlayerController", "persist — controller read failed", t)
            return
        }
        val complete = if (shouldMarkComplete(pos, dur)) System.currentTimeMillis() else null
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                playback.upsert(PlaybackPositionEntity(id, pos, dur, System.currentTimeMillis(), complete))
            }.onFailure { DebugLogger.w("PlayerController", "persist — playback.upsert failed", it) }
        }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
    }
}

/**
 * Periodic save loop, extracted to top-level so it's testable without
 * holding a real MediaController.
 *
 * Each iteration runs `persist` after a delay of [intervalMs]; an
 * exception in a single iteration is caught + logged so the loop keeps
 * firing on the next tick (defense in depth — `persist` itself also
 * has try/catch around its controller reads).
 */
internal fun savePeriodicallyOnMain(
    scope: CoroutineScope,
    intervalMs: Long,
    persist: () -> Unit,
): Job = scope.launch {
    while (true) {
        delay(intervalMs)
        try {
            persist()
        } catch (t: Throwable) {
            DebugLogger.e("PlayerController", "save loop iteration threw — continuing", t)
        }
    }
}
