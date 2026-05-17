package com.odyssey.show

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.odyssey.catalog.AioCatalogRepo
import com.odyssey.scrape.OneplaceClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration test for [AioOneplaceProvider] that drives the real
 * provider against a MockWebServer returning a mixed-show response
 * — proving the showId filter is wired up in `newSince`, not just
 * defined in the pure helper.
 *
 * Background: the live oneplace API returns Sekulow / FOTF rows
 * intermixed with AIO when the cursor falls past the AIO range.
 * A pure-helper test (`AioOneplaceFilterTest`) verifies `isAio()`
 * works in isolation, but doesn't catch the regression where
 * `newSince` forgets to actually CALL it. This test does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AioOneplaceProviderTest {

    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private lateinit var oneplace: OneplaceClient
    private lateinit var provider: AioOneplaceProvider

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        oneplace = OneplaceClient(OkHttpClient()).apply {
            apiUrl = server.url("/api/related-episodes").toString().trimEnd('/')
        }
        provider = AioOneplaceProvider(oneplace, AioCatalogRepo(ctx)).apply {
            listenUrl = server.url("/ministries/adventures-in-odyssey/listen/").toString()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `newSince drops Sekulow rows mixed into the related-episodes response`() = runBlocking {
        // Bootstrap page: latest AIO episodeId = 1278389.
        server.enqueue(html(bootstrap(1278389)))
        // First page: 2 AIO + 2 Sekulow + 1 FOTF. Mirrors what live
        // oneplace returned 2026-05-17 when the cursor crossed the
        // AIO/Sekulow boundary.
        server.enqueue(jsonResp("""[
            ${aioEp(1278389, "The Secret Keys of Discipline")},
            ${sekulowEp(1278252, "Sekulow")},
            ${aioEp(1278388, "Fences")},
            ${sekulowEp(1278046, "Sekulow")},
            ${fotfEp(1278000, "Focus on the Family Daily")}
        ]"""))
        // Pagination terminator.
        server.enqueue(jsonResp("[]"))

        val episodes = provider.newSince(lastSeenExternalId = null, maxFetch = 50)

        assertEquals("only the 2 AIO episodes pass the filter", 2, episodes.size)
        assertTrue(
            "real AIO episode is included",
            episodes.any { it.title == "The Secret Keys of Discipline" },
        )
        assertTrue(
            "AIO 'Fences' is included",
            episodes.any { it.title == "Fences" },
        )
        assertFalse(
            "Sekulow rows must NOT be present — this is the bug fix the test prevents from regressing",
            episodes.any { it.title.contains("Sekulow") },
        )
        assertFalse(
            "Focus on the Family rows must NOT be present",
            episodes.any { it.title.contains("Focus on the Family") },
        )
    }

    // ----- helpers --------------------------------------------------------

    private fun html(body: String) = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(body)

    private fun jsonResp(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    /** Minimal listen-page HTML — only the bootstrap regex matters. */
    private fun bootstrap(latest: Long) =
        "<html><script>var x = { episodeId: $latest };</script></html>"

    private fun aioEp(id: Long, title: String) = oneplaceEpJson(
        id = id, showId = 777L, title = title,
        downloadUrl = "https://zcast.swncdn.com/episodes/zcast/adventures-in-odyssey/2026/05-11/$id/777_x.mp3",
    )

    private fun sekulowEp(id: Long, title: String) = oneplaceEpJson(
        id = id, showId = 663L, title = title,
        downloadUrl = "https://zcast.swncdn.com/episodes/zcast/jay-sekulow-live/2026/04-16/$id/663_x.mp3",
    )

    private fun fotfEp(id: Long, title: String) = oneplaceEpJson(
        id = id, showId = 555L, title = title,
        downloadUrl = "https://zcast.swncdn.com/episodes/zcast/focus-on-the-family/2026/05-11/$id/555_x.mp3",
    )

    private fun oneplaceEpJson(id: Long, showId: Long, title: String, downloadUrl: String) = """
        {
            "episodeId": $id,
            "showId": $showId,
            "title": "${title.replace("\"", "\\\"")}",
            "subTitle": "May 11, 2026",
            "descriptionHtmlWithoutImages": "stub",
            "description": null,
            "downloadFileUrl": "$downloadUrl",
            "url": "https://www.oneplace.com/x/$id",
            "durationSeconds": 1800,
            "imageUrl": null
        }
    """.trimIndent()
}
