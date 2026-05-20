package com.odyssey.scrape

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests against fixtures captured live on 2026-05-03 from
 * oneplace.com (see android/app/src/test/resources/oneplace/).
 *
 * Goal: pin down what `OneplaceClient.newSince()` does with real-world
 * server responses, so we can tell whether a "Check now finds nothing"
 * report is the scrape layer's fault or a runtime-side issue (Hilt,
 * WorkManager, observability).
 *
 * The production client targets oneplace.com; tests redirect the API
 * by overwriting its `apiUrl` field and pass a mock listen URL per
 * call (listen URL is now per-show, not a per-client field).
 */
class OneplaceClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OneplaceClient
    private lateinit var listenUrl: String

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = OneplaceClient(OkHttpClient())
        redirectClientTo(server.url("/"))
    }

    @After
    fun tearDown() = server.shutdown()

    // ----- listenUrl / latestEpisodeId -----

    @Test
    fun `latestEpisodeId extracts the bootstrap episodeId from the live listen page`() = runTest {
        server.enqueue(html(fixture("/oneplace/listen.html")))
        assertEquals(1278294L, client.latestEpisodeId(listenUrl))
    }

    @Test
    fun `latestEpisodeId returns null when the page has no episodeId assignment`() = runTest {
        server.enqueue(html("<html><body>no bootstrap here</body></html>"))
        assertNull(client.latestEpisodeId(listenUrl))
    }

    @Test
    fun `latestEpisodeId returns null on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertNull(client.latestEpisodeId(listenUrl))
    }

    // ----- episodesBefore / JSON deserialization -----

    @Test
    fun `episodesBefore parses the live API JSON without losing required fields`() = runTest {
        server.enqueue(json(fixture("/oneplace/api_page1.json")))
        val episodes = client.episodesBefore(cursor = 1278295L, pageSize = 20)
        assertEquals(5, episodes.size)
        with(episodes.first()) {
            assertEquals(1278383L, episodeId)
            assertEquals("War of the Words", title)
            assertEquals("May 8, 2026", airDate)
            assertTrue("download URL must be populated", downloadFileUrl.isNotBlank())
            assertTrue("episode page URL must be populated", url.isNotBlank())
        }
    }

    @Test
    fun `episodesBefore parses the imageUrl artwork field`() = runTest {
        server.enqueue(json(fixture("/oneplace/api_page1.json")))
        val episodes = client.episodesBefore(cursor = 1278295L, pageSize = 20)
        // Every fixture row carries the AIO show logo at this URL today.
        // Test asserts the artwork URL flows through the JSON model so the
        // row thumbnail and lockscreen art have something to render.
        val expected = "https://i.swncdn.com/cdn/400w/zcast/oneplace/host-images/" +
            "adventures-in-odyssey/AIO_FOTF_Color_640x480.webp?v=260210-360"
        assertEquals(expected, episodes.first().imageUrl)
        assertTrue(
            "all rows in the fixture should have a non-null imageUrl",
            episodes.all { !it.imageUrl.isNullOrBlank() },
        )
    }

    @Test
    fun `episodesBefore returns empty list for an empty JSON array`() = runTest {
        server.enqueue(json("[]"))
        assertTrue(client.episodesBefore(cursor = 1L, pageSize = 5).isEmpty())
    }

    @Test
    fun `episodesBefore parses showId so providers can filter cross-show contamination`() = runTest {
        // The api_page1 fixture is real AIO traffic — every row has
        // showId=777. Without this assertion AioOneplaceProvider's
        // filter has no way to tell the rows apart, and Sekulow leaks
        // into the AIO Library. See AioOneplaceFilterTest for the
        // downstream consumer of this field.
        server.enqueue(json(fixture("/oneplace/api_page1.json")))
        val episodes = client.episodesBefore(cursor = 1278295L, pageSize = 20)
        assertTrue(
            "every fixture row must report showId so isAio() can run",
            episodes.all { it.showId != null },
        )
        assertEquals(
            "fixture is real AIO traffic -- showId=777 for every row",
            777L,
            episodes.first().showId,
        )
    }

    // ----- newSince — the path that "Check now" actually exercises -----

    @Test
    fun `newSince fresh install with maxFetch=7 returns 7 episodes across two pages`() = runTest {
        // First call hits the listen page for the bootstrap eid (1278294).
        server.enqueue(html(fixture("/oneplace/listen.html")))
        // Then the client walks the API: page1 has 5 episodes, page2 has 4 — total 9 available, capped at 7.
        server.enqueue(json(fixture("/oneplace/api_page1.json")))
        server.enqueue(json(fixture("/oneplace/api_page2.json")))

        val results = client.newSince(listenUrl, lastSeen = 0L, maxFetch = 7)

        assertEquals("expected 7 episodes (5 from page1 + 2 from page2)", 7, results.size)
        // Newest first.
        assertEquals(1278383L, results.first().episodeId)
        // Distinct ids.
        assertEquals(7, results.map { it.episodeId }.toSet().size)
    }

    @Test
    fun `newSince stops when it encounters lastSeen and excludes it from results`() = runTest {
        server.enqueue(html(fixture("/oneplace/listen.html")))
        server.enqueue(json(fixture("/oneplace/api_page1.json")))

        // page1 ids: 1278383, 1278382, 1278381, 1278380, 1278379. Pretend we've
        // already seen 1278381 — should get the two newer ones and stop.
        val results = client.newSince(listenUrl, lastSeen = 1278381L, maxFetch = 50)

        assertEquals(2, results.size)
        assertEquals(listOf(1278383L, 1278382L), results.map { it.episodeId })
    }

    @Test
    fun `newSince returns empty when latest equals lastSeen (nothing new since last run)`() = runTest {
        server.enqueue(html(fixture("/oneplace/listen.html")))
        // No API call should be needed — latest (1278294) == lastSeen.
        val results = client.newSince(listenUrl, lastSeen = 1278294L, maxFetch = 50)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `newSince returns empty list when bootstrap fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(client.newSince(listenUrl, lastSeen = 0L, maxFetch = 7).isEmpty())
    }

    @Test
    fun `newSince terminates when the API consistently returns empty pages`() = runTest {
        // As of 2026-05-20 the API returns [] for any seed eid that isn't
        // a real episodeId — newSince now probes forward through gaps.
        // Pre-load enough empties to exhaust the probe cap so we still
        // exit cleanly when oneplace is genuinely empty / broken.
        server.enqueue(html(fixture("/oneplace/listen.html")))
        repeat(25) { server.enqueue(json("[]")) }
        val results = client.newSince(listenUrl, lastSeen = 0L, maxFetch = 50)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `newSince probes past a gap eid to find the latest episode (2026-05-20 regression)`() = runTest {
        // User report 2026-05-20: refresh said "no new episodes" when a
        // new AIO broadcast had aired that morning. Live probe shows
        // oneplace's /api/related-episodes now returns [] when the seed
        // eid is a gap in the global CMS id sequence — so the original
        // `cursor = latest + 1` lookup misses the newly-published episode.
        // Repro: listen page bootstraps with latest=1278393; cursor=1278394
        // is a gap (api returns []); cursor=1278395 returns the AIO show's
        // recent episodes including 1278393.
        server.enqueue(html("""<html><body><script>episodeId=1278393</script></body></html>"""))
        // cursor=1278394 → gap
        server.enqueue(json("[]"))
        // cursor=1278395 → returns the latest episode (1278393) and 6 more
        val page = """[
            {"episodeId":1278393,"title":"First-Hand Experience","subTitle":"May 20, 2026",
             "downloadFileUrl":"https://cdn.example/1278393.mp3","url":"https://example/1278393",
             "showId":777,"durationSeconds":1500},
            {"episodeId":1278392,"title":"Two Brothers and Bernard, Part 2","subTitle":"May 19, 2026",
             "downloadFileUrl":"https://cdn.example/1278392.mp3","url":"https://example/1278392",
             "showId":777,"durationSeconds":1500},
            {"episodeId":1278391,"title":"Two Brothers and Bernard, Part 1","subTitle":"May 18, 2026",
             "downloadFileUrl":"https://cdn.example/1278391.mp3","url":"https://example/1278391",
             "showId":777,"durationSeconds":1500}
        ]"""
        server.enqueue(json(page))
        // After walking back via cursor = page.last().episodeId = 1278391,
        // the next request should hit an empty page and terminate (we've
        // already found content, so subsequent empty means archive tail —
        // NOT another gap to probe past).
        server.enqueue(json("[]"))

        val results = client.newSince(listenUrl, lastSeen = 0L, maxFetch = 50)

        assertEquals("expected the 3 episodes from the gap-skipped page", 3, results.size)
        assertEquals(
            "newest episode 1278393 must be in the result -- it's the one the user was waiting for",
            1278393L, results.first().episodeId,
        )
        assertEquals(
            listOf(1278393L, 1278392L, 1278391L),
            results.map { it.episodeId },
        )
    }

    @Test
    fun `newSince does not keep probing once it has found content (archive tail stop)`() = runTest {
        // Once we've successfully read a page, a subsequent [] means
        // we've walked off the end of the show's archive — not a gap
        // to skip. Don't keep probing forward, or we'd march through
        // unrelated eids forever.
        server.enqueue(html("""<html><body><script>episodeId=1278393</script></body></html>"""))
        // First call: returns one episode.
        server.enqueue(json("""[
            {"episodeId":1278393,"title":"only one","subTitle":"May 20, 2026",
             "downloadFileUrl":"https://cdn.example/x.mp3","url":"https://example/x",
             "showId":777,"durationSeconds":1500}
        ]"""))
        // Second call (cursor = 1278393 from page.last) → empty.
        // Must stop here without further probing.
        server.enqueue(json("[]"))

        val results = client.newSince(listenUrl, lastSeen = 0L, maxFetch = 50)

        assertEquals(1, results.size)
        // If we did keep probing, MockWebServer would record extra
        // requests beyond these three. Verify the request count to
        // catch a regression where probesRemaining doesn't reset on
        // first success.
        assertEquals("listen + cursor + empty = 3 requests, no extra probes", 3, server.requestCount)
    }

    // ----- helpers -----

    private fun html(body: String) = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(body)

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun fixture(path: String): String =
        OneplaceClientTest::class.java.getResource(path)
            ?.readText()
            ?: error("fixture not found: $path (is src/test/resources/$path checked in?)")

    private fun redirectClientTo(base: HttpUrl) {
        listenUrl = base.newBuilder()
            .addPathSegments("ministries/adventures-in-odyssey/listen/").build().toString()
        client.apiUrl = base.newBuilder()
            .addPathSegments("api/related-episodes").build().toString().trimEnd('/')
    }
}
