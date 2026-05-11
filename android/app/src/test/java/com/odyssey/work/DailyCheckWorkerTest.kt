package com.odyssey.work

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.OdysseyDb
import com.odyssey.scrape.OneplaceClient
import com.odyssey.show.AioOneplaceProvider
import com.odyssey.show.ProviderEpisode
import com.odyssey.show.ShowProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Highest-leverage uncovered test from the real-use plan (B1).
 *
 * Background: v0.1.1 shipped with a "Check now does nothing" bug
 * that lived in DailyCheckWorker. Up to now the worker had ZERO
 * test coverage — the v0.1.1 regression class could re-ship at any
 * time. This test simulates a full daily-check pass against a live
 * MockWebServer and asserts every observable side-effect:
 *
 *   1. Newly-seen episodes get inserted into the local DB
 *   2. A download is enqueued for each new episode
 *   3. settings.lastSeenEpisodeId is updated to the newest episode
 *   4. settings.lastRunAt is updated
 *   5. Already-known episodes (existing DB rows) are NOT re-enqueued
 *   6. Empty result still updates lastRunAt + returns success
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class DailyCheckWorkerTest {

    private lateinit var ctx: Application
    private lateinit var server: MockWebServer
    private lateinit var oneplace: OneplaceClient
    private lateinit var aioProvider: AioOneplaceProvider
    private lateinit var db: OdysseyDb
    private lateinit var episodes: EpisodeDao
    private lateinit var settings: SettingsRepo
    private lateinit var enqueuer: RecordingEnqueuer

    /**
     * Providers passed to the worker. Default is just AIO (matches the
     * production Hilt graph today). Tests that exercise multi-provider
     * iteration can override before calling buildWorker().
     */
    private var providers: Set<ShowProvider> = emptySet()

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
        oneplace = OneplaceClient(OkHttpClient()).apply {
            // Redirect the API at the MockWebServer (listen URL is now
            // per-provider, redirected on the provider instance below).
            apiUrl = server.url("/api").toString()
        }
        aioProvider = AioOneplaceProvider(oneplace, com.odyssey.catalog.AioCatalogRepo(ctx)).apply {
            listenUrl = server.url("/listen").toString()
        }
        providers = setOf(aioProvider)
        db = Room.inMemoryDatabaseBuilder(ctx, OdysseyDb::class.java).allowMainThreadQueries().build()
        episodes = db.episodes()
        settings = SettingsRepo(ctx)
        // Robolectric reuses the Application across tests, so DataStore
        // file persists. Without this reset, a prior test that set
        // lastSeenEpisodeId would leak into the next test's worker run
        // and short-circuit newSince().
        runBlocking {
            settings.setLastSeen(0L)
            settings.setLastRun(0L)
        }
        enqueuer = RecordingEnqueuer()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun `fresh install ingests new episodes and enqueues downloads`() = runBlocking {
        // Mirror the OneplaceClient sequence: listen page → api page1 → api page2.
        server.enqueue(html(loadFixture("/oneplace/listen.html")))
        server.enqueue(json(loadFixture("/oneplace/api_page1.json")))
        server.enqueue(json(loadFixture("/oneplace/api_page2.json")))

        val worker = buildWorker()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        // The worker scrapes up to 7 on a fresh install (lastSeen == 0).
        val rows = episodes.observeAll().first()
        assertEquals("expected 7 episodes ingested on fresh install", 7, rows.size)
        // All ingested rows should be undownloaded (filePath null).
        assertTrue("rows should not have filePath set yet", rows.all { it.filePath == null })
        // All rows are tagged with their source provider (H-lite).
        assertTrue("all rows should have providerId='aio'", rows.all { it.providerId == "aio" })

        // One download enqueue per episode.
        assertEquals(7, enqueuer.calls.size)
        assertEquals(rows.map { it.episodeId }.toSet(), enqueuer.calls.map { it.episodeId }.toSet())

        // Settings updated. lastSeenEpisodeId is the broadcast number
        // for the newest matched episode — "War of the Words" → 265,
        // because AioOneplaceProvider now resolves CMS ids → broadcast
        // numbers via the catalog. (The test catalog asset has the
        // mapping shipped with the app.)
        val s = settings.flow.first()
        assertEquals(265L, s.lastSeenEpisodeId)
        assertTrue("lastRunAt should have advanced past 0", s.lastRunAtMs > 0L)
    }

    @Test
    fun `subsequent run with no new episodes still updates lastRunAt`() = runBlocking {
        server.enqueue(html("<html>no bootstrap here</html>"))   // → newSince returns []

        // Pre-set lastRunAt to a past value to verify it gets bumped.
        settings.setLastRun(1L)

        val worker = buildWorker()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        assertEquals("no new episodes should be ingested", 0, episodes.observeAll().first().size)
        assertEquals("no enqueues for empty result", 0, enqueuer.calls.size)
        // lastRunAt MUST advance even on no-op so the user can tell
        // "Check now" actually ran.
        val s = settings.flow.first()
        assertTrue("lastRunAt should have advanced past the seed value", s.lastRunAtMs > 1L)
    }

    @Test
    fun `already-seen episodes are not re-enqueued`() = runBlocking {
        // Pre-populate "War of the Words" using the broadcast number
        // (265) — the same id AioOneplaceProvider would resolve via
        // the catalog. existingIds matches on episodeId so the
        // dedupe still works.
        episodes.upsert(
            com.odyssey.data.local.LocalEpisodeEntity(
                episodeId = 265L,
                title = "War of the Words",
                airDate = "May 8, 2026",
                description = null,
                sourceUrl = "x",
                downloadUrl = "x",
                filePath = "/already/downloaded.mp3",
                fileSize = 18000000L,
                durationMs = 25 * 60 * 1000L,
                downloadedAt = 1L,
                archivedAt = null,
            ),
        )
        server.enqueue(html(loadFixture("/oneplace/listen.html")))
        server.enqueue(json(loadFixture("/oneplace/api_page1.json")))
        server.enqueue(json(loadFixture("/oneplace/api_page2.json")))

        val worker = buildWorker()
        worker.doWork()

        // 7 in api results, but the pre-existing 265 shouldn't re-enqueue.
        val ids = enqueuer.calls.map { it.episodeId }
        assertTrue("should NOT re-enqueue download for existing 265", 265L !in ids)
        assertEquals(6, ids.size)
    }

    @Test
    fun `multiple providers each contribute episodes tagged with their providerId`() = runBlocking {
        // AIO still pulls its 7 episodes; a second fake provider returns
        // 2 of its own. Ingest is the union; rows are tagged correctly.
        server.enqueue(html(loadFixture("/oneplace/listen.html")))
        server.enqueue(json(loadFixture("/oneplace/api_page1.json")))
        server.enqueue(json(loadFixture("/oneplace/api_page2.json")))

        val fake = FakeShowProvider(
            id = "fake",
            displayName = "Fake Show",
            artistName = "Fake Show",
            episodes = listOf(
                fakeEpisode(externalId = "9000000001", title = "Fake A"),
                fakeEpisode(externalId = "9000000002", title = "Fake B"),
            ),
        )
        providers = setOf(aioProvider, fake)

        val worker = buildWorker()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val rows = episodes.observeAll().first()
        // 7 AIO + 2 fake = 9 total rows.
        assertEquals(9, rows.size)
        assertEquals(7, rows.count { it.providerId == "aio" })
        assertEquals(2, rows.count { it.providerId == "fake" })

        // Both fake rows enqueued for download just like AIO ones.
        val fakeIds = setOf(9000000001L, 9000000002L)
        assertTrue(
            "fake provider episodes should also be enqueued",
            enqueuer.calls.any { it.episodeId in fakeIds },
        )
        assertEquals(9, enqueuer.calls.size)

        // lastSeen is updated to the AIO newest only (fake provider's
        // state isn't tracked in SettingsRepo in H-lite). Value is
        // the broadcast number 265 — see the fresh-install test for
        // why CMS ids stopped flowing through.
        val s = settings.flow.first()
        assertEquals(265L, s.lastSeenEpisodeId)
    }

    @Test
    fun `upstream error returns retry not failure`() = runBlocking {
        // Server replies with 500 — the listen-page request should fail,
        // newSince() catches and returns empty list. But we want the
        // worker to not poison itself; current behavior treats it as
        // "no new episodes" → success. Lock the contract.
        server.enqueue(MockResponse().setResponseCode(500))

        val worker = buildWorker()
        val result = worker.doWork()
        // Either Success (newSince returned empty) or Retry — both are
        // acceptable; what we DON'T want is a propagated exception that
        // crashes the worker.
        assertTrue(
            "expected Result.success or Result.retry, got $result",
            result == ListenableWorker.Result.success() || result == ListenableWorker.Result.retry(),
        )
    }

    // ---- helpers ---------------------------------------------------------

    private fun buildWorker(): DailyCheckWorker =
        TestListenableWorkerBuilder.from(ctx, DailyCheckWorker::class.java)
            .setWorkerFactory(testWorkerFactory())
            .build() as DailyCheckWorker

    private fun testWorkerFactory(): WorkerFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: android.content.Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? {
            if (workerClassName != DailyCheckWorker::class.java.name) return null
            return DailyCheckWorker(
                ctx = appContext,
                params = workerParameters,
                providers = providers,
                episodes = episodes,
                settings = settings,
                scheduler = enqueuer,
            )
        }
    }

    private fun loadFixture(path: String): String =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: error("fixture not found: $path")

    private fun html(body: String) = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(body)

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    /** Records every enqueueDownload call so the test can assert what got scheduled. */
    private class RecordingEnqueuer : DownloadEnqueuer {
        data class Call(val episodeId: Long, val allowMetered: Boolean)
        val calls = mutableListOf<Call>()
        override fun enqueueDownload(episodeId: Long, allowMetered: Boolean) {
            calls += Call(episodeId, allowMetered)
        }
    }

    /** In-memory ShowProvider that returns a canned episode list — exercises the
     *  worker's multi-provider iteration without needing a second MockWebServer. */
    private class FakeShowProvider(
        override val id: String,
        override val displayName: String,
        override val artistName: String,
        private val episodes: List<ProviderEpisode>,
    ) : ShowProvider {
        override suspend fun newSince(lastSeenExternalId: String?, maxFetch: Int): List<ProviderEpisode> =
            episodes.take(maxFetch)
    }

    private fun fakeEpisode(externalId: String, title: String) = ProviderEpisode(
        externalId = externalId,
        title = title,
        airDate = "May 1, 2026",
        description = null,
        downloadUrl = "https://fake.example/$externalId.mp3",
        sourceUrl = "https://fake.example/$externalId",
        durationSeconds = 1500L,
        imageUrl = null,
    )
}
