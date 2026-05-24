package com.odyssey.download

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.OdysseyDb
import com.odyssey.work.DownloadEnqueuer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Regression test for the v0.1.52-era YSH "stuck after upgrade" bug:
 *
 *   - Pre-v0.1.51 a worker downloaded all bytes of an episode but
 *     died (or threw in `runCatching {}.getOrElse { Result.retry() }`)
 *     before `episodes.upsert(filePath=...)` ran. Row stays
 *     `filePath = null` with the full file on disk.
 *   - WorkManager retries the worker on exponential backoff (5min →
 *     10min → … capped at 5h). After enough retries the next attempt
 *     is hours away.
 *   - User installs v0.1.51 which has the 416-recovery fix. But the
 *     fix only runs when the worker fires :and the worker is sitting
 *     in deep backoff. Symptom: device looks broken, "Active transfers"
 *     bar stuck at 0%, no progress for hours.
 *
 * Fix: [DownloadReconciler] runs on app launch, finds rows with
 * `filePath = null` whose file already exists on disk with non-zero
 * bytes, and calls [DownloadEnqueuer.kickDownload] to cancel the
 * stuck work and re-enqueue. The new worker runs immediately, hits
 * the 416-recovery path, persists filePath, and the loop ends.
 *
 * The recorder fake distinguishes `enqueue` from `kick` calls so we
 * verify the reconciler is calling the cancel-then-enqueue path, not
 * an ordinary enqueue (which the unique-work + KEEP policy would
 * no-op against the existing stuck work).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class DownloadReconcilerTest {

    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: OdysseyDb
    private lateinit var downloader: EpisodeDownloader
    private lateinit var recorder: RecordingEnqueuer
    private lateinit var archiveRecorder: RecordingArchiveEnqueuer
    private lateinit var reconciler: DownloadReconciler

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, OdysseyDb::class.java)
            .allowMainThreadQueries()
            .build()
        downloader = EpisodeDownloader(ctx = ctx, http = OkHttpClient())
        recorder = RecordingEnqueuer()
        archiveRecorder = RecordingArchiveEnqueuer()
        reconciler = DownloadReconciler(db.episodes(), downloader, recorder, archiveRecorder)
    }

    @After
    fun tearDown() {
        db.close()
        // Clean up the per-test "Episodes/" tree under getExternalFilesDir.
        downloader.rootDir.deleteRecursively()
    }

    @Test
    fun `kicks stuck row whose file is fully on disk but DB still says filePath is null`() = runBlocking {
        val ep = yshRow(externalId = "ysh-sku-559", title = "Child of Privilege", filePath = null)
        db.episodes().upsert(ep)
        // Pretend the previous worker wrote the complete file before dying.
        val onDisk = downloader.fileFor(ep.providerId, ep.externalId, ep.title)
        onDisk.parentFile?.mkdirs()
        onDisk.writeBytes(ByteArray(21_366_117 / 1000) { 0x55 })   // 1/1000 scale stand-in
        assertTrue("test pre-condition: file must exist on disk", onDisk.exists())
        assertTrue("test pre-condition: file must be non-empty", onDisk.length() > 0)

        val kicked = reconciler.reconcile(allowMetered = false)

        assertEquals("exactly one stuck row was kicked", 1, kicked)
        assertEquals("recorder saw exactly one kick call", 1, recorder.kicks.size)
        assertEquals(
            "kick targets the right episode",
            "ysh" to "ysh-sku-559",
            recorder.kicks[0].providerId to recorder.kicks[0].externalId,
        )
        assertEquals("kick honors allowMetered", false, recorder.kicks[0].allowMetered)
        assertEquals(
            "regular enqueueDownload was NOT used (would be no-op'd by KEEP policy)",
            0, recorder.enqueues.size,
        )
    }

    @Test
    fun `ignores rows whose expected file does not exist on disk -- those just download normally`() = runBlocking {
        val ep = yshRow(externalId = "ysh-sku-158", title = "The Land of Uz", filePath = null)
        db.episodes().upsert(ep)
        // Do NOT create the file on disk. This row has never been downloaded.

        val kicked = reconciler.reconcile(allowMetered = false)

        assertEquals("nothing to reconcile :no file on disk means no stuck state", 0, kicked)
        assertEquals(0, recorder.kicks.size)
    }

    @Test
    fun `ignores zero-byte placeholder files -- those are not 'fully downloaded'`() = runBlocking {
        val ep = yshRow(externalId = "ysh-sku-1246", title = "Out of the Jungle", filePath = null)
        db.episodes().upsert(ep)
        // Create an empty file (e.g. a previous worker touched the path but
        // wrote nothing before being killed). Zero bytes is NOT a stuck-
        // complete state :let the normal worker handle it.
        val onDisk = downloader.fileFor(ep.providerId, ep.externalId, ep.title)
        onDisk.parentFile?.mkdirs()
        onDisk.writeBytes(ByteArray(0))

        val kicked = reconciler.reconcile(allowMetered = false)

        assertEquals("zero-byte file is not 'stuck complete' :leave it alone", 0, kicked)
        assertEquals(0, recorder.kicks.size)
    }

    @Test
    fun `ignores rows that already have filePath set -- those are fine`() = runBlocking {
        // Row claims a file path already; reconciler's WHERE filePath IS
        // NULL query won't even return it. Verifies the reconciler is
        // keying off the DB column, not file-on-disk presence.
        val ep = yshRow(
            externalId = "ysh-sku-447",
            title = "The Lady of Longpoint",
            filePath = downloader.fileFor("ysh", "ysh-sku-447", "The Lady of Longpoint").absolutePath,
        )
        db.episodes().upsert(ep)

        val kicked = reconciler.reconcile(allowMetered = false)

        assertEquals("row already marked downloaded -- reconciler stays out of it", 0, kicked)
    }

    @Test
    fun `handles a mix -- only the stuck one gets kicked`() = runBlocking {
        val stuck = yshRow(externalId = "ysh-sku-559", title = "Child of Privilege", filePath = null)
        val notYetDownloaded = yshRow(externalId = "ysh-sku-158", title = "The Land of Uz", filePath = null)
        val alreadyDone = yshRow(externalId = "ysh-sku-2745", title = "Done", filePath = "/already/here.mp3")
        db.episodes().upsert(stuck)
        db.episodes().upsert(notYetDownloaded)
        db.episodes().upsert(alreadyDone)
        downloader.fileFor(stuck.providerId, stuck.externalId, stuck.title)
            .apply { parentFile?.mkdirs() }
            .writeBytes(ByteArray(1024) { 0x11 })

        val kicked = reconciler.reconcile(allowMetered = true)

        assertEquals("only the stuck row gets kicked", 1, kicked)
        assertEquals("ysh-sku-559", recorder.kicks.single().externalId)
        assertEquals("metered flag propagates", true, recorder.kicks.single().allowMetered)
    }

    @Test
    fun `cleanupCrossShowContamination removes AIO rows whose downloadUrl is not for AIO`() = runBlocking {
        // Pre-v0.1.59 leak: oneplace's related-episodes API didn't
        // filter by showId, so Sekulow rows landed in the DB with
        // providerId="aio". On v0.1.59+ launch the cleaner sweeps them.
        val aioReal = aioRow(
            externalId = "1278389",
            title = "The Secret Keys of Discipline",
            downloadUrl = "https://zcast.swncdn.com/episodes/zcast/adventures-in-odyssey/2026/05-11/1278389/777_x.mp3",
            filePath = null,
        )
        val sekulowLeak = aioRow(
            externalId = "1278252",
            title = "Sekulow",
            downloadUrl = "https://zcast.swncdn.com/episodes/zcast/jay-sekulow-live/2026/04-16/1278252/663_x.mp3",
            filePath = "/tmp/sekulow-leak.mp3",
        )
        // Touch the leak file so we can verify the cleaner deletes it.
        java.io.File(sekulowLeak.filePath!!).writeBytes(byteArrayOf(0x49, 0x44, 0x33))
        db.episodes().upsert(aioReal)
        db.episodes().upsert(sekulowLeak)

        val removed = reconciler.cleanupCrossShowContamination()

        assertEquals("only the Sekulow leak was removed", 1, removed)
        assertEquals(
            "real AIO row stays in the DB",
            "1278389",
            db.episodes().byKey("aio", "1278389")?.externalId,
        )
        assertEquals(
            "Sekulow leak row was deleted from the DB",
            null,
            db.episodes().byKey("aio", "1278252"),
        )
        assertEquals(
            "Sekulow leak file was deleted from disk",
            false,
            java.io.File("/tmp/sekulow-leak.mp3").exists(),
        )
        assertEquals(
            "cancelArchive was called for the leaked episode -- so any pending " +
                "WorkManager archive entry doesn't fire later and spam 'no row in DB'",
            listOf(1278252L),
            archiveRecorder.cancels,
        )
    }

    @Test
    fun `cleanupCrossShowContamination is a no-op on a clean DB`() = runBlocking {
        db.episodes().upsert(aioRow(
            externalId = "1278389",
            title = "Clean AIO Row",
            downloadUrl = "https://zcast.swncdn.com/episodes/zcast/adventures-in-odyssey/2026/05-11/x/777_x.mp3",
            filePath = null,
        ))
        db.episodes().upsert(yshRow("ysh-sku-123", "YSH row", filePath = null))

        val removed = reconciler.cleanupCrossShowContamination()

        assertEquals(0, removed)
        assertEquals(2, db.episodes().observeAll().first().size)
    }

    @Test
    fun `cleanupCrossShowContamination preserves backup mirror ghost rows`() = runBlocking {
        // Regression test for the v0.1.63 → v0.1.64 fix: RetentionWorker
        // converts pruned NAS-backed rows to backup-mirror ghosts
        // (sourceUrl/downloadUrl="backup://<id>"). The pre-v0.1.64
        // cleaner matched on "downloadUrl does NOT contain
        // /adventures-in-odyssey/" — which falsely flagged every ghost
        // as cross-show contamination and deleted them on next launch.
        // The user then saw a fresh re-download on the next refresh.
        // BrowseNasScreen.mirrorServerEpisodes creates the same shape,
        // so this preserves those too.
        val realAio = aioRow(
            externalId = "1278389",
            title = "Real AIO row",
            downloadUrl = "https://zcast.swncdn.com/episodes/zcast/adventures-in-odyssey/2026/05-11/1278389/777_x.mp3",
            filePath = "/tmp/aio-real.mp3",
        )
        val ghost = aioRow(
            externalId = "269",
            title = "ghosted-after-retention",
            downloadUrl = "backup://269",  // ← legitimate, must not be swept
            filePath = null,
        )
        val sekulow = aioRow(
            externalId = "1278252",
            title = "Sekulow leak",
            downloadUrl = "https://zcast.swncdn.com/episodes/zcast/jay-sekulow-live/2026/04-16/x/663_x.mp3",
            filePath = null,
        )
        db.episodes().upsert(realAio)
        db.episodes().upsert(ghost)
        db.episodes().upsert(sekulow)

        val removed = reconciler.cleanupCrossShowContamination()

        assertEquals("only the real Sekulow contamination should be removed", 1, removed)
        assertEquals(
            "backup-mirror ghost stays in the DB",
            "269",
            db.episodes().byKey("aio", "269")?.externalId,
        )
        assertEquals(
            "real AIO row stays in the DB",
            "1278389",
            db.episodes().byKey("aio", "1278389")?.externalId,
        )
        assertEquals(
            "Sekulow leak still gets cleaned",
            null,
            db.episodes().byKey("aio", "1278252"),
        )
    }

    @Test
    fun `cleanupCrossShowContamination ignores YSH rows even if downloadUrl is non-AIO`() = runBlocking {
        // YSH rows' downloadUrls go to yourstoryhour S3 or oneplace's
        // YSH path; neither contains /adventures-in-odyssey/. The
        // cleaner must NOT delete them — it only acts on providerId="aio".
        db.episodes().upsert(LocalEpisodeEntity(
            providerId = "ysh",
            externalId = "ysh-sku-100",
            title = "Some YSH Story",
            airDate = null,
            description = null,
            sourceUrl = "https://yourstoryhour.org/x",
            downloadUrl = "https://your-story-hour.s3.amazonaws.com/documents/mp3s/X.mp3",
            filePath = null,
            fileSize = 0L,
            durationMs = 0L,
            downloadedAt = null,
            archivedAt = null,
            imageUrl = null,
        ))

        val removed = reconciler.cleanupCrossShowContamination()

        assertEquals("YSH rows are out of scope for this AIO-targeted cleaner", 0, removed)
    }

    // ---- backup:// row skip (regression for user log 2026-05-24) -------

    @Test
    fun `reconcile skips backup-ghost rows even if a stale file lingers at the canonical path`() = runBlocking {
        // Pre-v0.1.75: allUndownloaded() returns every row with
        // filePath IS NULL, which INCLUDES backup-ghost rows. If a
        // stale file lingered at downloader.fileFor(...) (a previous
        // download cycle whose delete() failed, a partial-restore
        // crash, manual file management, etc.), the reconciler kicked
        // a DownloadEpisodeWorker for the ghost. The worker then
        // crashed on the backup:// URL and burned ~10h of retries —
        // see device log 2026-05-24, ysh-sku-447 "The Lady of Longpoint".
        //
        // v0.1.75 guard: skip rows whose downloadUrl starts with
        // "backup://". Those rows are intentional NAS pointers, not
        // stuck downloads.
        val ep = yshRow(
            externalId = "ysh-sku-447",
            title = "The Lady of Longpoint",
            filePath = null,
        ).copy(
            downloadUrl = "backup://ysh-sku-447",
            sourceUrl = "backup://ysh-sku-447",
            archivedAt = 1_700_000_000_000L,
        )
        db.episodes().upsert(ep)
        // Stale file at the canonical path — pre-fix this would have
        // triggered kickDownload.
        val onDisk = downloader.fileFor(ep.providerId, ep.externalId, ep.title)
        onDisk.parentFile?.mkdirs()
        onDisk.writeBytes(ByteArray(1024) { 0x42 })

        val kicked = reconciler.reconcile(allowMetered = false)

        assertEquals("backup-ghost rows must not be treated as stuck downloads", 0, kicked)
        assertEquals(
            "scheduler.kickDownload must NOT be called for a backup-ghost row",
            0, recorder.kicks.size,
        )
    }

    @Test
    fun `reconcile still kicks legitimately stuck rows when a backup-ghost row sits alongside`() = runBlocking {
        // Defense against an overly broad skip: a real stuck row
        // (CDN downloadUrl, filePath=null, complete file on disk)
        // must still get kicked even if a backup-ghost row is in the
        // same allUndownloaded() snapshot.
        val ghost = yshRow(
            externalId = "ysh-sku-447",
            title = "The Lady of Longpoint",
            filePath = null,
        ).copy(downloadUrl = "backup://ysh-sku-447", archivedAt = 1L)
        db.episodes().upsert(ghost)

        val stuck = yshRow(
            externalId = "ysh-sku-559",
            title = "Child of Privilege",
            filePath = null,
        )
        db.episodes().upsert(stuck)
        val stuckFile = downloader.fileFor(stuck.providerId, stuck.externalId, stuck.title)
        stuckFile.parentFile?.mkdirs()
        stuckFile.writeBytes(ByteArray(2048) { 0x77 })

        val kicked = reconciler.reconcile(allowMetered = false)

        assertEquals("the stuck row gets kicked, the ghost row doesn't", 1, kicked)
        assertEquals(1, recorder.kicks.size)
        assertEquals(
            "kick targets the legitimately stuck row, not the ghost",
            "ysh-sku-559", recorder.kicks[0].externalId,
        )
    }

    // ----- helpers --------------------------------------------------------

    private fun yshRow(externalId: String, title: String, filePath: String?) = LocalEpisodeEntity(
        providerId = "ysh",
        externalId = externalId,
        title = title,
        airDate = null,
        description = null,
        sourceUrl = "https://oneplace.example/$externalId",
        downloadUrl = "https://zcast.example/$externalId.mp3",
        filePath = filePath,
        fileSize = if (filePath != null) 1024L else 0L,
        durationMs = 30 * 60_000L,
        downloadedAt = if (filePath != null) 1_700_000_000_000L else null,
        archivedAt = null,
        imageUrl = null,
    )

    private fun aioRow(
        externalId: String,
        title: String,
        downloadUrl: String,
        filePath: String?,
    ) = LocalEpisodeEntity(
        providerId = "aio",
        externalId = externalId,
        title = title,
        airDate = null,
        description = null,
        sourceUrl = "https://oneplace.com/$externalId",
        downloadUrl = downloadUrl,
        filePath = filePath,
        fileSize = if (filePath != null) 1024L else 0L,
        durationMs = 30 * 60_000L,
        downloadedAt = if (filePath != null) 1_700_000_000_000L else null,
        archivedAt = null,
        imageUrl = null,
    )

    /**
     * Fake [DownloadEnqueuer] that distinguishes `enqueueDownload` from
     * `kickDownload`. Note we override BOTH so the default-impl fallback
     * (kick → enqueue) doesn't quietly cover for a missing kick override.
     */
    private class RecordingEnqueuer : DownloadEnqueuer {
        data class Call(
            val providerId: String,
            val externalId: String,
            val allowMetered: Boolean,
        )
        val enqueues = mutableListOf<Call>()
        val kicks = mutableListOf<Call>()

        override fun enqueueDownload(providerId: String, externalId: String, allowMetered: Boolean) {
            enqueues += Call(providerId, externalId, allowMetered)
        }

        override fun kickDownload(providerId: String, externalId: String, allowMetered: Boolean) {
            kicks += Call(providerId, externalId, allowMetered)
        }
    }

    /**
     * Fake [com.odyssey.work.ArchiveEnqueuer] for the cleanup tests.
     * Records cancelArchive calls so we can assert the reconciler
     * tears down the WorkManager entry alongside the DB row.
     */
    private class RecordingArchiveEnqueuer : com.odyssey.work.ArchiveEnqueuer {
        val enqueues = mutableListOf<Long>()
        val cancels = mutableListOf<Long>()

        override fun enqueueArchiveByKey(providerId: String, externalId: String, allowMetered: Boolean) {
            enqueues += externalId.toLongOrNull() ?: externalId.hashCode().toLong()
        }
        override fun enqueueArchive(episodeId: Long, allowMetered: Boolean) {
            enqueues += episodeId
        }

        override fun cancelArchive(episodeId: Long) {
            cancels += episodeId
        }
    }
}
