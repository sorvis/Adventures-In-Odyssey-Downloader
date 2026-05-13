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
import org.junit.Assert.assertFalse
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
        // file persists. clearAllForTest() wipes everything so each
        // @Test starts from defaults (enabledProviders = {"aio"},
        // lastSeen/lastRun = 0, etc.). Tests that need YSH or a fake
        // provider enabled set that explicitly inside the test body.
        runBlocking { settings.clearAllForTest() }
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
        assertSuccessWithNewCount(result, expected = 7)

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
    fun `refresh after May 8 ingests 3 new episodes that aired May 9-12 (user-reported bug)`() = runBlocking {
        // Reproduces the v0.1.42 user report (screenshot taken 2026-05-12):
        // The user's Recent list ends at broadcast #265 (May 8). Three
        // new episodes have aired on oneplace.com since (#266 May 11,
        // #267 May 12 — plus a May 9 re-air). The user taps Refresh
        // and sees "Refresh complete — no new episodes".
        //
        // Pre-seed the DB with the user's existing AIO rows (#261–#265,
        // externalIds = broadcast numbers since the catalog matches AIO
        // titles), set lastSeenEpisodeId=265, then drive the worker
        // against the live-captured /listen + /api responses for May 12.
        for (n in 261..265) {
            episodes.upsert(
                com.odyssey.data.local.LocalEpisodeEntity(
                    providerId = "aio",
                    externalId = n.toString(),
                    title = "episode $n",
                    airDate = "May ${n - 261 + 4}, 2026",
                    description = null,
                    sourceUrl = "https://oneplace.com/$n",
                    downloadUrl = "https://zcast/$n.mp3",
                    filePath = null,
                    fileSize = 0L,
                    durationMs = 25 * 60_000L,
                    downloadedAt = null,
                    archivedAt = null,
                ),
            )
        }
        settings.setLastSeen(265L)
        val startCount = episodes.observeAll().first().size
        assertEquals(5, startCount)

        // Live captures from oneplace.com on 2026-05-12: latest CMS id
        // 1278386; API returns 7 most-recent episodes incl. 3 newer
        // than what's in the DB.
        server.enqueue(html(loadFixture("/oneplace/listen_may12.html")))
        server.enqueue(json(loadFixture("/oneplace/api_may12_post_265.json")))
        server.enqueue(json("[]"))   // pagination terminator

        val worker = buildWorker()
        val result = worker.doWork()
        assertTrue("expected success result, got $result", result is ListenableWorker.Result.Success)

        val rows = episodes.observeAll().first()
        val newRowCount = rows.size - startCount
        assertTrue(
            "expected at least 2 new rows (the May 11 + May 12 broadcasts); " +
                "got $newRowCount new (total ${rows.size}). " +
                "User-reported bug: refresh says 'no new episodes' even though oneplace has shipped new ones.",
            newRowCount >= 2,
        )
        // The published newCount MUST match what actually got ingested —
        // this is the field the UI uses for the "Refresh complete — N
        // new episodes" snackbar. If the worker miscounts, the user
        // sees "no new episodes" even when new rows landed.
        val publishedCount =
            (result as ListenableWorker.Result.Success).outputData
                .getInt(DailyCheckWorker.KEY_NEW_COUNT, -1)
        assertEquals(
            "published newCount in WorkInfo.outputData must equal the actual new-row delta",
            newRowCount, publishedCount,
        )
    }

    @Test
    fun `legacy install with lastSeen=CMS_id still finds new episodes via catalog match`() = runBlocking {
        // Variant of the May-8 bug for users on installs OLDER than the
        // catalog-match feature (broadcast # resolution). Those installs
        // stored `lastSeenEpisodeId` as the oneplace CMS id (e.g.
        // 1278383 for War of the Words) rather than the broadcast
        // number (265). After upgrading, the OneplaceClient walks the
        // recent API page → finds CMS id 1278383 → early-returns with
        // anything newer. Should still produce 3 new rows.
        // Pre-seed the DB with externalIds=CMS ids (legacy migration
        // shape) — mirrors what v3→v4 → v5 would have produced on an
        // older install.
        for ((cmsId, broadcast) in listOf(
            1278383L to 265, 1278382L to 264, 1278381L to 263, 1278380L to 262, 1278379L to 261,
        )) {
            episodes.upsert(
                com.odyssey.data.local.LocalEpisodeEntity(
                    providerId = "aio",
                    externalId = cmsId.toString(),
                    title = "episode $broadcast",
                    airDate = "May ${broadcast - 261 + 4}, 2026",
                    description = null,
                    sourceUrl = "https://oneplace.com/$cmsId",
                    downloadUrl = "https://zcast/$cmsId.mp3",
                    filePath = null,
                    fileSize = 0L,
                    durationMs = 25 * 60_000L,
                    downloadedAt = null,
                    archivedAt = null,
                ),
            )
        }
        // Legacy install — lastSeen was the CMS id, not the broadcast #.
        settings.setLastSeen(1278383L)
        val startCount = episodes.observeAll().first().size

        server.enqueue(html(loadFixture("/oneplace/listen_may12.html")))
        server.enqueue(json(loadFixture("/oneplace/api_may12_post_265.json")))
        server.enqueue(json("[]"))

        buildWorker().doWork()

        val rows = episodes.observeAll().first()
        val newRowCount = rows.size - startCount
        // OneplaceClient.newSince should walk from latest=1278386 down
        // until it hits ep.episodeId == 1278383 → returns the 3 newer
        // ones (1278386, 1278385, 1278295).
        assertEquals(
            "expected 3 new rows for the legacy-CMS-id lastSeen path; got $newRowCount",
            3, newRowCount,
        )
    }

    @Test
    fun `subsequent run with no new episodes still updates lastRunAt`() = runBlocking {
        server.enqueue(html("<html>no bootstrap here</html>"))   // → newSince returns []

        // Pre-set lastRunAt to a past value to verify it gets bumped.
        settings.setLastRun(1L)

        val worker = buildWorker()
        val result = worker.doWork()
        assertSuccessWithNewCount(result, expected = 0)

        assertEquals("no new episodes should be ingested", 0, episodes.observeAll().first().size)
        assertEquals("no enqueues for empty result", 0, enqueuer.calls.size)
        // lastRunAt MUST advance even on no-op so the user can tell
        // "Check now" actually ran.
        val s = settings.flow.first()
        assertTrue("lastRunAt should have advanced past the seed value", s.lastRunAtMs > 1L)
    }

    @Test
    fun `backup-mirror ghost rows get promoted to real ingests when the provider re-fetches them`() = runBlocking {
        // User report 2026-05-13: newly-aired episodes 266/267/268 were
        // already in the DB as backup-mirror ghosts (BrowseNasScreen's
        // mirrorServerEpisodes() pre-inserts every server-side episode
        // with sourceUrl='backup://<id>' to power the Albums "☁ on
        // backup" badge). DailyCheckWorker fetched 268 from oneplace
        // but its `continue` on `(provider, externalId) in existing`
        // left the row stuck with the backup:// sourceUrl + (often
        // null/unparseable) airDate. Recent's v0.1.48 backup-ghost
        // filter then HID the row. Net result: latest episodes
        // invisible.
        //
        // Promotion rule: when an existing row has filePath=null AND
        // sourceUrl LIKE 'backup://%' (a pure ghost), overwrite
        // sourceUrl/downloadUrl/airDate/title/description/imageUrl/
        // durationMs with the provider's data. Preserve filePath/
        // fileSize/downloadedAt/archivedAt so on-phone state and the
        // backup badge stay intact. No download enqueue; newCount
        // stays at 0 — promotion is invisible to the snackbar.
        episodes.upsert(
            com.odyssey.data.local.LocalEpisodeEntity(
                providerId = "aio",
                externalId = "265",
                title = "stale-mirror-title",
                airDate = null,                       // mirror dropped it
                description = null,
                sourceUrl = "backup://265",
                downloadUrl = "backup://265",
                filePath = null,
                fileSize = 100L,                      // server-known size
                durationMs = 0L,
                downloadedAt = null,
                archivedAt = 99999L,                  // backup badge active
            ),
        )

        // AIO fetches 7 episodes from the canned fixture; one of them
        // is broadcast 265 ("War of the Words", aired May 8, 2026).
        server.enqueue(html(loadFixture("/oneplace/listen.html")))
        server.enqueue(json(loadFixture("/oneplace/api_page1.json")))
        server.enqueue(json(loadFixture("/oneplace/api_page2.json")))

        val worker = buildWorker()
        val result = worker.doWork()
        assertTrue(
            "worker should still succeed when no NEW rows landed " +
                "(promotion alone, no ingest)",
            result is androidx.work.ListenableWorker.Result.Success,
        )

        // Promotion preserved on-phone + backup state…
        val promoted = episodes.byKey("aio", "265")!!
        assertEquals(null, promoted.filePath)
        assertEquals(99999L, promoted.archivedAt)         // backup badge unchanged
        // …and refreshed the source-of-truth metadata.
        assertFalse(
            "sourceUrl should no longer be a backup:// stub — Recent's " +
                "ghost filter relies on this to surface the row",
            promoted.sourceUrl.startsWith("backup://"),
        )
        assertEquals(
            "title should match what the provider returned, not the " +
                "stale-mirror-title placeholder",
            "War of the Words",
            promoted.title,
        )
        assertEquals(
            "airDate must come from the provider so Recent can sort it " +
                "chronologically — the ghost had null airDate which sank it",
            "May 8, 2026", promoted.airDate,
        )

        // The fixture returns 7 episodes; we pre-seeded only 265 as a
        // ghost, so the other 6 ARE genuine new ingests and WILL be
        // enqueued. The promoted 265 itself must NOT be among those
        // enqueues — that's the assertion that locks in the promotion
        // semantics (refresh metadata, no re-download).
        val enqueuedExternalIds = enqueuer.calls.map { it.externalId }
        assertFalse(
            "promoted ghost row 265 must NOT trigger a download enqueue " +
                "— the audio is already archived on the NAS, no need to " +
                "re-pull it from oneplace",
            "265" in enqueuedExternalIds,
        )
        assertEquals(
            "the OTHER 6 episodes in the fixture (no prior row) ARE " +
                "genuine new ingests and should enqueue normally",
            6, enqueuer.calls.size,
        )

        // Snackbar reports the 6 actually-new rows; promotion is invisible.
        val publishedCount = (result as androidx.work.ListenableWorker.Result.Success)
            .outputData.getInt(DailyCheckWorker.KEY_NEW_COUNT, -1)
        assertEquals(
            "newCount counts new ingests only — promotion adds 0 to the count",
            6, publishedCount,
        )
    }

    @Test
    fun `promotion does NOT clobber filePath of an already-downloaded row`() = runBlocking {
        // Defense in depth: even if the existing row's sourceUrl looks
        // like 'backup://...' (e.g. user restored it from NAS at some
        // point — RestoreEpisodeWorker would have set filePath), we
        // must NOT enter the promotion path. The filePath != null
        // guard upstream of the promotion block protects against this;
        // this test pins it.
        episodes.upsert(
            com.odyssey.data.local.LocalEpisodeEntity(
                providerId = "aio",
                externalId = "265",
                title = "restored-from-nas",
                airDate = "May 8, 2026",
                description = null,
                sourceUrl = "backup://265",            // came in via Restore
                downloadUrl = "backup://265",
                filePath = "/data/odyssey/aio/265.mp3", // ← on phone!
                fileSize = 18000000L,
                durationMs = 25 * 60 * 1000L,
                downloadedAt = 12345L,
                archivedAt = 67890L,
            ),
        )

        server.enqueue(html(loadFixture("/oneplace/listen.html")))
        server.enqueue(json(loadFixture("/oneplace/api_page1.json")))
        server.enqueue(json(loadFixture("/oneplace/api_page2.json")))

        buildWorker().doWork()

        val stillOnPhone = episodes.byKey("aio", "265")!!
        // filePath untouched — the row is still playable offline.
        assertEquals("/data/odyssey/aio/265.mp3", stillOnPhone.filePath)
        assertEquals(18000000L, stillOnPhone.fileSize)
        assertEquals(12345L, stillOnPhone.downloadedAt)
        // The row's sourceUrl is allowed to stay backup:// here — it's
        // a restored backup, the "ghost filter" doesn't apply (filePath
        // is set, so the v0.1.48 Recent filter lets it through).
    }

    @Test
    fun `already-seen episodes are not re-enqueued`() = runBlocking {
        // Pre-populate "War of the Words" using the broadcast number
        // (265) — the same id AioOneplaceProvider would resolve via
        // the catalog. existingIds matches on episodeId so the
        // dedupe still works.
        episodes.upsert(
            com.odyssey.data.local.LocalEpisodeEntity(
                providerId = "aio",
                externalId = "265",
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
        // Step 9 gate: non-AIO providers only ingest when the user has
        // opted in. Simulate the dropdown's "Manage shows…" enable
        // flow turning the fake provider on.
        settings.setProviderEnabled("fake", true)

        val worker = buildWorker()
        val result = worker.doWork()
        assertSuccessWithNewCount(result, expected = 9)

        val rows = episodes.observeAll().first()
        // 7 AIO + 2 fake = 9 total rows.
        assertEquals(9, rows.size)
        assertEquals(7, rows.count { it.providerId == "aio" })
        assertEquals(2, rows.count { it.providerId == "fake" })

        // DownloadEnqueuer is now provider-aware (post step-after-10).
        // Every ingested row enqueues a download regardless of show.
        assertEquals("7 AIO + 2 fake = 9 downloads enqueued", 9, enqueuer.calls.size)
        assertEquals(
            "fake provider rows are queued under their providerId",
            2,
            enqueuer.calls.count { it.providerId == "fake" },
        )
        assertEquals(
            7,
            enqueuer.calls.count { it.providerId == "aio" },
        )

        // lastSeen is updated to the AIO newest only (fake provider's
        // state isn't tracked in SettingsRepo in H-lite). Value is
        // the broadcast number 265 — see the fresh-install test for
        // why CMS ids stopped flowing through.
        val s = settings.flow.first()
        assertEquals(265L, s.lastSeenEpisodeId)
    }

    @Test
    fun `disabled providers are skipped - opt-in gate honored`() = runBlocking {
        // Two registered providers; only AIO enabled (default).
        val fake = FakeShowProvider(
            id = "fake",
            displayName = "Fake Show",
            artistName = "Fake Show",
            episodes = listOf(fakeEpisode(externalId = "8000000001", title = "Should Not Ingest")),
        )
        providers = setOf(aioProvider, fake)
        server.enqueue(html(loadFixture("/oneplace/listen.html")))
        server.enqueue(json(loadFixture("/oneplace/api_page1.json")))
        server.enqueue(json(loadFixture("/oneplace/api_page2.json")))

        val worker = buildWorker()
        worker.doWork()

        val rows = episodes.observeAll().first()
        // AIO ingests; fake provider is excluded entirely.
        assertTrue("AIO rows should still ingest", rows.any { it.providerId == "aio" })
        assertTrue("fake provider must be excluded", rows.none { it.providerId == "fake" })
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
        // crashes the worker. Compare by type, NOT by structural equality:
        // Result.success(workDataOf(...)) is not equal to Result.success()
        // because Data equality is content-based.
        assertTrue(
            "expected Result.Success or Result.Retry, got $result",
            result is ListenableWorker.Result.Success || result is ListenableWorker.Result.Retry,
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

    /**
     * The worker now embeds the new-row count in WorkInfo.outputData
     * (the canonical worker→UI channel for the "Refresh complete — N
     * new episodes" snackbar). Result.success(workDataOf(...)) is
     * NOT structurally equal to Result.success() because Data
     * equality is content-based, so equality assertions need to look
     * at the type + payload directly.
     */
    private fun assertSuccessWithNewCount(
        result: ListenableWorker.Result,
        expected: Int,
    ) {
        assertTrue("expected success result, got $result", result is ListenableWorker.Result.Success)
        val actual = (result as ListenableWorker.Result.Success).outputData
            .getInt(DailyCheckWorker.KEY_NEW_COUNT, -1)
        assertEquals(
            "WorkInfo.outputData[${DailyCheckWorker.KEY_NEW_COUNT}] should publish $expected; " +
                "the UI reads this directly for the 'Refresh complete' snackbar",
            expected, actual,
        )
    }

    /** Records every enqueueDownload call so the test can assert what got scheduled. */
    private class RecordingEnqueuer : DownloadEnqueuer {
        data class Call(
            val providerId: String,
            val externalId: String,
            val episodeId: Long,
            val allowMetered: Boolean,
        )
        val calls = mutableListOf<Call>()
        override fun enqueueDownload(providerId: String, externalId: String, allowMetered: Boolean) {
            calls += Call(
                providerId = providerId,
                externalId = externalId,
                episodeId = externalId.toLongOrNull() ?: -1L,
                allowMetered = allowMetered,
            )
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
