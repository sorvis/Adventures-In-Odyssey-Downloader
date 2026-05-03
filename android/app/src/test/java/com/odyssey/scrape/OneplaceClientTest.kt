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
 * The production client targets oneplace.com; tests redirect it at a
 * MockWebServer by overwriting its `listenUrl` / `apiUrl` properties.
 */
class OneplaceClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OneplaceClient

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
        assertEquals(1278294L, client.latestEpisodeId())
    }

    @Test
    fun `latestEpisodeId returns null when the page has no episodeId assignment`() = runTest {
        server.enqueue(html("<html><body>no bootstrap here</body></html>"))
        assertNull(client.latestEpisodeId())
    }

    @Test
    fun `latestEpisodeId returns null on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertNull(client.latestEpisodeId())
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
    fun `episodesBefore returns empty list for an empty JSON array`() = runTest {
        server.enqueue(json("[]"))
        assertTrue(client.episodesBefore(cursor = 1L, pageSize = 5).isEmpty())
    }

    // ----- newSince — the path that "Check now" actually exercises -----

    @Test
    fun `newSince fresh install with maxFetch=7 returns 7 episodes across two pages`() = runTest {
        // First call hits the listen page for the bootstrap eid (1278294).
        server.enqueue(html(fixture("/oneplace/listen.html")))
        // Then the client walks the API: page1 has 5 episodes, page2 has 4 — total 9 available, capped at 7.
        server.enqueue(json(fixture("/oneplace/api_page1.json")))
        server.enqueue(json(fixture("/oneplace/api_page2.json")))

        val results = client.newSince(lastSeen = 0L, maxFetch = 7)

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
        val results = client.newSince(lastSeen = 1278381L, maxFetch = 50)

        assertEquals(2, results.size)
        assertEquals(listOf(1278383L, 1278382L), results.map { it.episodeId })
    }

    @Test
    fun `newSince returns empty when latest equals lastSeen (nothing new since last run)`() = runTest {
        server.enqueue(html(fixture("/oneplace/listen.html")))
        // No API call should be needed — latest (1278294) == lastSeen.
        val results = client.newSince(lastSeen = 1278294L, maxFetch = 50)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `newSince returns empty list when bootstrap fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(client.newSince(lastSeen = 0L, maxFetch = 7).isEmpty())
    }

    @Test
    fun `newSince terminates when the API returns an empty page`() = runTest {
        server.enqueue(html(fixture("/oneplace/listen.html")))
        server.enqueue(json("[]"))                  // empty page → loop breaks
        val results = client.newSince(lastSeen = 0L, maxFetch = 50)
        assertTrue(results.isEmpty())
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
        client.listenUrl = base.newBuilder()
            .addPathSegments("ministries/adventures-in-odyssey/listen/").build().toString()
        client.apiUrl = base.newBuilder()
            .addPathSegments("api/related-episodes").build().toString().trimEnd('/')
    }
}
