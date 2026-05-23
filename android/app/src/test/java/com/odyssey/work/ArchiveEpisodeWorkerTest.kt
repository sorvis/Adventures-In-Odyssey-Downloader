package com.odyssey.work

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.odyssey.app.SettingsRepo
import com.odyssey.catalog.AioCatalogRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.OdysseyDb
import com.odyssey.download.ArchiveProgressTracker
import com.odyssey.nas.NasClient
import kotlinx.coroutines.runBlocking
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
import java.io.File

/**
 * Bad-data coverage for [ArchiveEpisodeWorker] — the path that pushes
 * a downloaded episode to the NAS and marks it archived. Pre-v0.1.69
 * the worker had ZERO tests; bugs in its short-circuit branches
 * (no row in DB, filePath null, already archived, NAS unconfigured,
 * file gone from disk, server 5xx) could land in production untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ArchiveEpisodeWorkerTest {

    private lateinit var ctx: Application
    private lateinit var server: MockWebServer
    private lateinit var db: OdysseyDb
    private lateinit var episodes: EpisodeDao
    private lateinit var settings: SettingsRepo
    private lateinit var nas: NasClient
    private lateinit var progress: ArchiveProgressTracker
    private lateinit var catalog: AioCatalogRepo
    private lateinit var scheduler: WorkScheduler

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx,
            androidx.work.Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO).build(),
        )
        server = MockWebServer().apply { start() }
        db = Room.inMemoryDatabaseBuilder(ctx, OdysseyDb::class.java)
            .allowMainThreadQueries().build()
        episodes = db.episodes()
        settings = SettingsRepo(ctx)
        runBlocking { settings.clearAllForTest() }
        nas = NasClient(settings, OkHttpClient())
        progress = ArchiveProgressTracker()
        catalog = AioCatalogRepo(ctx)
        scheduler = WorkScheduler(ctx)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    // ---- short-circuit branches ------------------------------------------

    @Test
    fun `doWork -- invalid episodeId returns failure`() = runBlocking {
        // Caller didn't pass KEY_EPISODE_ID, or passed 0/negative. The
        // worker must fail fast — there's nothing useful to do.
        val worker = buildWorker(episodeId = 0L)
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Failure)
    }

    @Test
    fun `doWork -- missing DB row returns failure (no panic)`() = runBlocking {
        // Row was deleted between the worker being enqueued and running
        // (e.g. retention pruned it). Failure is the right call: there's
        // no metadata to upload, and re-trying won't help.
        val worker = buildWorker(episodeId = 999L)
        assertTrue(worker.doWork() is ListenableWorker.Result.Failure)
    }

    @Test
    fun `doWork -- row exists but filePath is null returns failure`() = runBlocking {
        // Pre-v0.1.63 retention left rows with filePath=null after
        // pruning. Worker can't upload nothing.
        episodes.upsert(makeRow(externalId = "100", filePath = null))
        val worker = buildWorker(episodeId = 100L)
        assertTrue(worker.doWork() is ListenableWorker.Result.Failure)
    }

    @Test
    fun `doWork -- row already archived short-circuits to success without uploading`() = runBlocking {
        // Idempotency: ArchiveBackfill may re-enqueue an already-archived
        // row when re-archiving everything. Worker must NOT re-upload.
        settings.setNas(server.url("/").toString().trimEnd('/'), "tok")
        val file = File(ctx.cacheDir, "already-archived.mp3").apply { writeText("audio") }
        episodes.upsert(makeRow(
            externalId = "101",
            filePath = file.absolutePath,
            archivedAt = 12345L,
        ))
        val worker = buildWorker(episodeId = 101L)
        val result = worker.doWork()
        assertTrue("must succeed without uploading", result is ListenableWorker.Result.Success)
        assertEquals("zero HTTP requests — server contract preserved", 0, server.requestCount)
    }

    @Test
    fun `doWork -- NAS not configured short-circuits to success (standalone mode)`() = runBlocking {
        // App is designed to work without a NAS — daily download, play,
        // retention all work standalone. ArchiveWorker silently no-ops
        // in this case rather than retrying forever.
        val file = File(ctx.cacheDir, "no-nas.mp3").apply { writeText("audio") }
        episodes.upsert(makeRow(
            externalId = "102",
            filePath = file.absolutePath,
            archivedAt = null,
        ))
        val worker = buildWorker(episodeId = 102L)
        val result = worker.doWork()
        assertTrue("standalone mode → success", result is ListenableWorker.Result.Success)
        // Row should NOT be marked archived since nothing was uploaded.
        val row = episodes.byKey("aio", "102")!!
        assertNull("archivedAt must stay null when no upload happened", row.archivedAt)
    }

    @Test
    fun `doWork -- file gone from disk returns failure`() = runBlocking {
        // The on-disk file was deleted (user cleanup, retention pruned
        // the file but not the row, etc.) between download and archive.
        // Worker fails — it has no bytes to upload.
        settings.setNas(server.url("/").toString().trimEnd('/'), "tok")
        episodes.upsert(makeRow(
            externalId = "103",
            filePath = "/data/odyssey/this-path-does-not-exist.mp3",
        ))
        val worker = buildWorker(episodeId = 103L)
        assertTrue(worker.doWork() is ListenableWorker.Result.Failure)
    }

    // ---- upload outcomes -------------------------------------------------

    @Test
    fun `doWork -- successful upload marks the row archivedAt and returns success`() = runBlocking {
        settings.setNas(server.url("/").toString().trimEnd('/'), "tok")
        val file = File(ctx.cacheDir, "happy.mp3").apply { writeText("audio bytes") }
        episodes.upsert(makeRow(
            externalId = "200",
            filePath = file.absolutePath,
            archivedAt = null,
        ))
        // NasClient.upload accepts 200-201.
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ok":true}"""))

        val result = buildWorker(episodeId = 200L).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val row = episodes.byKey("aio", "200")!!
        assertNotNull("archivedAt set after successful upload", row.archivedAt)
    }

    @Test
    fun `doWork -- server 5xx returns retry (not failure)`() = runBlocking {
        // Transient failure — WorkManager backoff will re-run. If we
        // returned Result.failure() here, the upload would NEVER happen.
        settings.setNas(server.url("/").toString().trimEnd('/'), "tok")
        val file = File(ctx.cacheDir, "retry.mp3").apply { writeText("audio") }
        episodes.upsert(makeRow(
            externalId = "201",
            filePath = file.absolutePath,
            archivedAt = null,
        ))
        server.enqueue(MockResponse().setResponseCode(503))

        val result = buildWorker(episodeId = 201L).doWork()

        assertTrue("5xx must retry, not fail permanently", result is ListenableWorker.Result.Retry)
        val row = episodes.byKey("aio", "201")!!
        assertNull("archivedAt stays null on a failed upload", row.archivedAt)
    }

    @Test
    fun `doWork -- server 4xx auth error returns retry (not silent success)`() = runBlocking {
        // Bad token. Same retry path — the user might fix the token and
        // re-trigger. Crucially the row stays unarchived.
        settings.setNas(server.url("/").toString().trimEnd('/'), "wrong-tok")
        val file = File(ctx.cacheDir, "auth.mp3").apply { writeText("audio") }
        episodes.upsert(makeRow(
            externalId = "202",
            filePath = file.absolutePath,
            archivedAt = null,
        ))
        server.enqueue(MockResponse().setResponseCode(401))

        val result = buildWorker(episodeId = 202L).doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        assertNull(episodes.byKey("aio", "202")!!.archivedAt)
    }

    // ---- helpers ---------------------------------------------------------

    private fun makeRow(
        externalId: String,
        filePath: String? = null,
        archivedAt: Long? = null,
    ) = LocalEpisodeEntity(
        providerId = "aio",
        externalId = externalId,
        title = "ep $externalId",
        airDate = "May 1, 2026",
        description = null,
        sourceUrl = "https://oneplace.com/$externalId",
        downloadUrl = "https://zcast/$externalId.mp3",
        filePath = filePath,
        fileSize = filePath?.let { File(it).takeIf(File::exists)?.length() } ?: 0L,
        durationMs = 25 * 60_000L,
        downloadedAt = if (filePath != null) 1L else null,
        archivedAt = archivedAt,
    )

    private fun buildWorker(episodeId: Long): ArchiveEpisodeWorker =
        TestListenableWorkerBuilder.from(ctx, ArchiveEpisodeWorker::class.java)
            .setInputData(workDataOf(DownloadEpisodeWorker.KEY_EPISODE_ID to episodeId))
            .setWorkerFactory(testWorkerFactory())
            .build() as ArchiveEpisodeWorker

    private fun testWorkerFactory(): WorkerFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: android.content.Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? {
            if (workerClassName != ArchiveEpisodeWorker::class.java.name) return null
            return ArchiveEpisodeWorker(
                ctx = appContext,
                params = workerParameters,
                episodes = episodes,
                nas = nas,
                scheduler = scheduler,
                progress = progress,
                catalog = catalog,
            )
        }
    }
}
