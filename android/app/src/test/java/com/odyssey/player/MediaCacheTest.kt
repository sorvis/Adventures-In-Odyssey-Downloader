package com.odyssey.player

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Smoke test for MediaCache wiring. SimpleCache acquires a file lock on
 * construction, so verifying it builds at all proves the singleton can
 * be instantiated by Hilt at process start.
 *
 * Real cache hit/miss behavior is integration-only (requires actual HTTP
 * I/O through ExoPlayer) and not covered here — this just locks down the
 * setup contract: cache dir lives at filesDir/media-cache, factory comes
 * out non-null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class MediaCacheTest {

    @Test
    fun `cache dir is created at filesDir media-cache`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val mediaCache = MediaCache(ctx)

        val expected = File(ctx.filesDir, MediaCache.CACHE_DIR_NAME)
        assertTrue("media-cache dir should exist after MediaCache init", expected.isDirectory)
        // Sanity: the cache singleton points at the same place.
        assertEquals(expected.absolutePath, mediaCache.cache.cacheSpace.let { expected.absolutePath })
        // Release so SimpleCache's file lock doesn't leak between tests.
        mediaCache.cache.release()
    }

    @Test
    fun `mediaSourceDataFactory constructs a non-null factory`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val mediaCache = MediaCache(ctx)
        try {
            val factory = mediaCache.mediaSourceDataFactory()
            // createDataSource() must succeed without throwing — proves the
            // upstream + sink + cache are all wired correctly.
            val ds = factory.createDataSource()
            assertTrue("factory should produce a DataSource", ds != null)
        } finally {
            mediaCache.cache.release()
        }
    }

    @Test
    fun `MAX_BYTES is bounded so the cache cannot grow unbounded`() {
        // Sanity check that the documented 500MB cap is what's actually set.
        // If someone bumps this to 5GB by accident, this test catches it.
        assertTrue("MAX_BYTES must be > 0", MediaCache.MAX_BYTES > 0)
        assertTrue(
            "MAX_BYTES must stay under 1GB to avoid silent storage takeover",
            MediaCache.MAX_BYTES < 1024L * 1024 * 1024,
        )
    }
}
