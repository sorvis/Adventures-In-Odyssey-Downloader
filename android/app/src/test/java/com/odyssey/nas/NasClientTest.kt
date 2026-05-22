package com.odyssey.nas

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.odyssey.app.SettingsRepo
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adversarial coverage for the NAS HTTP surface (added 2026-05-22 after
 * multiple production regressions slipped past a previously-empty
 * test directory). Every path the Android RetentionWorker, NasMirror,
 * and BrowseVm depend on gets exercised against a MockWebServer with
 * both happy-path and known-bad-data responses.
 *
 * Why these tests in particular: bugs the user surfaced in v0.1.63-67
 * all involved trusting some shape of NAS response without verifying
 * the failure modes. The tests below pin the contract from the
 * client side so the next regression fails locally, not on the user's
 * phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class NasClientTest {

    private lateinit var server: MockWebServer
    private lateinit var settings: SettingsRepo
    private lateinit var client: NasClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        settings = SettingsRepo(ctx)
        runBlocking {
            settings.clearAllForTest()
            settings.setNas(server.url("/").toString().trimEnd('/'), "tok")
        }
        client = NasClient(settings, OkHttpClient())
    }

    @After
    fun tearDown() { server.shutdown() }

    // ---- isConfigured -----------------------------------------------------

    @Test
    fun `isConfigured -- false when url is blank`() = runBlocking {
        settings.setNas("", "tok")
        assertFalse(client.isConfigured())
    }

    @Test
    fun `isConfigured -- false when token is blank`() = runBlocking {
        settings.setNas(server.url("/").toString().trimEnd('/'), "")
        assertFalse(client.isConfigured())
    }

    @Test
    fun `isConfigured -- true when both set`() = runBlocking {
        assertTrue(client.isConfigured())
    }

    // ---- episodeExistsOnNas (verify-before-prune HEAD probe) --------------

    @Test
    fun `episodeExistsOnNas -- 200 maps to true`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val r = client.episodeExistsOnNas(123L)
        assertEquals(true, r.getOrThrow())
        val req = server.takeRequest()
        assertEquals("HEAD", req.method)
        assertEquals("/episodes/123", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
    }

    @Test
    fun `episodeExistsOnNas -- 404 maps to false (definitively missing)`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(false, client.episodeExistsOnNas(123L).getOrThrow())
    }

    @Test
    fun `episodeExistsOnNas -- 410 maps to false (row present, file gone)`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(410))
        assertEquals(false, client.episodeExistsOnNas(123L).getOrThrow())
    }

    @Test
    fun `episodeExistsOnNas -- 500 returns failure (NOT false)`() = runBlocking {
        // Critical contract: RetentionWorker treats success(false) as
        // "definitively missing, clear archivedAt" and failure(...) as
        // "network glitch, leave it alone". A 5xx must take the second
        // path or we'd loop re-uploading every time the server hiccups.
        server.enqueue(MockResponse().setResponseCode(500))
        val r = client.episodeExistsOnNas(123L)
        assertTrue("5xx must surface as Result.failure, not success(false)", r.isFailure)
    }

    @Test
    fun `episodeExistsOnNas -- 401 returns failure`() = runBlocking {
        // Same contract as 5xx — bad auth is not "missing on backup".
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(client.episodeExistsOnNas(123L).isFailure)
    }

    @Test
    fun `episodeExistsOnNas -- nasUnconfigured returns failure`() = runBlocking {
        settings.setNas("", "")
        val r = client.episodeExistsOnNas(123L)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is NasNotConfiguredException)
        // No HTTP attempt should have happened.
        assertEquals(0, server.requestCount)
    }

    // ---- search -----------------------------------------------------------

    @Test
    fun `search -- happy path parses minimal NasEpisode JSON`() = runBlocking {
        server.enqueue(json("""[{"episode_id":1,"title":"A","file_size":100}]"""))
        val r = client.search(q = null, album = null, limit = 50, offset = 0)
        val list = r.getOrThrow()
        assertEquals(1, list.size)
        assertEquals(1L, list[0].episode_id)
        assertEquals("A", list[0].title)
    }

    @Test
    fun `search -- ignores unknown fields (forward compat)`() = runBlocking {
        // Server may grow new fields between releases. The client must
        // not crash when it sees one it doesn't know.
        server.enqueue(
            json("""[{"episode_id":1,"title":"A","file_size":100,"future_field":"unexpected","another":42}]""")
        )
        val list = client.search(null, null).getOrThrow()
        assertEquals(1, list.size)
    }

    @Test
    fun `search -- empty array is success, not failure`() = runBlocking {
        server.enqueue(json("[]"))
        val list = client.search(null, null).getOrThrow()
        assertTrue(list.isEmpty())
    }

    @Test
    fun `search -- 5xx surfaces as failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(client.search(null, null).isFailure)
    }

    @Test
    fun `search -- malformed JSON surfaces as failure (not crash)`() = runBlocking {
        server.enqueue(json("not json at all"))
        val r = client.search(null, null)
        assertTrue(
            "garbled response must be a failed Result, not a thrown exception",
            r.isFailure,
        )
    }

    // ---- listAllEpisodes (paging contract used by NasMirror) --------------

    @Test
    fun `listAllEpisodes -- single short page terminates without a second call`() = runBlocking {
        server.enqueue(json("""[{"episode_id":1,"title":"A","file_size":1}]"""))
        val r = client.listAllEpisodes(pageSize = 200)
        assertEquals(1, r.getOrThrow().size)
        assertEquals("expected exactly one /episodes call", 1, server.requestCount)
    }

    @Test
    fun `listAllEpisodes -- full first page triggers a second page`() = runBlocking {
        val full = (1..2).joinToString(",") { """{"episode_id":$it,"title":"E$it","file_size":1}""" }
        server.enqueue(json("[$full]"))      // pageSize=2 → full → continue
        server.enqueue(json("[]"))            // empty tail → stop
        val r = client.listAllEpisodes(pageSize = 2)
        assertEquals(2, r.getOrThrow().size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `listAllEpisodes -- aggregates across many pages preserving order`() = runBlocking {
        // 3 pages of 2 + a short tail = 7 total.
        server.enqueue(json("""[{"episode_id":1,"title":"a","file_size":1},{"episode_id":2,"title":"b","file_size":1}]"""))
        server.enqueue(json("""[{"episode_id":3,"title":"c","file_size":1},{"episode_id":4,"title":"d","file_size":1}]"""))
        server.enqueue(json("""[{"episode_id":5,"title":"e","file_size":1},{"episode_id":6,"title":"f","file_size":1}]"""))
        server.enqueue(json("""[{"episode_id":7,"title":"g","file_size":1}]"""))
        val r = client.listAllEpisodes(pageSize = 2).getOrThrow()
        assertEquals(7, r.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L), r.map { it.episode_id })
    }

    @Test
    fun `listAllEpisodes -- failure on first page surfaces as failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(client.listAllEpisodes(pageSize = 50).isFailure)
    }

    @Test
    fun `listAllEpisodes -- failure mid-paging surfaces as failure`() = runBlocking {
        // First page OK, second page server tantrum. The client must
        // return failure so NasMirror knows the catalog snapshot is
        // incomplete — not pretend it has full coverage with a partial
        // first page.
        server.enqueue(json("""[{"episode_id":1,"title":"a","file_size":1},{"episode_id":2,"title":"b","file_size":1}]"""))
        server.enqueue(MockResponse().setResponseCode(500))
        val r = client.listAllEpisodes(pageSize = 2)
        assertTrue(
            "partial paging failure must NOT silently truncate — caller relies on completeness",
            r.isFailure,
        )
    }

    @Test
    fun `listAllEpisodes -- requests page with correct offset+limit each time`() = runBlocking {
        server.enqueue(json("""[{"episode_id":1,"title":"a","file_size":1},{"episode_id":2,"title":"b","file_size":1}]"""))
        server.enqueue(json("[]"))
        client.listAllEpisodes(pageSize = 2)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertTrue("first call must include limit=2 and offset=0", first.path!!.contains("limit=2") && first.path!!.contains("offset=0"))
        assertTrue("second call must advance offset past page1", second.path!!.contains("limit=2") && second.path!!.contains("offset=2"))
    }

    @Test
    fun `listAllEpisodes -- bails at the safety cap (no runaway loop)`() = runBlocking {
        // A buggy server that always returns "full" pages of identical
        // rows would otherwise loop forever. Cap at 50k.
        val full = (1..200).joinToString(",") { """{"episode_id":$it,"title":"x","file_size":1}""" }
        repeat(300) { server.enqueue(json("[$full]")) }
        val r = client.listAllEpisodes(pageSize = 200).getOrThrow()
        assertTrue(
            "must bail before 300 pages of 200 — implementation safety cap is 50k",
            r.size <= 50_200,
        )
    }

    // ---- audioUrl (no HTTP — pure URL construction) -----------------------

    @Test
    fun `audioUrl -- builds canonical URL with bearer header`() = runBlocking {
        val a = client.audioUrl(42L).getOrThrow()
        assertTrue("URL must end at the audio sub-resource", a.url.endsWith("/episodes/42/audio"))
        assertEquals("Bearer tok", a.authHeader)
    }

    @Test
    fun `audioUrl -- failure when NAS unconfigured`() = runBlocking {
        settings.setNas("", "")
        val r = client.audioUrl(42L)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is NasNotConfiguredException)
    }

    // ---- helpers ----------------------------------------------------------

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
