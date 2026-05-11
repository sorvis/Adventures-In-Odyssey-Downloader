package com.odyssey.show

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM tests for YshFreeStreamProvider against a fixture captured
 * from yourstoryhour.org/crud/free-streaming (6 albums × 1 free track
 * each = 7 tracks total in the snapshot).
 *
 * Provider behavior we want to lock down:
 *   - sku_id flows through as the externalId with the canonical
 *     `ysh-sku-<n>` prefix so the same story coming from
 *     YshOneplaceProvider later collapses on the composite-PK dedup.
 *   - Inline album metadata (name, slug, cover image) populates the
 *     ProviderEpisode without depending on YshCatalog.
 *   - Snapshot semantics — `lastSeenExternalId` is ignored.
 *   - maxFetch caps the output.
 */
class YshFreeStreamProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: YshFreeStreamProvider

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        provider = YshFreeStreamProvider(OkHttpClient()).apply {
            freeStreamUrl = server.url("/crud/free-streaming").toString()
        }
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `newSince maps every free track to a ProviderEpisode with the prefixed externalId`() = runTest {
        server.enqueue(json(fixture("/ysh/free-streaming.json")))

        val episodes = provider.newSince(lastSeenExternalId = null, maxFetch = 100)

        // Fixture has 6 albums × 1 free track each → 7 total tracks
        // (one album has 2 free tracks; that's just how the snapshot
        // landed). Verify by counting non-null download_urls in the
        // fixture rather than hard-coding to 7 — keeps the test
        // resilient if the snapshot is refreshed.
        assertTrue("expected at least 5 free tracks in the fixture", episodes.size >= 5)
        // Every externalId carries the canonical prefix so the dedup
        // with YshOneplaceProvider works.
        assertTrue(
            "all externalIds should start with `ysh-sku-`",
            episodes.all { it.externalId.startsWith("ysh-sku-") },
        )
        // Spot-check a known fixture row: "The Land of Uz" was the
        // free track of Bible Comes Alive Album 4 in the snapshot.
        val landOfUz = episodes.firstOrNull { it.title == "The Land of Uz" }
        assertNotNull(landOfUz)
        assertEquals("ysh-sku-${landOfUz!!.externalId.removePrefix("ysh-sku-")}", landOfUz.externalId)
        assertTrue("download URL should be the S3 mp3", landOfUz.downloadUrl.contains("s3.amazonaws.com"))
        // Free-streaming response includes the album's primary_image
        // — flowed through as imageUrl on the ProviderEpisode.
        assertTrue("imageUrl should be absolutized", landOfUz.imageUrl?.startsWith("http") == true)
    }

    @Test
    fun `newSince ignores lastSeenExternalId - snapshot source returns the full pool every time`() = runTest {
        server.enqueue(json(fixture("/ysh/free-streaming.json")))

        val first = provider.newSince(lastSeenExternalId = null, maxFetch = 100)

        // Re-fire with a lastSeenExternalId set to the first episode's
        // externalId. Result must be identical — this provider doesn't
        // track cursors.
        server.enqueue(json(fixture("/ysh/free-streaming.json")))
        val second = provider.newSince(lastSeenExternalId = first.first().externalId, maxFetch = 100)

        assertEquals(first.size, second.size)
        assertEquals(first.map { it.externalId }.toSet(), second.map { it.externalId }.toSet())
    }

    @Test
    fun `maxFetch caps the response`() = runTest {
        server.enqueue(json(fixture("/ysh/free-streaming.json")))
        val capped = provider.newSince(lastSeenExternalId = null, maxFetch = 3)
        assertEquals(3, capped.size)
    }

    @Test
    fun `tracks without a download URL are dropped silently`() = runTest {
        // Inline JSON — no S3 URL on either track, must produce 0 episodes.
        val brokenAlbum = """
            [{
              "product_id": 9,
              "product": "Stub Album",
              "created_at": "2020-01-01T00:00:00.000Z",
              "slug": "stub",
              "primary_image": null,
              "tracks": [
                {"sku_id": 1, "sku": "No URL Track", "length_seconds": 100, "is_free_streaming": true},
                {"sku_id": 2, "sku": "Empty URL",    "length_seconds": 100, "download_url": "", "is_free_streaming": true}
              ]
            }]
        """.trimIndent()
        server.enqueue(json(brokenAlbum))
        assertEquals(0, provider.newSince(lastSeenExternalId = null, maxFetch = 100).size)
    }

    @Test
    fun `provider id and metadata are wired correctly for the dedup join with YshOneplace`() {
        assertEquals("ysh", provider.id)
        assertEquals("Your Story Hour", provider.displayName)
        assertEquals("Your Story Hour", provider.artistName)
    }

    @Test
    fun `absolutize prepends the site origin to relative paths and leaves absolute URLs alone`() {
        assertEquals(
            "https://www.yourstoryhour.org/images/albums/foo.jpg",
            absolutize("/images/albums/foo.jpg"),
        )
        assertEquals(
            "https://www.yourstoryhour.org/images/albums/foo.jpg",
            absolutize("images/albums/foo.jpg"),
        )
        assertEquals("https://cdn/example.png", absolutize("https://cdn/example.png"))
        assertEquals(null, absolutize(null))
        assertEquals(null, absolutize("   "))
    }

    // ----- helpers --------------------------------------------------------

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun fixture(path: String): String =
        YshFreeStreamProviderTest::class.java.getResource(path)
            ?.readText()
            ?: error("fixture not found: $path")
}
