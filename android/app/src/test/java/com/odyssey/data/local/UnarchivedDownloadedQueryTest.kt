package com.odyssey.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the AIO-only scope on [EpisodeDao.unarchivedDownloaded] and
 * [EpisodeDao.observeUnarchivedDownloaded].
 *
 * **Why this matters:** the archive-service is AIO-only today.
 * YSH externalIds are non-numeric ("ysh-sku-1958"), so their
 * `episodeId` getter falls back to `String.hashCode().toLong()`.
 * If unarchivedDownloaded returns YSH rows, ArchiveBackfill enqueues
 * `archive-<hash>`. ArchiveEpisodeWorker then calls
 * `episodes.byId(<hash>)` which filters `WHERE providerId = 'aio'`
 * — guaranteed miss → `Result.failure()` + "no row in DB" log spam.
 * Worse, the row stays unarchived so the next snapshot re-yields it,
 * pull-to-refresh kicks it again, and the orphan loops forever.
 *
 * User device logs 2026-05-17 showed 13 such hash-shaped IDs
 * (`469853093, 469853122, ..., 1680575637`) recurring across
 * sessions. This DAO scope is the v0.1.62 fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class UnarchivedDownloadedQueryTest {

    private lateinit var db: OdysseyDb
    private lateinit var episodes: EpisodeDao

    @Before
    fun setUp() {
        val ctx: Application = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, OdysseyDb::class.java)
            .allowMainThreadQueries()
            .build()
        episodes = db.episodes()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `unarchivedDownloaded excludes YSH rows -- archive-service is AIO-only`() = runBlocking {
        episodes.upsert(downloadedRow(providerId = "aio", externalId = "1278389"))
        episodes.upsert(downloadedRow(providerId = "ysh", externalId = "ysh-sku-1958"))
        episodes.upsert(downloadedRow(providerId = "ysh", externalId = "ysh-sku-559"))

        val out = episodes.unarchivedDownloaded()

        assertEquals(
            "only the AIO row passes the filter -- YSH rows would create archive-<hash> " +
                "orphans that ArchiveEpisodeWorker can't resolve",
            listOf("1278389"),
            out.map { it.externalId },
        )
    }

    @Test
    fun `observeUnarchivedDownloaded also excludes YSH rows`() = runBlocking {
        episodes.upsert(downloadedRow(providerId = "aio", externalId = "1278389"))
        episodes.upsert(downloadedRow(providerId = "ysh", externalId = "ysh-sku-1958"))

        val out = episodes.observeUnarchivedDownloaded().first()

        assertEquals(listOf("1278389"), out.map { it.externalId })
    }

    @Test
    fun `already-archived AIO rows are still excluded -- the filter is additive`() = runBlocking {
        episodes.upsert(downloadedRow(providerId = "aio", externalId = "1278389", archivedAt = null))
        episodes.upsert(downloadedRow(providerId = "aio", externalId = "1278388", archivedAt = 1L))

        val out = episodes.unarchivedDownloaded()
        assertEquals(listOf("1278389"), out.map { it.externalId })
    }

    private fun downloadedRow(
        providerId: String,
        externalId: String,
        archivedAt: Long? = null,
    ) = LocalEpisodeEntity(
        providerId = providerId,
        externalId = externalId,
        title = "ep $externalId",
        airDate = "2026-05-17",
        description = null,
        sourceUrl = "https://src/$externalId",
        downloadUrl = "https://dl/$externalId.mp3",
        filePath = "/data/$externalId.mp3",
        fileSize = 1024L,
        durationMs = 30 * 60_000L,
        downloadedAt = 1_700_000_000_000L,
        archivedAt = archivedAt,
        imageUrl = null,
    )
}
