package com.odyssey.e2e

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.OdysseyDb
import com.odyssey.scrape.OneplaceClient
import com.odyssey.show.AioOneplaceProvider
import com.odyssey.show.ShowProvider
import com.odyssey.show.YshCatalog
import com.odyssey.show.YshFreeStreamProvider
import com.odyssey.show.YshOneplaceProvider
import com.odyssey.work.DailyCheckWorker
import com.odyssey.work.DownloadEnqueuer
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
 * End-to-end happy-path tests for the daily-check pipeline AFTER the
 * step 9–10 YSH work. Drives the full chain — providers fetch over
 * MockWebServer, DailyCheckWorker upserts into a real Room DB,
 * DownloadEnqueuer records what got queued, and the entity getters
 * read back without throwing on YSH rows.
 *
 * Designed to catch the class of bug that v0.1.37 shipped: a code
 * path the Compose tests didn't exercise (LazyColumn key extractor
 * calling the never-throws-on-AIO `episodeId` getter on a YSH row)
 * crashed the app the first time YSH content flowed end-to-end. A
 * full pipeline run with mixed-provider data is the only way to
 * surface that kind of integration gap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class YshHappyPathEndToEndTest {

    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private lateinit var aioServer: MockWebServer        // /listen + /api for AIO
    private lateinit var yshOneplaceServer: MockWebServer // /listen + /api for YSH
    private lateinit var yshFreeServer: MockWebServer     // /crud/free-streaming
    private lateinit var yshCatalogServer: MockWebServer  // /crud/product/skus

    private lateinit var db: OdysseyDb
    private lateinit var episodes: EpisodeDao
    private lateinit var settings: SettingsRepo
    private lateinit var enqueuer: Recording

    private lateinit var aioOneplace: OneplaceClient
    private lateinit var yshOneplaceClient: OneplaceClient
    private lateinit var catalog: YshCatalog

    @Before
    fun setUp() {
        aioServer = MockWebServer().apply { start() }
        yshOneplaceServer = MockWebServer().apply { start() }
        yshFreeServer = MockWebServer().apply { start() }
        yshCatalogServer = MockWebServer().apply { start() }

        db = Room.inMemoryDatabaseBuilder(ctx, OdysseyDb::class.java)
            .allowMainThreadQueries()
            .build()
        episodes = db.episodes()
        settings = SettingsRepo(ctx)
        runBlocking { settings.clearAllForTest() }
        enqueuer = Recording()

        aioOneplace = OneplaceClient(OkHttpClient()).apply {
            apiUrl = aioServer.url("/api").toString().trimEnd('/')
        }
        yshOneplaceClient = OneplaceClient(OkHttpClient()).apply {
            apiUrl = yshOneplaceServer.url("/api").toString().trimEnd('/')
        }
        catalog = YshCatalog(ctx, OkHttpClient()).apply {
            skusUrl = yshCatalogServer.url("/crud/product/skus").toString().trimEnd('/')
        }
        ctx.filesDir.resolve("ysh/catalog.json").delete()
    }

    @After
    fun tearDown() {
        aioServer.shutdown()
        yshOneplaceServer.shutdown()
        yshFreeServer.shutdown()
        yshCatalogServer.shutdown()
        db.close()
        ctx.filesDir.resolve("ysh/catalog.json").delete()
    }

    @Test
    fun aio_only_user_runs_daily_check_and_sees_rows_plus_enqueued_downloads() = runBlocking {
        // Default enabled = {"aio"} — no YSH wiring needed.
        val aio = AioOneplaceProvider(aioOneplace, com.odyssey.catalog.AioCatalogRepo(ctx)).apply {
            listenUrl = aioServer.url("/listen").toString()
        }
        // AIO server returns the canned fixture page used by the
        // unit suite — 7 episodes on a fresh install.
        aioServer.enqueue(html(fixture("/oneplace/listen.html")))
        aioServer.enqueue(jsonResp(fixture("/oneplace/api_page1.json")))
        aioServer.enqueue(jsonResp(fixture("/oneplace/api_page2.json")))

        val worker = buildWorker(providers = setOf(aio))
        val result = worker.doWork()
        // Worker now publishes the new-row count via WorkInfo.outputData,
        // so Result.success(workDataOf(...)) is NOT structurally equal
        // to Result.success(). Assert the type + the published count
        // — the count is what drives the user-facing snackbar.
        assertTrue(
            "expected success result, got $result",
            result is androidx.work.ListenableWorker.Result.Success,
        )
        assertEquals(
            "7 fresh-install rows must show up in outputData[KEY_NEW_COUNT]",
            7,
            (result as androidx.work.ListenableWorker.Result.Success).outputData
                .getInt(com.odyssey.work.DailyCheckWorker.KEY_NEW_COUNT, -1),
        )

        val rows = episodes.observeAll().first()
        assertEquals(7, rows.size)
        assertTrue(rows.all { it.providerId == "aio" })
        // Every AIO row's episodeId getter parses cleanly (numeric).
        rows.forEach { it.episodeId }   // must not throw
        // Enqueues match — 7 AIO downloads, all keyed by externalId
        // which IS the numeric string for AIO.
        assertEquals(7, enqueuer.calls.size)
        assertTrue(enqueuer.calls.all { it.providerId == "aio" })
    }

    @Test
    fun user_enables_ysh_and_daily_check_ingests_ysh_rows_without_crashing_the_entity_getter() = runBlocking {
        // Pre-load the YSH catalog so YshOneplaceProvider's title-join hits.
        yshCatalogServer.enqueue(jsonResp(fixture("/ysh/catalog-page-1.json")))
        yshCatalogServer.enqueue(jsonResp(fixture("/ysh/catalog-page-2.json")))
        catalog.refresh().getOrThrow()

        // Free-stream provider — returns the captured ~7 free tracks.
        val freeStream = YshFreeStreamProvider(OkHttpClient()).apply {
            freeStreamUrl = yshFreeServer.url("/crud/free-streaming").toString()
        }
        yshFreeServer.enqueue(jsonResp(fixture("/ysh/free-streaming.json")))

        // Oneplace YSH provider — fetches recent broadcasts, title-joins.
        val yshOneplaceProvider = YshOneplaceProvider(
            yshOneplaceClient, catalog, db.yshUnmatched(),
        ).apply {
            listenUrl = yshOneplaceServer.url("/listen").toString()
        }
        // Bootstrap + a single page of episodes including "The Land of
        // Uz" (which the fixture catalog DOES include → catalog hit).
        yshOneplaceServer.enqueue(html("<script>episodeId=1277616</script>"))
        yshOneplaceServer.enqueue(jsonResp(
            """[{
              "episodeId": 1277616,
              "title": "The Land of Uz",
              "subTitle": "May 10, 2026",
              "descriptionHtmlWithoutImages": "Job loses everything.",
              "description": null,
              "downloadFileUrl": "https://zcast/.../1277616.mp3",
              "url": "https://oneplace/.../1277616",
              "durationSeconds": 1800,
              "imageUrl": null
            }]"""))
        yshOneplaceServer.enqueue(jsonResp("[]"))   // pagination end

        // User opts in to YSH (mirrors Settings → Shows toggle).
        settings.setProviderEnabled("ysh", true)

        val worker = buildWorker(providers = setOf(freeStream, yshOneplaceProvider))
        worker.doWork()

        val rows = episodes.observeAll().first()
        assertTrue("YSH rows must be ingested", rows.any { it.providerId == "ysh" })
        // Every YSH row carries a non-null externalId of the right
        // shape AND a non-throwing episodeId getter — this is the
        // regression guard for the v0.1.37 crash.
        val yshRows = rows.filter { it.providerId == "ysh" }
        yshRows.forEach { row ->
            assertNotNull(row.externalId)
            assertTrue(
                "expected ysh-sku- prefix, got ${row.externalId}",
                row.externalId.startsWith("ysh-sku-"),
            )
            // The actual crash guard: must not throw.
            val id = row.episodeId
            assertEquals(row.externalId.hashCode().toLong(), id)
        }
        // YSH download enqueues fire (post-v0.1.37 fix).
        assertTrue(
            "YSH downloads must enqueue too — DownloadEnqueuer is now provider-aware",
            enqueuer.calls.any { it.providerId == "ysh" },
        )
    }

    @Test
    fun refresh_does_NOT_clobber_an_already_downloaded_ysh_row() = runBlocking {
        // Regression for the v0.1.38 user-reported bug: "downloads
        // tries with progress but pressing refresh seems to make it
        // go away". DailyCheckWorker's existing-row dedup only
        // covered AIO; YSH rows got re-upserted on every daily check,
        // wiping filePath / fileSize / downloadedAt.
        //
        // Seed a YSH row that's already been downloaded, then run
        // the daily check twice (the second pass simulates the user
        // hitting Refresh). The downloaded fields must survive.
        episodes.upsert(
            com.odyssey.data.local.LocalEpisodeEntity(
                providerId   = "ysh",
                externalId   = "ysh-sku-1958",
                title        = "Madeleine's Courage",
                airDate      = "2021-06-01",
                description  = "...",
                sourceUrl    = "https://yourstoryhour.org/ee-vol-11",
                downloadUrl  = "https://s3/EE-11-02.mp3",
                filePath     = "/data/odyssey/episodes/ysh/ysh-sku-1958.mp3",
                fileSize     = 1_234_567L,
                durationMs   = 30 * 60_000L,
                downloadedAt = 1L,
                archivedAt   = null,
                albumName    = "Exciting Events - Volume 11",
                albumImageUrl = "https://s3/EE11.jpg",
                albumTrackOrder = 2,
            ),
        )

        // YshFreeStreamProvider that returns a stub list including
        // the same sku — every refresh would normally re-upsert.
        val fakeProvider = object : ShowProvider {
            override val id = "ysh"
            override val displayName = "Your Story Hour"
            override val artistName = "Your Story Hour"
            override suspend fun newSince(
                lastSeenExternalId: String?,
                maxFetch: Int,
            ) = listOf(
                com.odyssey.show.ProviderEpisode(
                    externalId = "ysh-sku-1958",
                    title = "Madeleine's Courage",
                    airDate = "2021-06-01",
                    description = "...",
                    downloadUrl = "https://s3/EE-11-02.mp3",
                    sourceUrl = "https://yourstoryhour.org/ee-vol-11",
                    durationSeconds = 1800,
                    imageUrl = null,
                ),
            )
        }
        settings.setProviderEnabled("ysh", true)

        // First refresh — row already exists, dedup skips upsert.
        buildWorker(setOf(fakeProvider)).doWork()
        val rowAfter1 = episodes.byKey("ysh", "ysh-sku-1958")!!
        assertNotNull(rowAfter1.filePath)
        assertEquals(1_234_567L, rowAfter1.fileSize)

        // Second refresh (simulates user pressing Refresh) — must
        // still skip the upsert.
        buildWorker(setOf(fakeProvider)).doWork()
        val rowAfter2 = episodes.byKey("ysh", "ysh-sku-1958")!!
        assertNotNull(
            "downloaded file path must survive a daily-check refresh",
            rowAfter2.filePath,
        )
        assertEquals(
            "downloaded file size must survive a daily-check refresh",
            1_234_567L,
            rowAfter2.fileSize,
        )
        // Album metadata also intact.
        assertEquals("Exciting Events - Volume 11", rowAfter2.albumName)
        assertEquals(2, rowAfter2.albumTrackOrder)
        // And the row never re-enqueued for download since the dedup
        // covered it.
        assertEquals(0, enqueuer.calls.size)
    }

    @Test
    fun switching_active_show_lets_LazyColumn_key_extractor_run_on_ysh_rows_without_throwing() =
        runBlocking {
            // Seed the DB directly with one AIO + one YSH row — we
            // don't need to drive the full pipeline for this guard;
            // we just want to prove that reading episodeId on either
            // provider's row is safe for downstream callers (the
            // RecentScreen / DownloadedScreen LazyColumn key extractor
            // is the load-bearing real-world caller).
            episodes.upsert(makeRow(providerId = "aio", externalId = "1278294"))
            episodes.upsert(makeRow(providerId = "ysh", externalId = "ysh-sku-1958"))

            val all = episodes.observeAll().first()
            // For each row, run the exact code paths the screens use:
            //   LazyColumn key:    `it.episodeId`
            //   progress map key:  `progress[ep.episodeId]`
            //   expanded set:      `ep.episodeId in expandedIds`
            // Each one calls the getter. None must throw.
            val ids = all.map { it.episodeId }
            assertEquals(2, ids.size)
            assertEquals(2, ids.toSet().size)   // distinct, no collision
            assertTrue(1278294L in ids)
            assertTrue("ysh-sku-1958".hashCode().toLong() in ids)
        }

    // ---------- helpers ---------------------------------------------------

    private fun makeRow(providerId: String, externalId: String) =
        com.odyssey.data.local.LocalEpisodeEntity(
            providerId = providerId,
            externalId = externalId,
            title = "Stub $externalId",
            airDate = null,
            description = null,
            sourceUrl = "x",
            downloadUrl = "y",
            filePath = null,
            fileSize = 0L,
            durationMs = 0L,
            downloadedAt = null,
            archivedAt = null,
        )

    private fun buildWorker(providers: Set<ShowProvider>): DailyCheckWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: android.content.Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = if (workerClassName != DailyCheckWorker::class.java.name) null
            else DailyCheckWorker(
                ctx = appContext,
                params = workerParameters,
                providers = providers,
                episodes = episodes,
                settings = settings,
                scheduler = enqueuer,
            )
        }
        return TestListenableWorkerBuilder.from(ctx, DailyCheckWorker::class.java)
            .setWorkerFactory(factory).build() as DailyCheckWorker
    }

    private fun html(body: String) = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)
    private fun jsonResp(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json").setBody(body)

    private fun fixture(path: String): String =
        YshHappyPathEndToEndTest::class.java.getResource(path)?.readText()
            ?: error("fixture not found: $path")

    private class Recording : DownloadEnqueuer {
        data class Call(val providerId: String, val externalId: String, val allowMetered: Boolean)
        val calls = mutableListOf<Call>()
        override fun enqueueDownload(providerId: String, externalId: String, allowMetered: Boolean) {
            calls += Call(providerId, externalId, allowMetered)
        }
    }
}
