package com.odyssey.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.odyssey.app.SettingsRepo
import com.odyssey.debug.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class MediaCache @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsRepo,
) {

    private val cacheDir: File = File(ctx.filesDir, CACHE_DIR_NAME).apply { mkdirs() }

    val cache: SimpleCache = run {
        DebugLogger.d("MediaCache", "init — opening SimpleCache at ${cacheDir.absolutePath}")
        runCatching {
            SimpleCache(
                cacheDir,
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(ctx),
            )
        }.onFailure {
            DebugLogger.e("MediaCache", "SimpleCache init failed — playback will be broken", it)
        }.getOrThrow()
    }

    /**
     * Build the player's DataSource.Factory: file:// (and other local
     * schemes) bypass the cache entirely; HTTP streams flow through
     * CacheDataSource so they get progressively cached on disk.
     *
     * Why local files bypass: we used to wrap CacheDataSource around
     * DefaultDataSource for everything. That produced a cache-poisoning
     * bug — see CacheBypassingDataSource for the full story.
     */
    fun mediaSourceDataFactory(): DataSource.Factory = CacheBypassingDataSourceFactory(
        cachedFactory = CacheDataSource.Factory()
            .setCache(cache)
            // Cached upstream is HTTP-only — local schemes are handled by
            // the plain factory below, so we don't need a DefaultDataSource
            // here that includes a FileDataSource.
            .setUpstreamDataSourceFactory(authAwareHttpFactory())
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
            // Don't fail reads when cache writes hit disk-full / evictor.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
        plainFactory = DefaultDataSource.Factory(ctx),
    )

    /**
     * HTTP factory that injects `Authorization: Bearer <token>` on
     * every request when a NAS bearer is configured. Lets the player
     * stream directly from the self-hosted backup service's protected
     * /episodes/N/audio endpoint without a separate URL-rewriting
     * dance. Reads settings on every createDataSource() call so a
     * token rotation picks up at the next playback.
     *
     * Side effect: oneplace.com requests also carry the header. Public
     * AIO endpoints don't validate Authorization so they ignore the
     * extra bytes.
     */
    private fun authAwareHttpFactory(): HttpDataSource.Factory {
        return object : HttpDataSource.Factory {
            override fun createDataSource(): HttpDataSource {
                val current = runCatching { runBlocking { settings.flow.first() } }.getOrNull()
                val token = current?.nasToken?.takeIf { it.isNotBlank() }
                val factory = DefaultHttpDataSource.Factory()
                if (token != null) {
                    factory.setDefaultRequestProperties(
                        mapOf("Authorization" to "Bearer $token"),
                    )
                }
                return factory.createDataSource()
            }

            override fun setDefaultRequestProperties(
                defaultRequestProperties: Map<String, String>,
            ): HttpDataSource.Factory = this
        }
    }

    /**
     * @deprecated kept temporarily for any caller still on the old name.
     * Use [mediaSourceDataFactory] for new code.
     */
    @Deprecated("Use mediaSourceDataFactory()", ReplaceWith("mediaSourceDataFactory()"))
    fun cacheDataSourceFactory(): DataSource.Factory = mediaSourceDataFactory()

    companion object {
        const val CACHE_DIR_NAME = "media-cache"

        // 500MB ≈ 35 AIO episodes. Bigger than typical "last few weeks"
        // listening; not so big it dominates the storage profile.
        const val MAX_BYTES: Long = 500L * 1024 * 1024
    }
}
