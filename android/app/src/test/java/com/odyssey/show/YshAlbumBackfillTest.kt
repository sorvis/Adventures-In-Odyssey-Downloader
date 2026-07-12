package com.odyssey.show

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.OdysseyDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room-backed coverage for [backfillYshAlbums] and the two DAO queries
 * it drives ([EpisodeDao.yshRowsMissingAlbum], [EpisodeDao.setAlbumInfo]).
 *
 * This is the "some YSH have no album listed" fix: rows ingested before
 * album-at-ingest landed carry a null albumName; the backfill fills them
 * from the loaded catalog by skuId so "Go to album" resolves for
 * episodes already on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class YshAlbumBackfillTest {

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
    fun tearDown() = db.close()

    @Test
    fun `backfill fills album metadata onto null-album YSH rows from the catalog`() = runBlocking {
        episodes.upsert(yshRow(skuId = 1958, albumName = null))   // needs backfill
        episodes.upsert(yshRow(skuId = 559, albumName = null))    // needs backfill
        episodes.upsert(yshRow(skuId = 100, albumName = "Kept"))  // already has one

        val catalog = YshCatalogIndex(
            scrapedAtMs = 1L,
            tracks = listOf(
                track(1958, "Exciting Events - Volume 11", order = 2),
                track(559, "Great Stories - Volume 7", order = 0),
                track(100, "Something Else", order = 5),
            ),
        )

        val updated = backfillYshAlbums(episodes, catalog)

        assertEquals("only the two null-album rows are touched", 2, updated)
        assertEquals("Exciting Events - Volume 11", episodes.byKey("ysh", "ysh-sku-1958")!!.albumName)
        assertEquals(2, episodes.byKey("ysh", "ysh-sku-1958")!!.albumTrackOrder)
        assertEquals("Great Stories - Volume 7", episodes.byKey("ysh", "ysh-sku-559")!!.albumName)
        // A row that already had an album is left as-is (not re-derived).
        assertEquals("Kept", episodes.byKey("ysh", "ysh-sku-100")!!.albumName)
    }

    @Test
    fun `rows whose skuId is not in the catalog stay null and are skipped`() = runBlocking {
        episodes.upsert(yshRow(skuId = 7777, albumName = null))

        val catalog = YshCatalogIndex(scrapedAtMs = 1L, tracks = listOf(track(1, "A", order = 0)))
        val updated = backfillYshAlbums(episodes, catalog)

        assertEquals(0, updated)
        assertNull(episodes.byKey("ysh", "ysh-sku-7777")!!.albumName)
    }

    @Test
    fun `yshRowsMissingAlbum returns only ysh rows with a null albumName`() = runBlocking {
        episodes.upsert(yshRow(skuId = 1, albumName = null))
        episodes.upsert(yshRow(skuId = 2, albumName = "Has One"))
        // an AIO row with null albumName must NOT be returned
        episodes.upsert(yshRow(skuId = 3, albumName = null).copy(providerId = "aio", externalId = "12345"))

        val missing = episodes.yshRowsMissingAlbum().map { it.externalId }.toSet()
        assertEquals(setOf("ysh-sku-1"), missing)
    }

    private fun track(skuId: Long, albumTitle: String, order: Int) = YshCatalogTrack(
        skuId = skuId,
        title = "T$skuId",
        albumId = 1L,
        albumTitle = albumTitle,
        albumSlug = albumTitle.lowercase().replace(" ", "-"),
        albumImageUrl = "https://catalog/$skuId.jpg",
        orderIndex = order,
    )

    private fun yshRow(skuId: Long, albumName: String?) = LocalEpisodeEntity(
        providerId = "ysh",
        externalId = "ysh-sku-$skuId",
        title = "Story $skuId",
        airDate = "2021-01-01",
        description = null,
        sourceUrl = "https://src/$skuId",
        downloadUrl = "https://dl/$skuId.mp3",
        filePath = null,
        fileSize = 0L,
        durationMs = 30 * 60_000L,
        downloadedAt = null,
        archivedAt = null,
        imageUrl = null,
        albumName = albumName,
    )
}
