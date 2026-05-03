package com.odyssey.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.PlaybackDao
import com.odyssey.data.local.PlaybackPositionEntity
import com.odyssey.debug.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
                if (isPlaying) startSaveLoop(c) else { saveJob?.cancel(); persist(c) }
            }
        })
    }

    private fun startSaveLoop(c: MediaController) {
        saveJob?.cancel()
        saveJob = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                delay(5_000)
                persist(c)
            }
        }
    }

    private fun persist(c: MediaController) {
        val id = c.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val pos = c.currentPosition
        val dur = c.duration.coerceAtLeast(0)
        val complete = if (shouldMarkComplete(pos, dur)) System.currentTimeMillis() else null
        CoroutineScope(Dispatchers.IO).launch {
            playback.upsert(PlaybackPositionEntity(id, pos, dur, System.currentTimeMillis(), complete))
        }
    }
}
