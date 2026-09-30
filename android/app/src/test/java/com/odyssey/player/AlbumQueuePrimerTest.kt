package com.odyssey.player

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odyssey.catalog.AioCatalogRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.OdysseyDb
import com.odyssey.show.YshCatalog
import com.odyssey.ui.AlbumNavResolver
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Auto-advance only fires when the ended episode is in a queue. Before
 * this primer, only the two album-detail screens ever installed one, so
 * a play started from Recent / Recent History / Library ended and simply
 * stopped — the 2026-09-28 report where episode 354 ended and 355 had to
 * be found by hand.
 *
 * These pin the queue the primer installs, and that the queue actually
 * answers "what's next" for the episode you started from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AlbumQueuePrimerTest {

    private lateinit var ctx: Application
    private lateinit var db: OdysseyDb
    private lateinit var episodes: EpisodeDao
    private lateinit var queue: AlbumQueueController
    private lateinit var primer: AlbumQueuePrimer

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, OdysseyDb::class.java)
            .allowMainThreadQueries().build()
        episodes = db.episodes()
        queue = AlbumQueueController()
        val ysh = YshCatalog(ctx, OkHttpClient())
        val aio = AioCatalogRepo(ctx)
        primer = AlbumQueuePrimer(
            episodes = episodes,
            resolver = AlbumNavResolver(aio, ysh),
            aio = aio,
            ysh = ysh,
            albumQueue = queue,
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `primes the album in track order regardless of insert order`() = runBlocking {
        // Inserted shuffled on purpose: the DB's natural order must not
        // be what decides playback order — albumTrackOrder must.
        episodes.upsert(yshRow("ysh-sku-3", "Third", order = 3))
        episodes.upsert(yshRow("ysh-sku-1", "First", order = 1))
        episodes.upsert(yshRow("ysh-sku-2", "Second", order = 2))

        val started = episodes.byKey("ysh", "ysh-sku-2")!!
        val n = primer.primeFor(started)

        assertEquals(3, n)
        assertEquals(
            listOf("ysh-sku-1", "ysh-sku-2", "ysh-sku-3"),
            queue.queue.value.map { it.externalId },
        )
    }

    @Test
    fun `the primed queue answers what plays next after the started episode`() = runBlocking {
        // The end-to-end point: starting from Recent should now roll on
        // to the next track instead of stopping dead.
        episodes.upsert(yshRow("ysh-sku-1", "First", order = 1))
        episodes.upsert(yshRow("ysh-sku-2", "Second", order = 2))

        val started = episodes.byKey("ysh", "ysh-sku-1")!!
        primer.primeFor(started)

        val next = queue.nextAfter(started.episodeId)
        assertEquals("ysh-sku-2", next?.externalId)
    }

    @Test
    fun `stops at the end of the album rather than wrapping or wandering`() = runBlocking {
        episodes.upsert(yshRow("ysh-sku-1", "First", order = 1))
        episodes.upsert(yshRow("ysh-sku-2", "Second", order = 2))

        val last = episodes.byKey("ysh", "ysh-sku-2")!!
        primer.primeFor(last)

        assertNull("end of album must not wrap to the first track", queue.nextAfter(last.episodeId))
    }

    @Test
    fun `only the started episode's album is queued`() = runBlocking {
        episodes.upsert(yshRow("ysh-sku-1", "First", order = 1))
        episodes.upsert(yshRow("ysh-sku-9", "Other", order = 1, album = "Some Other Album"))

        val started = episodes.byKey("ysh", "ysh-sku-1")!!
        primer.primeFor(started)

        assertEquals(listOf("ysh-sku-1"), queue.queue.value.map { it.externalId })
    }

    @Test
    fun `an unresolvable album clears the queue instead of leaving a stale one`() = runBlocking {
        // A row with no album (and no catalog entry to fall back on)
        // must not inherit whatever album was queued previously —
        // otherwise it would auto-advance into an unrelated album.
        episodes.upsert(yshRow("ysh-sku-1", "First", order = 1))
        primer.primeFor(episodes.byKey("ysh", "ysh-sku-1")!!)
        assertTrue(queue.queue.value.isNotEmpty())

        episodes.upsert(yshRow("ysh-sku-77", "Orphan", order = null, album = null))
        val n = primer.primeFor(episodes.byKey("ysh", "ysh-sku-77")!!)

        assertEquals(0, n)
        assertTrue("stale queue must be cleared", queue.queue.value.isEmpty())
    }

    private fun yshRow(
        externalId: String,
        title: String,
        order: Int?,
        album: String? = "Bible Comes Alive - Album 3",
    ) = LocalEpisodeEntity(
        providerId = "ysh",
        externalId = externalId,
        title = title,
        airDate = "2026-05-01",
        description = null,
        sourceUrl = "https://yourstoryhour.org/$externalId",
        downloadUrl = "https://s3.example/$externalId.mp3",
        filePath = null,
        fileSize = 0L,
        durationMs = 0L,
        downloadedAt = null,
        archivedAt = null,
        albumName = album,
        albumTrackOrder = order,
    )
}
