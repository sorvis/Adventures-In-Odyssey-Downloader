package com.odyssey.show

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odyssey.data.local.OdysseyDb
import com.odyssey.data.local.YshUnmatchedDao
import com.odyssey.scrape.OneplaceClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * YshOneplaceProvider — drives the oneplace YSH broadcast feed through
 * the catalog title-join. We need real Room (for the unmatched-titles
 * DAO) and a real YshCatalog (for the title lookup); both are wired
 * via MockWebServers to keep the test JVM-style with no network.
 *
 * Behaviors locked down:
 *   1. Episodes whose title hits the catalog produce ProviderEpisodes
 *      with externalId = "ysh-sku-<sku_id>" — same scheme as
 *      YshFreeStreamProvider so dedup works on the composite PK.
 *   2. Episodes whose title misses the catalog do NOT produce a
 *      ProviderEpisode and DO land in `ysh_unmatched_titles`.
 *   3. Re-encountering a previously-unmatched title bumps attemptCount
 *      instead of creating a duplicate row.
 *   4. Album metadata flows from the catalog hit (not from the
 *      oneplace response, which carries none for YSH).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class YshOneplaceProviderTest {

    private val ctx: Application = ApplicationProvider.getApplicationContext()

    private lateinit var oneplaceServer: MockWebServer
    private lateinit var catalogServer: MockWebServer
    private lateinit var db: OdysseyDb
    private lateinit var unmatched: YshUnmatchedDao
    private lateinit var oneplace: OneplaceClient
    private lateinit var catalog: YshCatalog
    private lateinit var provider: YshOneplaceProvider

    @Before
    fun setUp() = runBlocking {
        oneplaceServer = MockWebServer().apply { start() }
        catalogServer = MockWebServer().apply { start() }

        db = Room.inMemoryDatabaseBuilder(ctx, OdysseyDb::class.java)
            .allowMainThreadQueries()
            .build()
        unmatched = db.yshUnmatched()

        oneplace = OneplaceClient(OkHttpClient()).apply {
            apiUrl = oneplaceServer.url("/api/related-episodes").toString().trimEnd('/')
        }

        catalog = YshCatalog(ctx, OkHttpClient()).apply {
            skusUrl = catalogServer.url("/crud/product/skus").toString().trimEnd('/')
        }
        // Pre-load the catalog from the fixture pages so title-join hits work.
        ctx.filesDir.resolve("ysh/catalog.json").delete()
        catalogServer.enqueue(jsonResp(fixture("/ysh/catalog-page-1.json")))
        catalogServer.enqueue(jsonResp(fixture("/ysh/catalog-page-2.json")))
        catalog.refresh().getOrThrow()

        provider = YshOneplaceProvider(oneplace, catalog, unmatched).apply {
            listenUrl = oneplaceServer.url("/ministries/your-story-hour/listen/").toString()
        }
    }

    @After
    fun tearDown() {
        oneplaceServer.shutdown()
        catalogServer.shutdown()
        db.close()
        ctx.filesDir.resolve("ysh/catalog.json").delete()
    }

    @Test
    fun `matched episodes get ysh-sku externalIds with album metadata from the catalog`() = runBlocking {
        // oneplace returns one YSH episode whose title is in the
        // catalog fixture ("The Land of Uz" → Bible Comes Alive Vol 4).
        oneplaceServer.enqueue(html(bootstrapHtml(latestEpisodeId = 1277616)))
        oneplaceServer.enqueue(jsonResp(oneplacePage(listOf(
            opEpisode(episodeId = 1277616, title = "The Land of Uz"),
        ))))
        oneplaceServer.enqueue(jsonResp("[]"))   // pagination terminator

        val episodes = provider.newSince(lastSeenExternalId = null, maxFetch = 10)
        assertEquals(1, episodes.size)
        val ep = episodes.first()
        // externalId is the catalog's sku_id with the canonical prefix
        // — NOT the oneplace CMS id.
        assertTrue(
            "externalId must be ysh-sku-<n>, was ${ep.externalId}",
            ep.externalId.startsWith("ysh-sku-"),
        )
        // catalog provides the album cover URL.
        assertNotNull("album image URL should flow from the catalog match", ep.imageUrl)
        assertTrue(
            "album image URL should be the catalog's S3 path",
            ep.imageUrl!!.contains("BibleComesAlive"),
        )
        // sourceUrl + downloadUrl flow straight from oneplace.
        assertEquals("https://www.oneplace.com/.../the-land-of-uz-1277616", ep.sourceUrl)
        assertEquals("https://zcast.swncdn.com/.../1277616.mp3", ep.downloadUrl)
        // No unmatched-titles row was inserted.
        assertEquals(0, unmatched.observeCount().first())
    }

    @Test
    fun `unmatched titles are logged and dropped from the output`() = runBlocking {
        oneplaceServer.enqueue(html(bootstrapHtml(latestEpisodeId = 9999999)))
        oneplaceServer.enqueue(jsonResp(oneplacePage(listOf(
            opEpisode(episodeId = 9999999, title = "Title That Is Not In The Catalog"),
        ))))
        oneplaceServer.enqueue(jsonResp("[]"))

        val episodes = provider.newSince(lastSeenExternalId = null, maxFetch = 10)
        assertEquals("unmatched titles are dropped from the output", 0, episodes.size)

        val rows = unmatched.observeAll().first()
        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals(9999999L, row.oneplaceEpisodeId)
        assertEquals("Title That Is Not In The Catalog", row.title)
        assertEquals(
            "first-ever encounter should land with attemptCount = 1 after the insert+bump pattern",
            1, row.attemptCount,
        )
    }

    @Test
    fun `re-encountering an unmatched title bumps attemptCount rather than duplicating the row`() = runBlocking {
        // Run #1: fresh miss → attemptCount = 1
        oneplaceServer.enqueue(html(bootstrapHtml(latestEpisodeId = 9999999)))
        oneplaceServer.enqueue(jsonResp(oneplacePage(listOf(
            opEpisode(episodeId = 9999999, title = "Misses Twice"),
        ))))
        oneplaceServer.enqueue(jsonResp("[]"))
        provider.newSince(lastSeenExternalId = null, maxFetch = 10)
        assertEquals(1, unmatched.observeCount().first())
        assertEquals(1, unmatched.observeAll().first().first().attemptCount)

        // Run #2: same episode comes back through oneplace's window
        // → insert is IGNORE'd, attemptCount goes to 2.
        oneplaceServer.enqueue(html(bootstrapHtml(latestEpisodeId = 9999999)))
        oneplaceServer.enqueue(jsonResp(oneplacePage(listOf(
            opEpisode(episodeId = 9999999, title = "Misses Twice"),
        ))))
        oneplaceServer.enqueue(jsonResp("[]"))
        provider.newSince(lastSeenExternalId = null, maxFetch = 10)
        assertEquals("still just one unmatched row", 1, unmatched.observeCount().first())
        assertEquals(2, unmatched.observeAll().first().first().attemptCount)
    }

    @Test
    fun `mixed-match page produces only the matched episode and one unmatched row`() = runBlocking {
        oneplaceServer.enqueue(html(bootstrapHtml(latestEpisodeId = 1277611)))
        oneplaceServer.enqueue(jsonResp(oneplacePage(listOf(
            opEpisode(episodeId = 1277611, title = "The \$14 Horse"),     // matches Exciting Events Vol 17
            opEpisode(episodeId = 7777, title = "Not in catalog"),         // miss
        ))))
        oneplaceServer.enqueue(jsonResp("[]"))

        val episodes = provider.newSince(lastSeenExternalId = null, maxFetch = 10)
        assertEquals(1, episodes.size)
        assertEquals("The \$14 Horse", episodes.first().title)
        assertEquals(1, unmatched.observeCount().first())
        assertEquals(7777L, unmatched.observeAll().first().first().oneplaceEpisodeId)
    }

    // ----- helpers --------------------------------------------------------

    private fun html(body: String) = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(body)

    private fun jsonResp(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    /** Minimal oneplace `/listen/` HTML — only the bootstrap regex matters. */
    private fun bootstrapHtml(latestEpisodeId: Long): String =
        "<html><script>var x = { episodeId: $latestEpisodeId };</script></html>"

    /** Serialize OneplaceEpisode-shaped objects to the JSON the API would return. */
    private fun oneplacePage(eps: List<OpFixtureEpisode>): String =
        eps.joinToString(prefix = "[", postfix = "]") { ep ->
            """
            {
              "episodeId": ${ep.episodeId},
              "title": "${ep.title.replace("\"", "\\\"")}",
              "subTitle": "${ep.airDate}",
              "descriptionHtmlWithoutImages": "${ep.description}",
              "description": null,
              "downloadFileUrl": "${ep.downloadFileUrl}",
              "url": "${ep.url}",
              "durationSeconds": ${ep.durationSeconds},
              "imageUrl": null
            }
            """.trimIndent()
        }

    private data class OpFixtureEpisode(
        val episodeId: Long,
        val title: String,
        val airDate: String = "May 10, 2026",
        val description: String = "stub",
        val downloadFileUrl: String,
        val url: String,
        val durationSeconds: Long = 1800,
    )

    private fun opEpisode(
        episodeId: Long,
        title: String,
        downloadFileUrl: String = "https://zcast.swncdn.com/.../$episodeId.mp3",
        url: String = "https://www.oneplace.com/.../${title.lowercase().replace(" ", "-")}-$episodeId",
    ) = OpFixtureEpisode(episodeId, title, downloadFileUrl = downloadFileUrl, url = url)

    private fun fixture(path: String): String =
        YshOneplaceProviderTest::class.java.getResource(path)
            ?.readText()
            ?: error("fixture not found: $path")
}
