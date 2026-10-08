package com.odyssey.player

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odyssey.app.SettingsRepo
import com.odyssey.catalog.AioCatalogRepo
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.OdysseyDb
import com.odyssey.show.YshCatalog
import com.odyssey.ui.AlbumNavResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dispatch used to live inside RecentVm, so only the Recent tab could
 * start an arbitrary episode correctly. NowPlayingScreen needs the same
 * logic to resume after Android kills the playback service, so it was
 * extracted here. These pin the routing it inherited.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class EpisodePlayDispatcherTest {

    private lateinit var ctx: Application
    private lateinit var db: OdysseyDb
    private lateinit var fake: RecordingPlayer
    private lateinit var dispatcher: EpisodePlayDispatcher

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, OdysseyDb::class.java)
            .allowMainThreadQueries().build()
        fake = RecordingPlayer()
        val settings = SettingsRepo(ctx)
        runBlocking { settings.clearAllForTest() }
        val aio = AioCatalogRepo(ctx)
        val ysh = YshCatalog(ctx, OkHttpClient())
        dispatcher = EpisodePlayDispatcher(
            player = fake,
            nas = com.odyssey.nas.NasClient(settings, OkHttpClient()),
            catalog = aio,
            yshCatalog = ysh,
            queuePrimer = AlbumQueuePrimer(
                episodes = db.episodes(),
                resolver = AlbumNavResolver(aio, ysh),
                aio = aio,
                ysh = ysh,
                albumQueue = AlbumQueueController(),
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `an on-disk file is queued as a file uri, not re-streamed`() = runBlocking {
        val ok = dispatcher.play(row(filePath = "/data/ep.mp3"))

        assertTrue(ok)
        val (items, startIndex) = fake.albumCalls.single()
        val started = items[startIndex]
        assertTrue(
            "a downloaded episode must play from disk, not the network (got ${started.uri})",
            started.uri.startsWith("file://"),
        )
    }

    @Test
    fun `a CDN row streams with its own providerId, not a hardcoded aio`() = runBlocking {
        // providerId drives the artist string on the lockscreen. The
        // pre-extraction call site omitted it, so YSH streams were
        // labelled as Adventures in Odyssey.
        val ok = dispatcher.play(
            row(filePath = null, providerId = "ysh", externalId = "ysh-sku-5"),
        )

        assertTrue(ok)
        val (items, startIndex) = fake.albumCalls.single()
        val started = items[startIndex]
        assertEquals("ysh", started.providerId)
        assertTrue(
            "should carry the CDN url (got ${started.uri})",
            started.uri.startsWith("https://cdn.example/"),
        )
    }

    @Test
    fun `a backup row with no NAS configured reports failure instead of silently doing nothing`() =
        runBlocking {
            // Settings are cleared, so the NAS can't be resolved. The
            // caller needs to be able to tell this apart from success —
            // a play button that no-ops is what made the original report
            // frustrating.
            val ok = dispatcher.play(row(filePath = null, downloadUrl = "backup://123"))

            assertFalse(ok)
            assertTrue("nothing may be queued when audio cannot be resolved", fake.albumCalls.isEmpty())
        }

    @Test
    fun `playing one episode queues the whole album, in order, starting at that episode`() =
        runBlocking {
            // The point of the playlist change. Before it, the player held
            // exactly one item, so the MediaSession had nothing to advertise
            // and next/previous were missing on lockscreen, Bluetooth and
            // car head units.
            val album = "Bible Comes Alive - Album 3"
            for ((i, name) in listOf("First", "Second", "Third").withIndex()) {
                db.episodes().upsert(
                    row(
                        filePath = "/data/" + (i + 1) + ".mp3",
                        providerId = "ysh",
                        externalId = "ysh-sku-" + (i + 1),
                        albumName = album,
                        albumTrackOrder = i + 1,
                    ).copy(title = name),
                )
            }

            val started = db.episodes().byKey("ysh", "ysh-sku-2")!!
            assertTrue(dispatcher.play(started))

            val (items, startIndex) = fake.albumCalls.single()
            // Titles carry albumTrackOrder 1..3, so this asserts the
            // queue order directly rather than via id arithmetic.
            assertEquals(listOf("First", "Second", "Third"), items.map { it.title })
            assertEquals("must start on the tapped episode", started.episodeId, items[startIndex].episodeId)
        }

    @Test
    fun `an album member whose audio cannot be resolved is dropped, not fatal`() = runBlocking {
        // A pruned ghost with no reachable NAS shouldn't stop the rest of
        // the album from playing — and dropping it must not shift
        // startIndex onto the wrong track.
        val album = "Bible Comes Alive - Album 3"
        db.episodes().upsert(
            row(
                filePath = null, providerId = "ysh", externalId = "ysh-sku-1",
                downloadUrl = "backup://1", albumName = album, albumTrackOrder = 1,
            ),
        )
        db.episodes().upsert(
            row(
                filePath = "/data/2.mp3", providerId = "ysh", externalId = "ysh-sku-2",
                albumName = album, albumTrackOrder = 2,
            ),
        )

        val started = db.episodes().byKey("ysh", "ysh-sku-2")!!
        assertTrue(dispatcher.play(started))

        val (items, startIndex) = fake.albumCalls.single()
        assertEquals("the unresolvable ghost is dropped", 1, items.size)
        assertEquals(started.episodeId, items[startIndex].episodeId)
    }

    private fun row(
        filePath: String?,
        providerId: String = "aio",
        externalId: String = "123",
        downloadUrl: String = "https://cdn.example/$externalId.mp3",
        albumName: String? = null,
        albumTrackOrder: Int? = null,
    ) = LocalEpisodeEntity(
        providerId = providerId,
        externalId = externalId,
        title = "Some Episode",
        airDate = "2026-05-01",
        description = null,
        sourceUrl = "https://example/$externalId",
        downloadUrl = downloadUrl,
        filePath = filePath,
        fileSize = 0L,
        durationMs = 0L,
        downloadedAt = null,
        archivedAt = null,
        albumName = albumName,
        albumTrackOrder = albumTrackOrder,
    )

    /** Records what the dispatcher asked the player to do, incl. providerId. */
    private class RecordingPlayer : EpisodePlayer {
        /** Playlist loads. The dispatcher calls this instead of
         *  playLocal/playStream since the album became a real
         *  ExoPlayer playlist. */
        val albumCalls = mutableListOf<Pair<List<PlayableItem>, Int>>()
        override suspend fun playAlbum(items: List<PlayableItem>, startIndex: Int) {
            albumCalls += items to startIndex
        }

        data class StreamCall(val episodeId: Long, val url: String, val providerId: String)
        val localCalls = mutableListOf<LocalEpisodeEntity>()
        val streamCalls = mutableListOf<StreamCall>()
        override val state = MutableStateFlow(PlayerStateSnapshot.IDLE)

        override suspend fun playLocal(ep: LocalEpisodeEntity, artworkUrl: String?) {
            localCalls += ep
        }

        override suspend fun playStream(
            episodeId: Long,
            streamUrl: String,
            title: String,
            artworkUrl: String?,
            providerId: String,
            description: String?,
        ) {
            streamCalls += StreamCall(episodeId, streamUrl, providerId)
        }

        override suspend fun pause() = Unit
    }
}
