package com.odyssey.download

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.OdysseyDb
import com.odyssey.work.DownloadEnqueuer
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
    private lateinit var reconciler: DownloadReconciler

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, OdysseyDb::class.java)
            .allowMainThreadQueries()
            .build()
        downloader = EpisodeDownloader(ctx = ctx, http = OkHttpClient())
        recorder = RecordingEnqueuer()
        reconciler = DownloadReconciler(db.episodes(), downloader, recorder)
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
}
