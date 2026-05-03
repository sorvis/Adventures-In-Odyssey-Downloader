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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val playback: PlaybackDao,
) {
    private var controller: MediaController? = null
    private var saveJob: Job? = null

    suspend fun connect(): MediaController {
        controller?.let { return it }
        val token = SessionToken(ctx, ComponentName(ctx, OdysseyPlaybackService::class.java))
        return suspendCoroutine { cont ->
            val future: ListenableFuture<MediaController> =
                MediaController.Builder(ctx, token).buildAsync()
            future.addListener({
                val c = future.get()
                controller = c
                attachPositionTracker(c)
                cont.resume(c)
            }, ctx.mainExecutor)
        }
    }

    suspend fun playLocal(ep: LocalEpisodeEntity) {
        val c = connect()
        val item = MediaItem.Builder()
            .setMediaId(ep.episodeId.toString())
            .setUri(android.net.Uri.fromFile(java.io.File(ep.filePath ?: return)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ep.title).setArtist("Adventures in Odyssey")
                    .build()
            )
            .build()
        c.setMediaItem(item)
        c.prepare()
        playback.get(ep.episodeId)?.let { c.seekTo(it.positionMs) }
        c.playWhenReady = true
    }

    suspend fun playStream(episodeId: Long, streamUrl: String, title: String) {
        val c = connect()
        val item = MediaItem.Builder()
            .setMediaId(episodeId.toString())
            .setUri(streamUrl)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        c.setMediaItem(item)
        c.prepare()
        playback.get(episodeId)?.let { c.seekTo(it.positionMs) }
        c.playWhenReady = true
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
