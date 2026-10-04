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
    fun `an on-disk file plays locally, never as a stream`() = runBlocking {
        val ok = dispatcher.play(row(filePath = "/data/ep.mp3"))

        assertTrue(ok)
        assertEquals(1, fake.localCalls.size)
        assertTrue("must not also stream", fake.streamCalls.isEmpty())
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
        assertEquals(1, fake.streamCalls.size)
        assertEquals("ysh", fake.streamCalls.single().providerId)
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
            assertTrue(fake.localCalls.isEmpty())
            assertTrue(fake.streamCalls.isEmpty())
        }

    private fun row(
        filePath: String?,
        providerId: String = "aio",
        externalId: String = "123",
        downloadUrl: String = "https://cdn.example/$externalId.mp3",
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
    )

    /** Records what the dispatcher asked the player to do, incl. providerId. */
    private class RecordingPlayer : EpisodePlayer {
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
