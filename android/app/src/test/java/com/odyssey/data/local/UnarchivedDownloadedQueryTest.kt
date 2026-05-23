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
 * Pins the cross-provider scope on [EpisodeDao.unarchivedDownloaded] +
 * [EpisodeDao.observeUnarchivedDownloaded].
 *
 * **History:** v0.1.62 made these queries AIO-only as a defensive fix.
 * YSH externalIds are non-numeric ("ysh-sku-1958"), and the legacy
 * archive pipeline routed by Long episodeId — YSH rows fell back to
 * `hashCode().toLong()`, never matched `byId(Long)`, and the
 * ArchiveBackfill orphan looped forever.
 *
 * v0.1.72 lifts that filter now that the archive pipeline routes by
 * `(providerId, externalId)` end-to-end via `WorkScheduler.
 * enqueueArchiveByKey` + `ArchiveEpisodeWorker.KEY_PROVIDER_ID/
 * KEY_EXTERNAL_ID` + the server-side v2 endpoint
 * `POST /providers/{provider}/episodes`. YSH rows are now legitimate
 * backfill candidates.
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
    fun `unarchivedDownloaded includes YSH rows -- v0_1_72 multi-provider archive`() = runBlocking {
        // Server now accepts YSH via POST /providers/ysh/episodes;
        // ArchiveBackfill routes by (providerId, externalId) so YSH
        // archives no longer hash-collide. YSH belongs in the backfill
        // candidate pool.
        episodes.upsert(downloadedRow(providerId = "aio", externalId = "1278389"))
        episodes.upsert(downloadedRow(providerId = "ysh", externalId = "ysh-sku-1958"))
        episodes.upsert(downloadedRow(providerId = "ysh", externalId = "ysh-sku-559"))

        val out = episodes.unarchivedDownloaded()
        val ids = out.map { it.externalId }.toSet()
        assertEquals(
            "all three unarchived downloaded rows must surface for backfill",
            setOf("1278389", "ysh-sku-1958", "ysh-sku-559"),
            ids,
        )
    }

    @Test
    fun `observeUnarchivedDownloaded also includes YSH rows`() = runBlocking {
        episodes.upsert(downloadedRow(providerId = "aio", externalId = "1278389"))
        episodes.upsert(downloadedRow(providerId = "ysh", externalId = "ysh-sku-1958"))

        val out = episodes.observeUnarchivedDownloaded().first()
        assertEquals(
            setOf("1278389", "ysh-sku-1958"),
            out.map { it.externalId }.toSet(),
        )
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
