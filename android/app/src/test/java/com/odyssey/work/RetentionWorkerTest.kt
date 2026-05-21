package com.odyssey.work

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.OdysseyDb
import com.odyssey.download.EpisodeDownloader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
import java.io.File

/**
 * Pins the post-v0.1.59 RetentionWorker behavior. The user surfaced a
 * loop where pull-to-refresh re-downloaded recently-archived episodes:
 *
 *   markArchived → enqueueRetention → RetentionWorker.delete(row)
 *   → DailyCheck.existingKeys() misses → row treated as new
 *   → enqueueDownload → archive → retention again
 *
 * Fix locked in here: when the NAS is configured, retention converts
 * pruned rows to backup-mirror ghosts (filePath=null, sourceUrl=
 * "backup://<id>", archivedAt preserved) instead of deleting them.
 * Keeping the row stops DailyCheckWorker from re-ingesting the
 * episode. When the NAS is NOT configured the old delete-the-row
 * behavior stays — there's no backup to fall back on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class RetentionWorkerTest {

    private lateinit var ctx: Application
    private lateinit var db: OdysseyDb
    private lateinit var episodes: EpisodeDao
    private lateinit var settings: SettingsRepo
    private lateinit var downloader: EpisodeDownloader
    private lateinit var nas: com.odyssey.nas.NasClient

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx,
            androidx.work.Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO).build(),
        )
        db = Room.inMemoryDatabaseBuilder(ctx, OdysseyDb::class.java)
            .allowMainThreadQueries().build()
        episodes = db.episodes()
        settings = SettingsRepo(ctx)
        runBlocking {
            settings.clearAllForTest()
            // Default existing assertions run with verify-before-prune
            // disabled so they don't try to reach a fake NAS that
            // isn't standing — verify-on path has its own focused
            // tests below.
            settings.setVerifyBackupBeforePrune(false)
        }
        downloader = EpisodeDownloader(ctx, OkHttpClient())
        nas = com.odyssey.nas.NasClient(settings, OkHttpClient())
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `NAS-configured -- retention converts oldest archived rows to backup ghosts (not delete)`() = runBlocking {
        settings.setNas("http://nas.example", "token")
        settings.setRetention(3)

        // Seed 5 downloaded + archived AIO rows. Use ISO-style airDates
        // so SQL lex-sort = chronological — keeps the test independent
        // of the lex-sort gotcha that production rows hit with
        // "May NN, 2026" strings.
        for (n in 1..5) {
            val file = File(ctx.cacheDir, "ep-$n.mp3").apply { writeText("audio bytes for $n") }
            episodes.upsert(
                LocalEpisodeEntity(
                    providerId = "aio",
                    externalId = "26$n",
                    title = "ep $n",
                    airDate = "2026-05-0$n",
                    description = null,
                    sourceUrl = "https://oneplace.com/26$n",
                    downloadUrl = "https://zcast/26$n.mp3",
                    filePath = file.absolutePath,
                    fileSize = file.length(),
                    durationMs = 25 * 60_000L,
                    downloadedAt = 1000L * n,
                    archivedAt = 2000L * n,
                ),
            )
        }

        val result = buildWorker().doWork()
        assertTrue("worker should succeed", result is ListenableWorker.Result.Success)

        val rows = episodes.observeAll().first().sortedBy { it.externalId }
        assertEquals("row count must stay at 5 — pruning ghosts, not deletes", 5, rows.size)

        // 2 oldest (#261, #262) are pruned to ghost shape; 3 newest
        // (#263, #264, #265) keep their filePath.
        val pruned = rows.filter { it.externalId in setOf("261", "262") }
        for (p in pruned) {
            assertNull("pruned row ${p.externalId} should have filePath nulled", p.filePath)
            assertEquals("pruned row ${p.externalId} sourceUrl should be backup://", "backup://${p.externalId}", p.sourceUrl)
            assertEquals("pruned row ${p.externalId} downloadUrl should be backup://", "backup://${p.externalId}", p.downloadUrl)
            assertNotNull("archivedAt must survive pruning — the NAS still has it", p.archivedAt)
            assertEquals("fileSize cleared", 0L, p.fileSize)
            assertNull("downloadedAt cleared", p.downloadedAt)
        }
        assertFalse("pruned file removed from disk", File(ctx.cacheDir, "ep-1.mp3").exists())
        assertFalse("pruned file removed from disk", File(ctx.cacheDir, "ep-2.mp3").exists())

        val kept = rows.filter { it.externalId in setOf("263", "264", "265") }
        for (k in kept) {
            assertNotNull("kept row ${k.externalId} keeps filePath", k.filePath)
            assertTrue("kept row ${k.externalId} sourceUrl untouched", k.sourceUrl.startsWith("https://"))
        }
    }

    @Test
    fun `NAS-configured -- ghosted row is treated as existing by existingKeys (no re-ingest)`() = runBlocking {
        // The whole point of the change: DailyCheckWorker's existingKeys
        // lookup must still find the row after retention runs, so
        // pull-to-refresh won't enqueue a fresh download. Lock that
        // contract here without spinning up the full DailyCheckWorker.
        settings.setNas("http://nas.example", "token")
        settings.setRetention(1)

        val file = File(ctx.cacheDir, "ep-aio-200.mp3").apply { writeText("audio") }
        episodes.upsert(
            LocalEpisodeEntity(
                providerId = "aio",
                externalId = "200",
                title = "to-be-pruned",
                airDate = "2026-05-01",
                description = null,
                sourceUrl = "https://oneplace.com/200",
                downloadUrl = "https://zcast/200.mp3",
                filePath = file.absolutePath,
                fileSize = file.length(),
                durationMs = 25 * 60_000L,
                downloadedAt = 1L,
                archivedAt = 2L,
            ),
        )
        episodes.upsert(
            LocalEpisodeEntity(
                providerId = "aio",
                externalId = "201",
                title = "keep",
                airDate = "2026-05-02",
                description = null,
                sourceUrl = "https://oneplace.com/201",
                downloadUrl = "https://zcast/201.mp3",
                filePath = File(ctx.cacheDir, "ep-aio-201.mp3").apply { writeText("x") }.absolutePath,
                fileSize = 1L,
                durationMs = 25 * 60_000L,
                downloadedAt = 1L,
                archivedAt = 2L,
            ),
        )

        buildWorker().doWork()

        // existingKeys for the just-pruned externalId must still return it.
        val keys = episodes.existingKeys("aio", listOf("200", "201", "999"))
        assertTrue("pruned row #200 must still surface in existingKeys", "200" in keys)
        assertTrue("non-pruned row #201 still there", "201" in keys)
        assertFalse("unrelated id stays unknown", "999" in keys)
    }

    @Test
    fun `per-provider caps are honored independently (YSH downloads don't squeeze AIO)`() = runBlocking {
        // v0.1.66 regression test for user report: with retention=7 and
        // 6 YSH downloads, only 1 AIO slot fit before retention pruned
        // archived AIO rows out from under the user. Per-provider keys
        // mean each show's ring is sized on its own.
        settings.setNas("http://nas.example", "token")
        settings.setRetentionFor("aio", 5)
        settings.setRetentionFor("ysh", 2)

        // 3 AIO rows downloaded + archived — below AIO cap of 5,
        // none should be pruned.
        for (n in 1..3) {
            val file = File(ctx.cacheDir, "aio-$n.mp3").apply { writeText("x") }
            episodes.upsert(
                LocalEpisodeEntity(
                    providerId = "aio",
                    externalId = "40$n",
                    title = "AIO $n",
                    airDate = "2026-05-0$n",
                    description = null,
                    sourceUrl = "https://oneplace.com/40$n",
                    downloadUrl = "https://zcast/40$n.mp3",
                    filePath = file.absolutePath,
                    fileSize = file.length(),
                    durationMs = 25 * 60_000L,
                    downloadedAt = 1000L * n,
                    archivedAt = 2000L * n,
                ),
            )
        }
        // 4 YSH rows downloaded (never archived — YSH has no NAS path).
        // Cap is 2 → 2 oldest should be pruned (hard-deleted).
        for (n in 1..4) {
            val file = File(ctx.cacheDir, "ysh-$n.mp3").apply { writeText("x") }
            episodes.upsert(
                LocalEpisodeEntity(
                    providerId = "ysh",
                    externalId = "ysh-sku-50$n",
                    title = "YSH $n",
                    airDate = "2026-05-0$n",
                    description = null,
                    sourceUrl = "https://yourstoryhour.org/$n",
                    downloadUrl = "https://s3.example/$n.mp3",
                    filePath = file.absolutePath,
                    fileSize = file.length(),
                    durationMs = 25 * 60_000L,
                    downloadedAt = 1000L * n,
                    archivedAt = null,
                ),
            )
        }

        buildWorker().doWork()

        val rows = episodes.observeAll().first()
        assertEquals(
            "expected 3 AIO untouched + 2 YSH kept (oldest 2 pruned) = 5",
            5, rows.size,
        )
        val aio = rows.filter { it.providerId == "aio" }
        assertEquals("AIO cap=5, 3 rows present — all kept", 3, aio.size)
        assertTrue("AIO rows still have filePath", aio.all { it.filePath != null })

        val ysh = rows.filter { it.providerId == "ysh" }
        assertEquals("YSH cap=2, 4 rows downloaded — 2 oldest deleted", 2, ysh.size)
        // Oldest two YSH (ysh-sku-501, ysh-sku-502) should be GONE — including row.
        assertTrue(
            "YSH cap should leave only the newest 2 ids",
            ysh.map { it.externalId }.toSet() == setOf("ysh-sku-503", "ysh-sku-504"),
        )
        assertFalse("oldest YSH file deleted from disk", File(ctx.cacheDir, "ysh-1.mp3").exists())
        assertFalse("second-oldest YSH file deleted from disk", File(ctx.cacheDir, "ysh-2.mp3").exists())
    }

    @Test
    fun `legacy retention setter still drives AIO cap (migration safety)`() = runBlocking {
        // Pre-v0.1.66 installs called `setRetention(n)` which writes the
        // legacy single key. After upgrade, that legacy value must keep
        // driving the AIO cap (otherwise the user wakes up to a fresh
        // default of 7 silently overriding their preference). Lock the
        // contract by writing through the legacy setter and verifying
        // the per-provider read sees the same number.
        settings.setRetention(15)
        val aioCap = settings.retentionCountFor("aio").first()
        assertEquals(15, aioCap)
    }

    @Test
    fun `NAS not configured -- retention falls back to the legacy delete-row behavior`() = runBlocking {
        // Without a backup to point at, leaving a filePath=null row in
        // the DB is just noise — the old behavior of deleting the row
        // outright is still correct here.
        // (NAS_URL and NAS_TOKEN left blank → settings.nasConfigured = false.)
        settings.setRetention(1)

        for (n in 1..3) {
            val file = File(ctx.cacheDir, "no-nas-$n.mp3").apply { writeText("x") }
            episodes.upsert(
                LocalEpisodeEntity(
                    providerId = "aio",
                    externalId = "30$n",
                    title = "ep $n",
                    airDate = "2026-05-0$n",
                    description = null,
                    sourceUrl = "https://oneplace.com/30$n",
                    downloadUrl = "https://zcast/30$n.mp3",
                    filePath = file.absolutePath,
                    fileSize = file.length(),
                    durationMs = 25 * 60_000L,
                    downloadedAt = 1L,
                    // Note: archivedAt is null because no NAS — but
                    // retention picks from `downloaded` (not just
                    // archived) when nasConfigured is false.
                    archivedAt = null,
                ),
            )
        }

        buildWorker().doWork()

        val remaining = episodes.observeAll().first()
        assertEquals(
            "without NAS, retention deletes the 2 oldest rows outright",
            1, remaining.size,
        )
        assertEquals("303", remaining.single().externalId)
    }

    @Test
    fun `verify-before-prune -- NAS missing leaves row alone and clears archivedAt`() = runBlocking {
        // The whole point of the v0.1.67 verify path: if the NAS HEAD
        // probe says 404/410, RetentionWorker MUST NOT delete the local
        // copy (it's the only one left). Instead it clears archivedAt
        // so ArchiveBackfill re-uploads on the next pass.
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            settings.setNas(server.url("/").toString().trimEnd('/'), "tok")
            settings.setVerifyBackupBeforePrune(true)
            settings.setRetentionFor("aio", 1)
            // Server says "definitively missing" for the HEAD probe.
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(404))

            val file = File(ctx.cacheDir, "verify-miss.mp3").apply { writeText("audio") }
            episodes.upsert(
                LocalEpisodeEntity(
                    providerId = "aio",
                    externalId = "777",
                    title = "would-have-been-pruned",
                    airDate = "2026-05-01",
                    description = null,
                    sourceUrl = "https://oneplace/777",
                    downloadUrl = "https://zcast/777.mp3",
                    filePath = file.absolutePath,
                    fileSize = file.length(),
                    durationMs = 25 * 60_000L,
                    downloadedAt = 1L,
                    archivedAt = 2L,
                ),
            )
            episodes.upsert(
                LocalEpisodeEntity(
                    providerId = "aio",
                    externalId = "778",
                    title = "newer-kept",
                    airDate = "2026-05-02",
                    description = null,
                    sourceUrl = "https://oneplace/778",
                    downloadUrl = "https://zcast/778.mp3",
                    filePath = File(ctx.cacheDir, "verify-keep.mp3").apply { writeText("y") }.absolutePath,
                    fileSize = 1L,
                    durationMs = 25 * 60_000L,
                    downloadedAt = 1L,
                    archivedAt = 2L,
                ),
            )

            buildWorker().doWork()

            // Row 777 still in DB, still has filePath, but archivedAt cleared.
            val survivor = episodes.byKey("aio", "777")!!
            assertNotNull("file path preserved when NAS says missing", survivor.filePath)
            assertTrue("local file still on disk", file.exists())
            assertEquals("archivedAt cleared so backfill re-uploads", null, survivor.archivedAt)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `verify-before-prune -- NAS confirms then row is ghosted as usual`() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            settings.setNas(server.url("/").toString().trimEnd('/'), "tok")
            settings.setVerifyBackupBeforePrune(true)
            settings.setRetentionFor("aio", 1)
            // Server says "yes, it's here."
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200))

            val file = File(ctx.cacheDir, "verify-ok.mp3").apply { writeText("audio") }
            episodes.upsert(
                LocalEpisodeEntity(
                    providerId = "aio",
                    externalId = "888",
                    title = "safe-to-prune",
                    airDate = "2026-05-01",
                    description = null,
                    sourceUrl = "https://oneplace/888",
                    downloadUrl = "https://zcast/888.mp3",
                    filePath = file.absolutePath,
                    fileSize = file.length(),
                    durationMs = 25 * 60_000L,
                    downloadedAt = 1L,
                    archivedAt = 2L,
                ),
            )
            episodes.upsert(
                LocalEpisodeEntity(
                    providerId = "aio",
                    externalId = "889",
                    title = "keeper",
                    airDate = "2026-05-02",
                    description = null,
                    sourceUrl = "https://oneplace/889",
                    downloadUrl = "https://zcast/889.mp3",
                    filePath = File(ctx.cacheDir, "verify-keeper.mp3").apply { writeText("y") }.absolutePath,
                    fileSize = 1L,
                    durationMs = 25 * 60_000L,
                    downloadedAt = 1L,
                    archivedAt = 2L,
                ),
            )

            buildWorker().doWork()

            val ghosted = episodes.byKey("aio", "888")!!
            assertEquals("filePath cleared on verified prune", null, ghosted.filePath)
            assertTrue("sourceUrl rewritten to backup://", ghosted.sourceUrl.startsWith("backup://"))
            assertNotNull("archivedAt preserved — row stays on backup", ghosted.archivedAt)
            assertFalse("local file deleted", file.exists())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `verify-before-prune -- network error skips prune without clearing archivedAt`() = runBlocking {
        // 500-class server error is NOT a definitive "missing on NAS" —
        // backup is probably fine, just unreachable. Skip prune this
        // cycle, leave archivedAt set so backfill doesn't pointlessly
        // re-upload.
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            settings.setNas(server.url("/").toString().trimEnd('/'), "tok")
            settings.setVerifyBackupBeforePrune(true)
            settings.setRetentionFor("aio", 1)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503))

            val file = File(ctx.cacheDir, "verify-flaky.mp3").apply { writeText("audio") }
            episodes.upsert(
                LocalEpisodeEntity(
                    providerId = "aio",
                    externalId = "999",
                    title = "flaky-network",
                    airDate = "2026-05-01",
                    description = null,
                    sourceUrl = "https://oneplace/999",
                    downloadUrl = "https://zcast/999.mp3",
                    filePath = file.absolutePath,
                    fileSize = file.length(),
                    durationMs = 25 * 60_000L,
                    downloadedAt = 1L,
                    archivedAt = 99L,
                ),
            )
            episodes.upsert(
                LocalEpisodeEntity(
                    providerId = "aio",
                    externalId = "1000",
                    title = "keeper",
                    airDate = "2026-05-02",
                    description = null,
                    sourceUrl = "https://oneplace/1000",
                    downloadUrl = "https://zcast/1000.mp3",
                    filePath = File(ctx.cacheDir, "verify-fkeeper.mp3").apply { writeText("y") }.absolutePath,
                    fileSize = 1L,
                    durationMs = 25 * 60_000L,
                    downloadedAt = 1L,
                    archivedAt = 100L,
                ),
            )

            buildWorker().doWork()

            val row = episodes.byKey("aio", "999")!!
            assertNotNull("filePath preserved on network error", row.filePath)
            assertTrue("file still on disk", file.exists())
            assertEquals("archivedAt left intact (backup IS fine, just unreachable)", 99L, row.archivedAt)
        } finally {
            server.shutdown()
        }
    }

    // ---- helpers ---------------------------------------------------------

    private fun buildWorker(): RetentionWorker =
        TestListenableWorkerBuilder.from(ctx, RetentionWorker::class.java)
            .setWorkerFactory(testWorkerFactory())
            .build() as RetentionWorker

    private fun testWorkerFactory(): WorkerFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: android.content.Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? {
            if (workerClassName != RetentionWorker::class.java.name) return null
            return RetentionWorker(
                ctx = appContext,
                params = workerParameters,
                episodes = episodes,
                downloader = downloader,
                settings = settings,
                nas = nas,
            )
        }
    }
}
