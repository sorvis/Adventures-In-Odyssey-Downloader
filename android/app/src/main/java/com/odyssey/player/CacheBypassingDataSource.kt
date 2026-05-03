package com.odyssey.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * DataSource wrapper that picks between a *cached* upstream (good for
 * HTTP streams — saves the bytes for offline replay) and a *plain*
 * upstream (good for already-local content like file://) based on the
 * URI scheme of each open() call.
 *
 * Why this exists: routing local files through CacheDataSource produced
 * a cache-poisoning bug where:
 *   1. First play of a corrupt MP3 wrote zero bytes into the cache,
 *      keyed by the file URI.
 *   2. PlaybackRecovery deleted + re-downloaded the file (now valid).
 *   3. Next play hit the cache (still keyed by the same URI), got the
 *      OLD zeros, ExoPlayer rejected with UnrecognizedInputFormat.
 *
 * Bypassing cache for `file://` (and other local schemes) eliminates the
 * stale-cache class entirely — local files never need caching anyway,
 * the bytes are already on disk.
 */
internal class CacheBypassingDataSource(
    private val cached: DataSource,
    private val plain: DataSource,
) : DataSource {

    private var active: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        val source = pickSource(dataSpec.uri)
        active = source
        return source.open(dataSpec)
    }

    private fun pickSource(uri: Uri): DataSource =
        if (uri.scheme?.lowercase() in LOCAL_SCHEMES) plain else cached

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    override fun addTransferListener(transferListener: TransferListener) {
        // Forward to both backing sources — addTransferListener is called
        // before open(), so we don't yet know which will be active.
        cached.addTransferListener(transferListener)
        plain.addTransferListener(transferListener)
    }

    companion object {
        private val LOCAL_SCHEMES = setOf("file", "content", "asset", "rawresource", "android.resource")
    }
}

/** Factory paired with [CacheBypassingDataSource]. */
internal class CacheBypassingDataSourceFactory(
    private val cachedFactory: DataSource.Factory,
    private val plainFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        CacheBypassingDataSource(
            cached = cachedFactory.createDataSource(),
            plain = plainFactory.createDataSource(),
        )
}
