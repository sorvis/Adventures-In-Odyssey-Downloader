package com.odyssey.download

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.OdysseyDb
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Phone-disk layout migration (step 4 of the YSH plan).
 *
 * Legacy AIO downloads sat as bare mp3 files in `<externalFilesDir>/Episodes/`.
 * The YSH-aware build moves them under `Episodes/aio/` so future YSH
 * downloads can sit alongside under `Episodes/ysh/` without colliding.
 *
 * Verified behaviors:
 *   - Legacy files are moved into `aio/`, and the sentinel marker
 *     `.aio-layout-v1` is created.
 *   - DB rows whose `filePath` pointed at a moved file are rewritten
 *     to the new location.
 *   - Re-running the migrator is a no-op once the marker exists.
 *   - Fresh installs (no legacy files) still drop the marker so
 *     subsequent launches skip the scan.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class DiskLayoutMigratorTest {

    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: OdysseyDb
    private lateinit var downloader: EpisodeDownloader
    private lateinit var migrator: DiskLayoutMigrator

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, OdysseyDb::class.java)
            .allowMainThreadQueries()
            .build()
        downloader = EpisodeDownloader(ctx, OkHttpClient())
        migrator = DiskLayoutMigrator(downloader, db.episodes())
        // Make sure rootDir starts empty across @Tests — Robolectric
        // reuses the Application so the external-files dir persists.
        downloader.rootDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @After
    fun tearDown() {
        db.close()
        downloader.rootDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @Test
    fun movesLegacyAioFilesUnderAioSubdir_andUpdatesRowPaths() = runBlocking {
        // Seed the legacy layout: two files directly under Episodes/.
        val legacy1 = File(downloader.rootDir, "1278294-Clutter.mp3").apply { writeText("fake-mp3-1") }
        val legacy2 = File(downloader.rootDir, "657-Camp_What_a_Nut.mp3").apply { writeText("fake-mp3-2") }
        // Corresponding DB rows pointing at those paths.
        db.episodes().upsert(makeRow("1278294", legacy1.absolutePath))
        db.episodes().upsert(makeRow("657", legacy2.absolutePath))

        migrator.migrateIfNeeded()

        val aioDir = File(downloader.rootDir, "aio")
        assertTrue("aio/ subdir must exist", aioDir.isDirectory)
        assertTrue(
            "legacy files must be moved into aio/",
            File(aioDir, "1278294-Clutter.mp3").exists() &&
                File(aioDir, "657-Camp_What_a_Nut.mp3").exists(),
        )
        assertFalse("legacy paths must no longer exist at root", legacy1.exists())
        assertFalse("legacy paths must no longer exist at root", legacy2.exists())
        assertTrue(
            "sentinel marker must exist",
            File(downloader.rootDir, ".aio-layout-v1").exists(),
        )

        // DB filePath columns rewritten.
        val row1 = db.episodes().byKey("aio", "1278294")
        val row2 = db.episodes().byKey("aio", "657")
        assertNotNull(row1); assertNotNull(row2)
        assertEquals(File(aioDir, "1278294-Clutter.mp3").absolutePath, row1!!.filePath)
        assertEquals(File(aioDir, "657-Camp_What_a_Nut.mp3").absolutePath, row2!!.filePath)
    }

    @Test
    fun secondRunIsNoOp_evenIfRootHasNewLegacyFile() = runBlocking {
        // First run: empty rootDir, just drops the marker.
        migrator.migrateIfNeeded()
        assertTrue(File(downloader.rootDir, ".aio-layout-v1").exists())

        // Drop a NEW mp3 at the legacy path post-migration. (Shouldn't
        // happen in practice — DownloadEpisodeWorker writes through
        // fileFor() which now goes into aio/ — but the migrator must
        // not retroactively re-migrate.) Sentinel gate means it stays.
        val sneaky = File(downloader.rootDir, "999-Stray.mp3").apply { writeText("post-marker") }

        migrator.migrateIfNeeded()

        assertTrue("sneaky file should remain at root (no second migration)", sneaky.exists())
        assertFalse(
            "no aio/ should be created on the no-op second run",
            File(downloader.rootDir, "aio").exists(),
        )
    }

    @Test
    fun freshInstallDropsMarkerWithoutCreatingAioDir() = runBlocking {
        // No legacy files in rootDir. Migrator should still drop the
        // marker so subsequent launches skip the file enumeration.
        migrator.migrateIfNeeded()
        assertTrue(File(downloader.rootDir, ".aio-layout-v1").exists())
        assertFalse(
            "fresh installs need no aio/ subdir until first download",
            File(downloader.rootDir, "aio").exists(),
        )
    }

    @Test
    fun rowsWithUnrelatedFilePathsAreNotRewritten() = runBlocking {
        val legacy = File(downloader.rootDir, "1278294-Clutter.mp3").apply { writeText("a") }
        db.episodes().upsert(makeRow("1278294", legacy.absolutePath))
        // A row whose filePath points OUTSIDE rootDir — e.g. a previous
        // dev-mode test path. The migrator must leave it alone.
        val unrelated = "/some/other/place/8888.mp3"
        db.episodes().upsert(makeRow("8888", unrelated))

        migrator.migrateIfNeeded()

        val matched = db.episodes().byKey("aio", "1278294")!!
        val untouched = db.episodes().byKey("aio", "8888")!!
        assertTrue(matched.filePath!!.contains("/aio/"))
        assertEquals(unrelated, untouched.filePath)
    }

    @Test
    fun newFileForLayoutPlacesYshUnderYshSubdir() {
        // Step 4 also exposes the provider-aware fileFor() so YSH
        // downloads land alongside, not interleaved with, AIO. Smoke-
        // test the layout directly.
        val aioFile = downloader.fileFor("aio", "1278294", "Clutter")
        val yshFile = downloader.fileFor("ysh", "ysh-sku-1958", "Madeleine's Courage")
        assertTrue(aioFile.parentFile!!.name == "aio")
        assertTrue(yshFile.parentFile!!.name == "ysh")
        // YSH externalId with non-alphanum chars survives the slugger
        // (the dashes are kept; the prefix is preserved verbatim).
        assertTrue(yshFile.name.startsWith("ysh-sku-1958-"))
    }

    // ----- helpers --------------------------------------------------------

    private fun makeRow(externalId: String, filePath: String) = LocalEpisodeEntity(
        providerId = "aio",
        externalId = externalId,
        title = "Episode $externalId",
        airDate = null,
        description = null,
        sourceUrl = "https://example/$externalId",
        downloadUrl = "https://example/$externalId.mp3",
        filePath = filePath,
        fileSize = 1L,
        durationMs = 0L,
        downloadedAt = 1L,
        archivedAt = null,
    )
}
