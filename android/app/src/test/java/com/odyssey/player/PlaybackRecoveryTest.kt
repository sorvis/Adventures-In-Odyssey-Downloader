package com.odyssey.player

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.OdysseyDb
import com.odyssey.work.DownloadEnqueuer
import com.odyssey.work.RestoreEnqueuer
import kotlinx.coroutines.runBlocking
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
 * Pins that corrupt-download self-heal works for EVERY provider, not
 * just AIO.
 *
 * **History:** PlaybackRecovery used to resolve the player's media id
 * through `EpisodeDao.byId(Long)`, which hard-codes `providerId='aio'`.
 * YSH externalIds aren't numeric ("ysh-sku-447") so their media id is a
 * `hashCode()` fallback — `byId` never matched, recovery logged "no
 * episode row" and a corrupt YSH file stayed corrupt forever. The
 * resolver now matches on `LocalEpisodeEntity.episodeId` over the
 * downloaded set, which covers both id shapes.
 *
 * Also pins the backup:// routing: a row restored from the NAS carries
 * `downloadUrl = "backup://<id>"`, and DownloadEpisodeWorker fail-fasts
 * on those (v0.1.75) — so recovery has to re-enqueue the RESTORE worker
 * or the deleted file would never come back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PlaybackRecoveryTest {

    private lateinit var db: OdysseyDb
    private lateinit var episodes: EpisodeDao
    private lateinit var settings: SettingsRepo
    private lateinit var downloads: RecordingDownloadEnqueuer
    private lateinit var restores: RecordingRestoreEnqueuer
    private lateinit var recovery: PlaybackRecovery
    private lateinit var tmp: File

    @Before
    fun setUp() {
        val ctx: Application = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, OdysseyDb::class.java)
            .allowMainThreadQueries()
            .build()
        episodes = db.episodes()
        settings = SettingsRepo(ctx)
        // DataStore survives across tests in the class — start clean.
        runBlocking { settings.setAllowMeteredDownloads(false) }
        downloads = RecordingDownloadEnqueuer()
        restores = RecordingRestoreEnqueuer()
        recovery = PlaybackRecovery(episodes, downloads, restores, settings)
        tmp = File(ctx.cacheDir, "recovery-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        tmp.deleteRecursively()
        runBlocking { settings.setAllowMeteredDownloads(false) }
    }

    @Test
    fun `YSH row with a hashed media id self-heals`() = runBlocking {
        val file = writeFile("ysh.mp3", HTML)
        val row = row(
            providerId = "ysh",
            externalId = "ysh-sku-447",
            downloadUrl = "https://s3.example/ysh/447.mp3",
            filePath = file.absolutePath,
        )
        episodes.upsert(row)

        recovery.handleParseError(row.episodeId)

        assertFalse("corrupt file must be deleted", file.exists())
        assertNull(
            "row must fall back to streamable",
            episodes.byKey("ysh", "ysh-sku-447")?.filePath,
        )
        assertEquals(
            listOf(Download("ysh", "ysh-sku-447", allowMetered = false)),
            downloads.calls,
        )
        assertTrue("must not go through the restore worker", restores.calls.isEmpty())
    }

    @Test
    fun `AIO row still self-heals`() = runBlocking {
        val file = writeFile("aio.mp3", HTML)
        val row = row(
            providerId = "aio",
            externalId = "1278294",
            downloadUrl = "https://cdn.example/adventures-in-odyssey/1278294.mp3",
            filePath = file.absolutePath,
        )
        episodes.upsert(row)

        recovery.handleParseError(row.episodeId)

        assertFalse(file.exists())
        assertEquals(
            listOf(Download("aio", "1278294", allowMetered = false)),
            downloads.calls,
        )
    }

    @Test
    fun `backup-sourced row is restored from the NAS, not re-downloaded`() = runBlocking {
        val file = writeFile("restored.mp3", HTML)
        val row = row(
            providerId = "ysh",
            externalId = "ysh-sku-1958",
            downloadUrl = "backup://ysh-sku-1958",
            filePath = file.absolutePath,
        ).copy(
            title = "The Lady of Longpoint",
            airDate = "2017-12-01",
            albumName = "Exciting Events - Volume 17",
            description = "A story.",
            durationMs = 1_500_000,
            archivedAt = 42L,
        )
        episodes.upsert(row)

        recovery.handleParseError(row.episodeId)

        assertFalse(file.exists())
        assertTrue("must not hand a backup:// URL to the download worker", downloads.calls.isEmpty())
        assertEquals(
            listOf(
                Restore(
                    providerId = "ysh",
                    externalId = "ysh-sku-1958",
                    title = "The Lady of Longpoint",
                    airDate = "2017-12-01",
                    album = "Exciting Events - Volume 17",
                    description = "A story.",
                    durationSecs = 1_500L,
                    allowMetered = false,
                ),
            ),
            restores.calls,
        )
    }

    @Test
    fun `a file that really is an mp3 is left alone`() = runBlocking {
        val file = writeFile("good.mp3", ID3)
        val row = row(
            providerId = "ysh",
            externalId = "ysh-sku-559",
            downloadUrl = "https://s3.example/ysh/559.mp3",
            filePath = file.absolutePath,
        )
        episodes.upsert(row)

        recovery.handleParseError(row.episodeId)

        assertTrue("valid MP3 must survive", file.exists())
        assertNotNull(episodes.byKey("ysh", "ysh-sku-559")?.filePath)
        assertTrue(downloads.calls.isEmpty())
        assertTrue(restores.calls.isEmpty())
    }

    @Test
    fun `recovery runs at most once per media id per session`() = runBlocking {
        val file = writeFile("twice.mp3", HTML)
        val row = row(
            providerId = "ysh",
            externalId = "ysh-sku-447",
            downloadUrl = "https://s3.example/ysh/447.mp3",
            filePath = file.absolutePath,
        )
        episodes.upsert(row)

        recovery.handleParseError(row.episodeId)
        // Second failure on the same episode — the re-download may well
        // serve the same junk bytes; retrying forever would loop.
        recovery.handleParseError(row.episodeId)

        assertEquals(1, downloads.calls.size)
    }

    @Test
    fun `media id with no downloaded row is a no-op`() = runBlocking {
        recovery.handleParseError(987_654L)

        assertTrue(downloads.calls.isEmpty())
        assertTrue(restores.calls.isEmpty())
    }

    @Test
    fun `metered preference is honoured`() = runBlocking {
        settings.setAllowMeteredDownloads(true)
        val file = writeFile("metered.mp3", HTML)
        val row = row(
            providerId = "aio",
            externalId = "1278300",
            downloadUrl = "https://cdn.example/adventures-in-odyssey/1278300.mp3",
            filePath = file.absolutePath,
        )
        episodes.upsert(row)

        recovery.handleParseError(row.episodeId)

        assertEquals(
            listOf(Download("aio", "1278300", allowMetered = true)),
            downloads.calls,
        )
    }

    private fun writeFile(name: String, bytes: ByteArray): File =
        File(tmp, name).apply { writeBytes(bytes) }

    private fun row(
        providerId: String,
        externalId: String,
        downloadUrl: String,
        filePath: String?,
    ) = LocalEpisodeEntity(
        providerId = providerId,
        externalId = externalId,
        title = "Episode $externalId",
        airDate = "2026-07-01",
        description = null,
        sourceUrl = downloadUrl,
        downloadUrl = downloadUrl,
        filePath = filePath,
        fileSize = 1024L,
        durationMs = 0L,
        downloadedAt = 1L,
        archivedAt = null,
    )

    private companion object {
        val HTML = "<!DOCTYPE html><html>404</html>".toByteArray()
        val ID3 = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00)
    }
}

private data class Download(
    val providerId: String,
    val externalId: String,
    val allowMetered: Boolean,
)

private data class Restore(
    val providerId: String,
    val externalId: String,
    val title: String,
    val airDate: String?,
    val album: String?,
    val description: String?,
    val durationSecs: Long,
    val allowMetered: Boolean,
)

private class RecordingDownloadEnqueuer : DownloadEnqueuer {
    val calls = mutableListOf<Download>()

    override fun enqueueDownload(providerId: String, externalId: String, allowMetered: Boolean) {
        calls += Download(providerId, externalId, allowMetered)
    }
}

private class RecordingRestoreEnqueuer : RestoreEnqueuer {
    val calls = mutableListOf<Restore>()

    override fun enqueueRestoreByKey(
        providerId: String,
        externalId: String,
        title: String,
        airDate: String?,
        album: String?,
        description: String?,
        durationSecs: Long,
        allowMetered: Boolean,
    ) {
        calls += Restore(
            providerId = providerId,
            externalId = externalId,
            title = title,
            airDate = airDate,
            album = album,
            description = description,
            durationSecs = durationSecs,
            allowMetered = allowMetered,
        )
    }
}
