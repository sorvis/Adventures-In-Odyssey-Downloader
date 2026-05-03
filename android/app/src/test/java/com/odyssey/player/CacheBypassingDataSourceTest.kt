package com.odyssey.player

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the URI-scheme dispatch contract for CacheBypassingDataSource:
 *   - file:// / content:// / asset:// → plain (no caching)
 *   - http:// / https:// → cached
 *
 * This is the architectural fix that ends the cache-poisoning class
 * of bug (where deleted-and-redownloaded local files served stale
 * zeros from the cache). A regression here would silently re-introduce
 * the bug, so the dispatch is locked down here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class CacheBypassingDataSourceTest {

    @Test
    fun `file URI dispatches to plain DataSource (skips cache)`() {
        val plain = RecordingDataSource()
        val cached = RecordingDataSource()
        val ds = CacheBypassingDataSource(cached = cached, plain = plain)
        ds.open(DataSpec(Uri.parse("file:///data/odyssey/episode.mp3")))
        assertTrue("plain factory should be opened", plain.opened)
        assertTrue("cached factory should NOT be opened for file://", !cached.opened)
    }

    @Test
    fun `https URI dispatches to cached DataSource`() {
        val plain = RecordingDataSource()
        val cached = RecordingDataSource()
        val ds = CacheBypassingDataSource(cached = cached, plain = plain)
        ds.open(DataSpec(Uri.parse("https://cdn.example/episode.mp3")))
        assertTrue("cached factory should be opened for https://", cached.opened)
        assertTrue("plain factory should NOT be opened for https://", !plain.opened)
    }

    @Test
    fun `content URI dispatches to plain DataSource`() {
        val plain = RecordingDataSource()
        val cached = RecordingDataSource()
        val ds = CacheBypassingDataSource(cached = cached, plain = plain)
        ds.open(DataSpec(Uri.parse("content://media/audio/123")))
        assertTrue(plain.opened)
        assertTrue(!cached.opened)
    }

    @Test
    fun `read forwards to whichever source was activated by open`() {
        val plain = RecordingDataSource(returnedBytes = 42)
        val cached = RecordingDataSource(returnedBytes = 99)
        val ds = CacheBypassingDataSource(cached = cached, plain = plain)
        ds.open(DataSpec(Uri.parse("file:///x")))
        // Read returns from the active (plain) source, not cached.
        assertEquals(42, ds.read(ByteArray(64), 0, 64))
    }

    @Test
    fun `addTransferListener forwards to BOTH sources (open hasn't picked yet)`() {
        val plain = RecordingDataSource()
        val cached = RecordingDataSource()
        val ds = CacheBypassingDataSource(cached = cached, plain = plain)
        val listener = NoopTransferListener()
        ds.addTransferListener(listener)
        assertSame(listener, plain.lastListener)
        assertSame(listener, cached.lastListener)
    }

    @Test
    fun `factory creates a non-null wrapper`() {
        val plainF = DataSource.Factory { RecordingDataSource() }
        val cachedF = DataSource.Factory { RecordingDataSource() }
        val factory = CacheBypassingDataSourceFactory(cachedF, plainF)
        assertTrue(factory.createDataSource() is CacheBypassingDataSource)
    }

    /** Minimal DataSource that records what was called on it. */
    private class RecordingDataSource(
        private val returnedBytes: Int = 0,
    ) : DataSource {
        var opened: Boolean = false
        var lastListener: TransferListener? = null

        override fun open(dataSpec: DataSpec): Long {
            opened = true
            return returnedBytes.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = returnedBytes
        override fun getUri(): Uri? = null
        override fun close() {}
        override fun addTransferListener(transferListener: TransferListener) {
            lastListener = transferListener
        }
    }

    private class NoopTransferListener : TransferListener {
        override fun onTransferInitializing(
            source: DataSource, dataSpec: DataSpec, isNetwork: Boolean,
        ) {}
        override fun onTransferStart(
            source: DataSource, dataSpec: DataSpec, isNetwork: Boolean,
        ) {}
        override fun onBytesTransferred(
            source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int,
        ) {}
        override fun onTransferEnd(
            source: DataSource, dataSpec: DataSpec, isNetwork: Boolean,
        ) {}
    }
}
