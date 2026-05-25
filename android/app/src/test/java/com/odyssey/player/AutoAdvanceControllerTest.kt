package com.odyssey.player

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.OdysseyDb
import com.odyssey.nas.NasClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AutoAdvanceControllerTest {

    private lateinit var db: OdysseyDb
    private lateinit var fakePlayer: RecordingPlayer
    private lateinit var queue: AlbumQueueController
    private lateinit var nas: NasClient
    private lateinit var controller: AutoAdvanceController

    @Before
    fun setUp() {
        val ctx: Application = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, OdysseyDb::class.java)
            .allowMainThreadQueries()
            .build()
        fakePlayer = RecordingPlayer()
        queue = AlbumQueueController()
        // Real NasClient with no NAS configured. The backup:// branch
        // (audioUrlByKey) returns failure → controller stops the queue.
        // Tests below avoid backup-shaped rows so this never trips.
        val settings = SettingsRepo(ctx).apply { runBlocking { clearAllForTest() } }
        nas = NasClient(settings, OkHttpClient())
        controller = AutoAdvanceController(queue, db.episodes(), nas, fakePlayer)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `empty queue -- onTrackEnded is a no-op and returns false`() = runBlocking {
        val advanced = controller.onTrackEnded(42L)
        assertFalse("empty queue → no advance", advanced)
        assertTrue("player was not invoked", fakePlayer.calls.isEmpty())
    }

    @Test
    fun `local-file row -- controller calls playLocal with the next row`() = runBlocking {
        // Two AIO rows in DB, both on disk. Queue them in order.
        val ep1 = aioRow(externalId = "100", filePath = "/data/100.mp3")
        val ep2 = aioRow(externalId = "101", filePath = "/data/101.mp3")
        db.episodes().upsert(ep1)
        db.episodes().upsert(ep2)
        queue.setQueue(listOf(entry(ep1), entry(ep2)))

        val advanced = controller.onTrackEnded(ep1.episodeId)
        assertTrue(advanced)
        assertEquals(1, fakePlayer.calls.size)
        val call = fakePlayer.calls[0] as RecordingPlayer.Call.PlayLocal
        assertEquals("ep2 plays next", ep2.externalId, call.ep.externalId)
    }

    @Test
    fun `CDN-stream row -- controller calls playStream with the downloadUrl`() = runBlocking {
        // ep1 is on-disk, ep2 is streamable-only (filePath = null,
        // downloadUrl is a CDN URL). When ep1 ends, ep2 should
        // playStream — not playLocal.
        val ep1 = aioRow(externalId = "200", filePath = "/data/200.mp3")
        val ep2 = aioRow(
            externalId = "201",
            filePath = null,
            downloadUrl = "https://cdn.example/201.mp3",
        )
        db.episodes().upsert(ep1)
        db.episodes().upsert(ep2)
        queue.setQueue(listOf(entry(ep1), entry(ep2)))

        controller.onTrackEnded(ep1.episodeId)
        assertEquals(1, fakePlayer.calls.size)
        val call = fakePlayer.calls[0] as RecordingPlayer.Call.PlayStream
        assertEquals(ep2.episodeId, call.episodeId)
        assertEquals("https://cdn.example/201.mp3", call.streamUrl)
        assertEquals("aio", call.providerId)
    }

    @Test
    fun `end of album -- onTrackEnded returns false, no play call`() = runBlocking {
        val ep1 = aioRow(externalId = "300", filePath = "/data/300.mp3")
        db.episodes().upsert(ep1)
        queue.setQueue(listOf(entry(ep1)))

        val advanced = controller.onTrackEnded(ep1.episodeId)
        assertFalse("only entry in queue → no next track", advanced)
        assertTrue(fakePlayer.calls.isEmpty())
    }

    @Test
    fun `current track not in queue -- no advance`() = runBlocking {
        // Mid-album the user tapped a standalone Recent-tab track,
        // played to end. Queue still holds the album rows but the
        // ended track id (999) isn't one of them — must NOT auto-
        // advance into the stale queue.
        val ep1 = aioRow(externalId = "400", filePath = "/data/400.mp3")
        db.episodes().upsert(ep1)
        queue.setQueue(listOf(entry(ep1)))

        val advanced = controller.onTrackEnded(999L)
        assertFalse(advanced)
        assertTrue(fakePlayer.calls.isEmpty())
    }

    @Test
    fun `next row missing from DB -- skip, no play call, no crash`() = runBlocking {
        // Queue references an episode id whose DB row was deleted
        // between queue-set and STATE_ENDED. Controller logs + skips
        // — must not throw.
        val ep1 = aioRow(externalId = "500", filePath = "/data/500.mp3")
        db.episodes().upsert(ep1)
        val ghost = AlbumQueueEntry(
            episodeId = 9999L,
            providerId = "aio",
            externalId = "9999",
        )
        queue.setQueue(listOf(entry(ep1), ghost))

        val advanced = controller.onTrackEnded(ep1.episodeId)
        assertFalse("missing-row entry → no advance", advanced)
        assertTrue(fakePlayer.calls.isEmpty())
    }

    // ---- helpers ----------------------------------------------------

    private fun aioRow(
        externalId: String,
        filePath: String? = null,
        downloadUrl: String = "https://cdn.example/$externalId.mp3",
    ) = LocalEpisodeEntity(
        providerId = "aio",
        externalId = externalId,
        title = "ep-$externalId",
        airDate = null,
        description = null,
        sourceUrl = "https://oneplace.example/$externalId",
        downloadUrl = downloadUrl,
        filePath = filePath,
        fileSize = if (filePath != null) 1024L else 0L,
        durationMs = 1_800_000L,
        downloadedAt = if (filePath != null) 1L else null,
        archivedAt = null,
        imageUrl = null,
    )

    private fun entry(ep: LocalEpisodeEntity) = AlbumQueueEntry(
        episodeId = ep.episodeId,
        providerId = ep.providerId,
        externalId = ep.externalId,
    )

    /** Records every play* invocation for assertion. */
    private class RecordingPlayer : EpisodePlayer {
        sealed interface Call {
            data class PlayLocal(val ep: LocalEpisodeEntity, val artworkUrl: String?) : Call
            data class PlayStream(
                val episodeId: Long,
                val streamUrl: String,
                val title: String,
                val artworkUrl: String?,
                val providerId: String,
            ) : Call
        }
        val calls = mutableListOf<Call>()
        private val _state = MutableStateFlow(PlayerStateSnapshot.IDLE)
        override val state: StateFlow<PlayerStateSnapshot> = _state

        override suspend fun playLocal(ep: LocalEpisodeEntity, artworkUrl: String?) {
            calls += Call.PlayLocal(ep, artworkUrl)
        }
        override suspend fun playStream(
            episodeId: Long,
            streamUrl: String,
            title: String,
            artworkUrl: String?,
            providerId: String,
        ) {
            calls += Call.PlayStream(episodeId, streamUrl, title, artworkUrl, providerId)
        }
        override suspend fun pause() {}
    }
}
