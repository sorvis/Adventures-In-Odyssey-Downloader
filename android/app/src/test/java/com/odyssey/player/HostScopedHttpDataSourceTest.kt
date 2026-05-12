package com.odyssey.player

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the v0.1.39+ playback bug: streaming YSH tracks
 * from `your-story-hour.s3.amazonaws.com` failed with HTTP 400 because
 * the player's HttpDataSource attached the NAS `Authorization: Bearer
 * <token>` header to every request — and S3 validates Authorization
 * headers strictly, rejecting non-SigV4 ones.
 *
 * HostScopedHttpDataSource fixes this by only attaching the headers
 * when the request URL host matches the configured NAS host. These
 * tests drive the delegate behavior directly with a recording stub
 * so we don't need ExoPlayer or a real HTTP server.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class HostScopedHttpDataSourceTest {

    @Test
    fun `attaches auth headers when host matches configured NAS host`() {
        val delegate = RecordingHttpDataSource()
        val ds = HostScopedHttpDataSource(
            delegate = delegate,
            authHeaders = mapOf(
                "Authorization" to "Bearer XYZ",
                "CF-Access-Client-Id" to "id1",
                "CF-Access-Client-Secret" to "secret1",
            ),
            nasHost = "archive.lan",
        )
        ds.open(DataSpec(Uri.parse("http://archive.lan:8088/episodes/42/audio")))

        // The three auth headers got set; clear was NOT called on them.
        assertEquals("Bearer XYZ", delegate.setHeaders["Authorization"])
        assertEquals("id1", delegate.setHeaders["CF-Access-Client-Id"])
        assertEquals("secret1", delegate.setHeaders["CF-Access-Client-Secret"])
        assertTrue("no clear calls when host matches", delegate.clearedKeys.isEmpty())
    }

    @Test
    fun `does NOT attach auth headers when host is a third-party CDN`() {
        val delegate = RecordingHttpDataSource()
        val ds = HostScopedHttpDataSource(
            delegate = delegate,
            authHeaders = mapOf("Authorization" to "Bearer XYZ"),
            nasHost = "archive.lan",
        )
        // YSH S3 — exactly the URL the user's logcat reported. S3
        // returned HTTP 400 when we added an Authorization header.
        ds.open(
            DataSpec(Uri.parse(
                "https://your-story-hour.s3.amazonaws.com/documents/mp3s/H-19%20-%20Bicycles%20and%20Kites.mp3"
            )),
        )

        assertFalse("Authorization must NOT be set for non-NAS hosts",
                    "Authorization" in delegate.setHeaders)
        // Headers were proactively cleared so a previous open() can't
        // leave a stale Authorization on the underlying socket.
        assertTrue("clear() should be called for the auth keys",
                   "Authorization" in delegate.clearedKeys)
    }

    @Test
    fun `does nothing when no auth headers are configured even on NAS host`() {
        val delegate = RecordingHttpDataSource()
        val ds = HostScopedHttpDataSource(
            delegate = delegate,
            authHeaders = emptyMap(),
            nasHost = "archive.lan",
        )
        ds.open(DataSpec(Uri.parse("http://archive.lan/episodes/1/audio")))

        assertTrue("no headers set when authHeaders is empty",
                   delegate.setHeaders.isEmpty())
        assertTrue("no clears when authHeaders is empty",
                   delegate.clearedKeys.isEmpty())
    }

    @Test
    fun `oneplace_com requests do NOT get auth headers either`() {
        // The original bug comment was "oneplace.com ignores them" — but
        // we still don't want to leak credentials to a third party,
        // even when ignored. The host-scoping fixes both issues at once.
        val delegate = RecordingHttpDataSource()
        val ds = HostScopedHttpDataSource(
            delegate = delegate,
            authHeaders = mapOf("Authorization" to "Bearer XYZ"),
            nasHost = "archive.lan",
        )
        ds.open(DataSpec(Uri.parse(
            "https://zcast.swncdn.com/episodes/zcast/adventures-in-odyssey/1278383.mp3"
        )))
        assertFalse("Authorization", "Authorization" in delegate.setHeaders)
    }

    @Test
    fun `host comparison is case insensitive`() {
        val delegate = RecordingHttpDataSource()
        val ds = HostScopedHttpDataSource(
            delegate = delegate,
            authHeaders = mapOf("Authorization" to "Bearer XYZ"),
            nasHost = "Archive.LAN",
        )
        ds.open(DataSpec(Uri.parse("http://archive.lan/episodes/1/audio")))
        assertEquals("Bearer XYZ", delegate.setHeaders["Authorization"])
    }

    @Test
    fun `nasHost null means never attach`() {
        // Fresh install with no NAS configured — every request is
        // third-party by definition, never attach auth.
        val delegate = RecordingHttpDataSource()
        val ds = HostScopedHttpDataSource(
            delegate = delegate,
            authHeaders = mapOf("Authorization" to "Bearer XYZ"),
            nasHost = null,
        )
        ds.open(DataSpec(Uri.parse("http://archive.lan/episodes/1/audio")))
        assertFalse("Authorization" in delegate.setHeaders)
    }

    // ------------------------------------------------------------------

    /** Records every setRequestProperty / clearRequestProperty call. */
    private class RecordingHttpDataSource : HttpDataSource {
        val setHeaders = mutableMapOf<String, String>()
        val clearedKeys = mutableSetOf<String>()

        override fun setRequestProperty(name: String, value: String) {
            setHeaders[name] = value
        }
        override fun clearRequestProperty(name: String) {
            clearedKeys += name
            setHeaders.remove(name)
        }
        override fun clearAllRequestProperties() {
            clearedKeys += setHeaders.keys
            setHeaders.clear()
        }
        override fun open(dataSpec: DataSpec): Long = 0
        override fun close() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun getResponseCode(): Int = 200
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        override fun getUri(): Uri? = null
        override fun addTransferListener(transferListener: TransferListener) {}
    }
}
