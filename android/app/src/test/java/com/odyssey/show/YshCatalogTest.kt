package com.odyssey.show

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavior tests for YshCatalog — drives the catalog pull through a
 * MockWebServer using fixture pages captured from the live
 * yourstoryhour.org /crud/product/skus endpoint (slimmed to 4 albums:
 * 3 English with varied digital_track counts + 1 Spanish to verify
 * the lang_code filter).
 *
 * Robolectric because the implementation uses Android Context for
 * filesDir caching; the underlying logic (parser + normalizer +
 * `buildTracks`) is pure-helper-shaped and also covered by JVM tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class YshCatalogTest {

    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private lateinit var catalog: YshCatalog

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        catalog = YshCatalog(ctx, OkHttpClient())
        catalog.skusUrl = server.url("/crud/product/skus").toString().trimEnd('/')
        // Robolectric reuses the Application — wipe the on-disk cache
        // between tests so load() doesn't surface a previous test's
        // index by accident.
        ctx.filesDir.resolve("ysh/catalog.json").delete()
    }

    @After
    fun tearDown() {
        server.shutdown()
        ctx.filesDir.resolve("ysh/catalog.json").delete()
    }

    @Test
    fun `refresh paginates until empty page and populates state with English-only tracks`() = runBlocking {
        server.enqueue(json(fixture("/ysh/catalog-page-1.json")))
        server.enqueue(json(fixture("/ysh/catalog-page-2.json")))  // empty → stop

        val count = catalog.refresh().getOrThrow()

        // Fixture: 3 English albums (24 + 6 + 12 = 42 tracks). The
        // Spanish album's 8 digital_track SKUs are dropped by the
        // lang_code filter.
        assertEquals(42, count)
        val state = catalog.state.value
        assertNotNull("state should populate after a successful refresh", state)
        assertEquals(42, state!!.tracks.size)
        assertTrue(
            "no Spanish-album tracks should leak through the lang_code filter",
            state.tracks.none { it.albumTitle.contains("Pasión") },
        )
    }

    @Test
    fun `lookup hits a known title via normalized matching`() = runBlocking {
        server.enqueue(json(fixture("/ysh/catalog-page-1.json")))
        server.enqueue(json(fixture("/ysh/catalog-page-2.json")))
        catalog.refresh().getOrThrow()

        // "The Land of Uz" appears in Bible Comes Alive Album 4 of
        // the fixture. Normalized title round-trips through lookup.
        val hit = catalog.lookup("The Land of Uz")
        assertNotNull("normalized title should hit", hit)
        assertEquals("Bible Comes Alive - Album 4", hit!!.albumTitle)
        assertTrue(
            "album cover URL should flow through to the matched track",
            hit.albumImageUrl?.contains("BibleComesAlive") == true,
        )

        // Punctuation drift survives normalization.
        assertEquals(hit, catalog.lookup("the land of uz!"))
        assertEquals(hit, catalog.lookup("The   Land   of  Uz  "))
    }

    @Test
    fun `lookup returns null on miss`() = runBlocking {
        server.enqueue(json(fixture("/ysh/catalog-page-1.json")))
        server.enqueue(json(fixture("/ysh/catalog-page-2.json")))
        catalog.refresh().getOrThrow()
        assertNull(catalog.lookup("Not a real YSH episode"))
    }

    @Test
    fun `lookup returns null before any refresh has loaded an index`() {
        // Fresh state with no on-disk cache and no refresh yet.
        assertNull(catalog.lookup("The Land of Uz"))
    }

    @Test
    fun `refresh persists to disk and load brings it back`() = runBlocking {
        server.enqueue(json(fixture("/ysh/catalog-page-1.json")))
        server.enqueue(json(fixture("/ysh/catalog-page-2.json")))
        catalog.refresh().getOrThrow()
        val before = catalog.state.value!!.tracks.size

        // Spin up a second instance pointing at the same Context —
        // simulates app restart.
        val cold = YshCatalog(ctx, OkHttpClient())
        assertNull("cold instance starts with no in-memory index", cold.state.value)
        cold.load()
        assertEquals(before, cold.state.value?.tracks?.size)
        // The cached file is real JSON.
        val cached = ctx.filesDir.resolve("ysh/catalog.json")
        assertTrue("cached catalog file should be present after refresh", cached.exists())
    }

    @Test
    fun `normalize collapses punctuation and whitespace`() {
        // Spot-check the helper used by the title-join.
        assertEquals("the 14 horse", normalize("The \$14 Horse"))
        assertEquals("child of privilege lottie moon part 1", normalize("Child of Privilege (Lottie Moon Part 1)"))
        assertEquals("smart quotes ok", normalize("“smart” quotes-ok"))
        assertEquals("", normalize("   "))
    }

    @Test
    fun `buildTracks filters to digital_track and English albums`() {
        val albums = listOf(
            YshApiAlbum(
                id = 1, title = "EN album", slug = "en", langCode = "en",
                skus = listOf(
                    YshApiSku(id = 100, title = "MP3",        type = "digital_album"),
                    YshApiSku(id = 101, title = "Story One",  type = "digital_track", orderIndex = 0),
                    YshApiSku(id = 102, title = "Story Two",  type = "digital_track", orderIndex = 1),
                    YshApiSku(id = 103, title = "Audio CD",   type = "physical"),
                ),
            ),
            YshApiAlbum(
                id = 2, title = "ES album", slug = "es", langCode = "es",
                skus = listOf(YshApiSku(id = 200, title = "Una", type = "digital_track")),
            ),
            YshApiAlbum(
                id = 3, title = "lang_code missing → defaults to en", slug = "x", langCode = null,
                skus = listOf(YshApiSku(id = 300, title = "Default EN", type = "digital_track")),
            ),
        )
        val tracks = buildTracks(albums)
        assertEquals(3, tracks.size)
        val titles = tracks.map { it.title }.sorted()
        assertEquals(listOf("Default EN", "Story One", "Story Two"), titles)
    }

    // ----- helpers --------------------------------------------------------

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun fixture(path: String): String =
        YshCatalogTest::class.java.getResource(path)
            ?.readText()
            ?: error("fixture not found: $path")
}
