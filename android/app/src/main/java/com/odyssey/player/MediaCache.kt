package com.odyssey.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
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
     * HTTP factory that injects auth headers **only** on requests
     * targeting the configured NAS host. Previously we set those
     * headers as default request properties so every HTTP request the
     * player issued carried them — that was fine for oneplace.com
     * (whose CDN ignores unknown Authorization headers) but BROKE
     * yourstoryhour.org's S3 buckets, which validate the Authorization
     * header and reject anything that isn't a SigV4 signature with
     * HTTP 400. Symptoms before this fix: YSH playback failed with
     * `ERROR_CODE_IO_BAD_HTTP_STATUS (2004)` the moment the user
     * tapped Play on a streaming YSH track.
     *
     * Reads settings on every `createDataSource()` call so token
     * rotation picks up at the next playback. The returned data
     * source captures the NAS-host snapshot and applies headers only
     * when the open() URL matches that host — so a rotation to a new
     * NAS during an active stream doesn't break the in-flight read.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun authAwareHttpFactory(): HttpDataSource.Factory {
        return object : HttpDataSource.Factory {
            override fun createDataSource(): HttpDataSource {
                val current = runCatching { runBlocking { settings.flow.first() } }
                    .onFailure { t -> com.odyssey.debug.DebugLogger.w("MediaCache", "settings.flow lookup failed — playback will run unauthenticated", t) }
                    .getOrNull()
                val nasHost = current?.nasUrl?.takeIf { it.isNotBlank() }
                    ?.let { url ->
                        runCatching { java.net.URI(url).host }
                            .onFailure { t -> com.odyssey.debug.DebugLogger.w("MediaCache", "nasUrl '$url' is not a valid URI — host-scoped auth disabled", t) }
                            .getOrNull()
                    }
                val headers = buildMap<String, String> {
                    current?.nasToken?.takeIf { it.isNotBlank() }
                        ?.let { put("Authorization", "Bearer $it") }
                    if (current?.cfAccessConfigured == true) {
                        put("CF-Access-Client-Id", current.cfAccessClientId)
                        put("CF-Access-Client-Secret", current.cfAccessClientSecret)
                    }
                }
                return HostScopedHttpDataSource(
                    delegate = DefaultHttpDataSource.Factory().createDataSource(),
                    authHeaders = headers,
                    nasHost = nasHost,
                )
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

/**
 * HttpDataSource decorator that adds `Authorization` / `CF-Access-*`
 * headers ONLY when the per-request URL points at the configured
 * NAS host. Other hosts (oneplace.com, yourstoryhour S3) see a
 * vanilla HTTP request — necessary because AWS S3 returns HTTP 400
 * when an unknown Authorization header is present.
 *
 * Implements HttpDataSource by delegation so all the read/seek/range
 * methods come through unchanged; only `open()` decides whether to
 * attach headers.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class HostScopedHttpDataSource(
    private val delegate: HttpDataSource,
    private val authHeaders: Map<String, String>,
    private val nasHost: String?,
) : HttpDataSource by delegate {

    override fun open(dataSpec: DataSpec): Long {
        if (authHeaders.isNotEmpty() && shouldAttachAuth(dataSpec.uri.host)) {
            authHeaders.forEach { (k, v) -> delegate.setRequestProperty(k, v) }
        } else {
            // Make sure no stale headers from a previous open() linger
            // on the underlying socket (DefaultHttpDataSource reuses
            // its instance across opens).
            authHeaders.keys.forEach { delegate.clearRequestProperty(it) }
        }
        return delegate.open(dataSpec)
    }

    private fun shouldAttachAuth(requestHost: String?): Boolean =
        nasHost != null && requestHost != null && requestHost.equals(nasHost, ignoreCase = true)
}
