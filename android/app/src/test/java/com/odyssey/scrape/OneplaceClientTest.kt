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

    // ----- bootstrap — single GET extracts both eid AND showId -----

    @Test
    fun `bootstrap extracts both episodeId and showId from the live listen page`() = runTest {
        server.enqueue(html(fixture("/oneplace/listen.html")))
        val boot = client.bootstrap(listenUrl)
        assertNotNull(boot)
        assertEquals(1278294L, boot!!.latestEpisodeId)
        // window.salemMeta.showId=`777` lives in the fixture as captured
        // from live traffic; auto-discovering it removes the AIO=777
        // hardcoded constant as a single point of failure.
        assertEquals(777L, boot.showId)
    }

    @Test
    fun `bootstrap discovers showId even when only the zetaUniqueId pattern appears`() = runTest {
        // Defensive: if oneplace ever drops the explicit salemMeta.showId
        // assignment, the same id sits in salemMeta.zetaUniqueId. The
        // fallback regex picks it up.
        server.enqueue(html("""
            <html><script>
              window.salemMeta=window.salemMeta||{}
              salemMeta.episodeId=1278400
              window.salemMeta.zetaUniqueId=`663`
            </script></html>
        """.trimIndent()))
        val boot = client.bootstrap(listenUrl)
        assertEquals(1278400L, boot?.latestEpisodeId)
        assertEquals(663L, boot?.showId)
    }

    @Test
    fun `bootstrap returns null showId when no recognized show identity pattern matches`() = runTest {
        // Bootstrap should still surface the eid even when showId is
        // unknown — the caller's hint constant takes over from there.
        server.enqueue(html("""<html><script>episodeId=1278500</script></html>"""))
        val boot = client.bootstrap(listenUrl)
        assertEquals(1278500L, boot?.latestEpisodeId)
        assertNull("no showId pattern in HTML → null, caller uses its hint", boot?.showId)
    }

    @Test
    fun `bootstrap matches alternate episodeId shapes (data-eid, json blobs)`() = runTest {
        // Future-proofing: if oneplace stops emitting `episodeId=NNN` as
        // a bare JS assignment, these other shapes are the next likely
        // places it would surface. Without the fallback array, an HTML
        // schema swap would silently empty the Recent tab.
        server.enqueue(html("""<div data-eid="1278601" class="ep"></div>"""))
        assertEquals(1278601L, client.bootstrap(listenUrl)?.latestEpisodeId)

        server.enqueue(html("""<script>var data = {"eid": 1278602}</script>"""))
        assertEquals(1278602L, client.bootstrap(listenUrl)?.latestEpisodeId)

        server.enqueue(html("""<script>var data = {"episodeId": 1278603}</script>"""))
        assertEquals(1278603L, client.bootstrap(listenUrl)?.latestEpisodeId)
    }

    @Test
    fun `newSince uses bootstrap-discovered showId, ignoring the caller's stale hint`() = runTest {
        // Scenario: oneplace renumbers AIO from showId 777 to 888 (or any
        // other value). The app code still passes the constant 777L as a
        // hint — but the page itself now says `salemMeta.showId=`888``.
        // Bootstrap's discovered value MUST win, otherwise the probe
        // would filter against the stale id and return empty even
        // though the page is healthy.
        server.enqueue(html("""<html><script>
            salemMeta.episodeId=1300000
            window.salemMeta.showId=`888`
        </script></html>"""))
        server.enqueue(json("""[
            {"episodeId":1300010,"title":"new AIO","showId":888,
             "downloadFileUrl":"https://x/a.mp3","url":"https://x/a"},
            {"episodeId":1300005,"title":"older AIO","showId":888,
             "downloadFileUrl":"https://x/b.mp3","url":"https://x/b"},
            {"episodeId":1300003,"title":"jay sekulow leak","showId":663,
             "downloadFileUrl":"https://x/c.mp3","url":"https://x/c"}
        ]"""))
        server.enqueue(json("[]"))

        // Caller passes the OLD hint (777). Bootstrap discovers 888 and
        // overrides. Result: only the showId=888 items survive the filter.
        val results = client.newSince(listenUrl, lastSeen = 0L, maxFetch = 50, showId = 777L)

        assertEquals("filtered to discovered showId=888, not the caller's hint=777", 2, results.size)
        assertEquals(
            listOf(1300010L, 1300005L),
            results.map { it.episodeId },
        )
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
        // (Probe cap bumped 20 → 50 in v0.1.69 to handle wider AIO/other-
        // show interleaving; enqueue 60 to be safely past it.)
        server.enqueue(html(fixture("/oneplace/listen.html")))
        repeat(60) { server.enqueue(json("[]")) }
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
    fun `newSince -- showId probe walks PAST other-show pages until target found (2026-05-23 regression)`() = runTest {
        // User report 2026-05-23: AIO refresh missed May 21 and May 22
        // broadcasts. Root cause: oneplace's CMS id sequence interleaves
        // shows. latest=1278397 (AIO). cursor=latest+1 returned 20 items
        // of a DIFFERENT show. Old gap-probe accepted that page as
        // "found content", walked back via its last id into yet more
        // other-show history, returned 50 episodes — AioOneplaceProvider
        // filtered them all out → 0 AIO. The new probe takes showId and
        // keeps advancing until target-show items appear.
        //
        // (CMS ids must be ≥6 digits — bootstrapRe = `episodeId[=:"\s]+(\d{6,})`.
        // Use the real-world 1278XXX range so the regex actually matches.)
        server.enqueue(html("""<html><body><script>episodeId=1278397</script></body></html>"""))
        val otherShowPage = (1..20).joinToString(",") {
            """{"episodeId":${1278500 - it},"title":"sekulow $it","showId":999,"downloadFileUrl":"https://x/s$it.mp3","url":"https://x/s$it"}"""
        }
        server.enqueue(json("[$otherShowPage]"))           // cursor=1278398 → wrong show
        server.enqueue(json("[]"))                          // cursor=1278399 → gap
        val aioPage = """[
            {"episodeId":1278397,"title":"AIO Third Degree","showId":777,"downloadFileUrl":"https://x/a397.mp3","url":"https://x/a397"},
            {"episodeId":1278395,"title":"AIO Second Thoughts","showId":777,"downloadFileUrl":"https://x/a395.mp3","url":"https://x/a395"},
            {"episodeId":1278393,"title":"AIO First-Hand","showId":777,"downloadFileUrl":"https://x/a393.mp3","url":"https://x/a393"}
        ]"""
        server.enqueue(json(aioPage))                       // cursor=1278400 → has AIO
        server.enqueue(json("[]"))                           // walk-back tail

        val results = client.newSince(listenUrl, lastSeen = 0L, maxFetch = 50, showId = 777L)

        assertEquals("expected 3 AIO episodes after probing past 2 non-AIO pages", 3, results.size)
        assertTrue("latest AIO (1278397) must be in the result -- this is the regression",
            results.any { it.episodeId == 1278397L })
        assertEquals(setOf(1278397L, 1278395L, 1278393L), results.map { it.episodeId }.toSet())
    }

    @Test
    fun `newSince -- showId probe exhausts cap when no target-show seed found`() = runTest {
        // Every probe within the cap returns wrong-show pages. newSince
        // returns empty rather than spinning forever or leaking unrelated
        // rows.
        server.enqueue(html("""<html><body><script>episodeId=1278397</script></body></html>"""))
        repeat(60) {
            val wrongShow = """[{"episodeId":${1300000 + it},"title":"x","showId":999,"downloadFileUrl":"https://x/$it.mp3","url":"https://x/$it"}]"""
            server.enqueue(json(wrongShow))
        }
        val results = client.newSince(listenUrl, lastSeen = 0L, maxFetch = 50, showId = 777L)
        assertTrue("no target-show content → empty result, NOT wrong-show passthrough", results.isEmpty())
    }

    @Test
    fun `newSince -- no showId given keeps old behavior (any non-empty page wins)`() = runTest {
        // Back-compat: when caller doesn't pass showId, the old probe
        // semantics apply — first non-empty page wins.
        server.enqueue(html("""<html><body><script>episodeId=1278397</script></body></html>"""))
        server.enqueue(json("""[{"episodeId":1278395,"title":"a","downloadFileUrl":"https://x/a.mp3","url":"https://x/a"}]"""))
        server.enqueue(json("[]"))
        val results = client.newSince(listenUrl, lastSeen = 0L, maxFetch = 50, showId = null)
        assertEquals(1, results.size)
    }

    @Test
    fun `newSince probes seed eid directly when bootstrap is an anchor not the real latest (2026-05-31 regression)`() = runTest {
        // User report 2026-05-31: AIO Recent tab silently empty on every
        // fresh install. scripts/probe-oneplace.sh exposed the shift:
        //
        //   - Listen page bootstraps `episodeId=1278298` — an anchor /
        //     featured pointer, NOT the show's actual newest broadcast.
        //   - Live AIO episodes were 1278415–1278423 (May 25–29).
        //   - `/api/related-episodes?eid=1278298` returns the show's 5
        //     most recent AIO episodes (none of them ARE 1278298).
        //   - `?eid=1278299` (the old `cursor = latest + 1`) returns [];
        //     `?eid=1278300+` returns OTHER shows. Forward-probing 50 from
        //     +1 walked into non-AIO territory and newSince returned [].
        //
        // The fix is to start the cursor at `latest` (not `latest + 1`).
        // For an anchor seed, the API maps it to "the show's recent
        // episodes" and the very first probe hits AIO content.
        //
        // Fixture mirrors the live observation: anchor eid 1278298 with
        // an API response containing 5 AIO episodes whose ids are all
        // GREATER than the seed (1278423 → 1278415). The empty pages
        // that follow (cursor=1278299, 1278300, …) only appear if the
        // fix regresses — with the fix, Phase 2 terminates immediately
        // because page.last().episodeId = 1278415 returns [].
        server.enqueue(html("""<html><body><script>episodeId=1278298</script></body></html>"""))
        val anchorPage = """[
            {"episodeId":1278423,"title":"Gone . . .","subTitle":"May 29, 2026",
             "downloadFileUrl":"https://cdn.example/1278423.mp3","url":"https://example/1278423",
             "showId":777,"durationSeconds":1500},
            {"episodeId":1278422,"title":"The Fifth House on the Left, Part 2 of 2","subTitle":"May 28, 2026",
             "downloadFileUrl":"https://cdn.example/1278422.mp3","url":"https://example/1278422",
             "showId":777,"durationSeconds":1500},
            {"episodeId":1278420,"title":"The Fifth House on the Left, Part 1 of 2","subTitle":"May 27, 2026",
             "downloadFileUrl":"https://cdn.example/1278420.mp3","url":"https://example/1278420",
             "showId":777,"durationSeconds":1500},
            {"episodeId":1278417,"title":"It Happened at Four Corners","subTitle":"May 26, 2026",
             "downloadFileUrl":"https://cdn.example/1278417.mp3","url":"https://example/1278417",
             "showId":777,"durationSeconds":1500},
            {"episodeId":1278415,"title":"The War Hero","subTitle":"May 25, 2026",
             "downloadFileUrl":"https://cdn.example/1278415.mp3","url":"https://example/1278415",
             "showId":777,"durationSeconds":1500}
        ]"""
        server.enqueue(json(anchorPage))    // cursor=1278298 → 5 AIO (seed not in results)
        server.enqueue(json("[]"))          // walk-back tail (cursor=1278415)

        val results = client.newSince(listenUrl, lastSeen = 0L, maxFetch = 50, showId = 777L)

        assertEquals("expected 5 AIO episodes returned by the anchor seed", 5, results.size)
        assertEquals(
            "results are ordered newest-first matching the API page",
            listOf(1278423L, 1278422L, 1278420L, 1278417L, 1278415L),
            results.map { it.episodeId },
        )
        // The seed (1278298) must NOT appear in results — the API
        // doesn't return it and we don't fabricate it. (If the bootstrap
        // eid ever advances to the true latest, we'll lose at most one
        // episode per anchor cycle — acceptable.)
        assertTrue("seed eid is never echoed in the result set", results.none { it.episodeId == 1278298L })
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
