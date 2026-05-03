package com.odyssey.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-process-wide ExoPlayer media cache. SimpleCache acquires a file lock
 * on its directory so it MUST be a singleton — multiple instances pointing
 * at the same path will throw at construction time.
 *
 * Used as the upstream for ExoPlayer's MediaSource.Factory in
 * OdysseyPlaybackService. Streamed bytes get written to disk as they're
 * read, so the second play of a streamed episode comes from cache instead
 * of re-fetching from the network.
 *
 * Local file URIs (file://) also flow through this cache when ExoPlayer
 * plays already-downloaded episodes — the bytes get redundantly cached
 * once. Trade-off: ~2× storage for downloaded episodes that have also been
 * played. The LRU evictor (500MB cap) eventually reclaims space.
 */
@Singleton
class MediaCache @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val cacheDir: File = File(ctx.filesDir, CACHE_DIR_NAME).apply { mkdirs() }

    val cache: SimpleCache = SimpleCache(
        cacheDir,
        LeastRecentlyUsedCacheEvictor(MAX_BYTES),
        StandaloneDatabaseProvider(ctx),
    )

    /**
     * Build a CacheDataSource.Factory for the ExoPlayer to use. Wraps
     * DefaultDataSource (which itself dispatches by URI scheme — http→net,
     * file→FileDataSource, etc.) so the same factory handles both streaming
     * and local file playback.
     */
    fun cacheDataSourceFactory(): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(ctx))
        .setCacheWriteDataSinkFactory(
            CacheDataSink.Factory().setCache(cache),
        )
        // If writing to cache fails (disk full, evictor mid-prune), don't
        // fail the read — just stream straight from upstream.
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    companion object {
        const val CACHE_DIR_NAME = "media-cache"

        // 500MB ≈ 35 AIO episodes. Bigger than typical "last few weeks"
        // listening; not so big it dominates the storage profile.
        const val MAX_BYTES: Long = 500L * 1024 * 1024
    }
}
