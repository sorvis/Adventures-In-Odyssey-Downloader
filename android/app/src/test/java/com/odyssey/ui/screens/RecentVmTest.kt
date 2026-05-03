package com.odyssey.ui.screens

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.PlaybackDao
import com.odyssey.data.local.PlaybackPositionEntity
import com.odyssey.player.EpisodePlayer
import com.odyssey.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins down the dispatch path inside RecentVm.play() — the layer between
 * PlaySourceTest (pure decision) and EpisodeRowTest (UI tap → onPlay).
 *
 * Today: when the user taps Play on a downloaded episode, RecentVm has
 * to call player.playLocal(), NOT playStream(). And vice versa for
 * undownloaded. PlaySourceTest already locks the decision; this test
 * locks that the VM actually wires the decision into Player calls.
 *
 * Uses a fake Player to capture calls. RecentVm's other deps (DAOs,
 * settings, scheduler) are stubbed to no-op since play() doesn't touch
 * them; only viewModelScope's coroutine has to dispatch correctly,
 * which we synchronize via Dispatchers.setMain(UnconfinedTestDispatcher).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class RecentVmTest {

    @Before
    fun setUpMainDispatcher() {
        // viewModelScope launches on Main; UnconfinedTestDispatcher runs
        // continuations inline so we can assert immediately after play().
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `play(downloaded) calls Player playLocal not playStream`() = runTest {
        val fakePlayer = FakePlayer()
        val vm = makeVm(fakePlayer)

        val ep = makeEp(filePath = "/data/odyssey/123.mp3")
        vm.play(ep)

        assertEquals("playLocal must be called once", 1, fakePlayer.playLocalCalls.size)
        assertEquals(ep, fakePlayer.playLocalCalls.single())
        assertTrue("playStream must not fire for downloaded ep", fakePlayer.playStreamCalls.isEmpty())
    }

    @Test
    fun `play(undownloaded) calls Player playStream not playLocal`() = runTest {
        val fakePlayer = FakePlayer()
        val vm = makeVm(fakePlayer)

        val ep = makeEp(filePath = null, downloadUrl = "https://cdn.example/123.mp3")
        vm.play(ep)

        assertEquals("playStream must be called once", 1, fakePlayer.playStreamCalls.size)
        with(fakePlayer.playStreamCalls.single()) {
            assertEquals(ep.episodeId, episodeId)
            assertEquals(ep.downloadUrl, streamUrl)
            assertEquals(ep.title, title)
        }
        assertTrue("playLocal must not fire for undownloaded ep", fakePlayer.playLocalCalls.isEmpty())
    }

    @Test
    fun `play swallows exceptions thrown by Player and logs them`() = runTest {
        val fakePlayer = FakePlayer(throwOnLocal = true)
        val vm = makeVm(fakePlayer)

        // Must not propagate — the user tapping Play shouldn't crash the VM.
        // (DebugLogger captures the stack; that's covered by the logging path
        // itself, not asserted here.)
        vm.play(makeEp(filePath = "/data/odyssey/123.mp3"))

        assertEquals(1, fakePlayer.playLocalCalls.size)
    }

    // -- helpers ---------------------------------------------------------

    private fun makeVm(player: EpisodePlayer): RecentVm = RecentVm(
        ctx = ApplicationProvider.getApplicationContext(),
        episodes = NoopEpisodeDao(),
        playback = NoopPlaybackDao(),
        player = player,
        scheduler = WorkScheduler(ApplicationProvider.getApplicationContext()),
        settings = SettingsRepo(ApplicationProvider.getApplicationContext()),
    )

    private fun makeEp(
        filePath: String? = null,
        downloadUrl: String = "https://cdn.example/x.mp3",
    ) = LocalEpisodeEntity(
        episodeId = 1L,
        title = "Some Episode",
        airDate = "2026-05-08",
        description = null,
        sourceUrl = "https://oneplace.com/x",
        downloadUrl = downloadUrl,
        filePath = filePath,
        fileSize = 0L,
        durationMs = 0L,
        downloadedAt = null,
        archivedAt = null,
    )

    /** Captures calls into the dispatch boundary. */
    private class FakePlayer(
        private val throwOnLocal: Boolean = false,
        private val throwOnStream: Boolean = false,
    ) : EpisodePlayer {
        val playLocalCalls = mutableListOf<LocalEpisodeEntity>()
        data class StreamCall(val episodeId: Long, val streamUrl: String, val title: String)
        val playStreamCalls = mutableListOf<StreamCall>()

        override suspend fun playLocal(ep: LocalEpisodeEntity) {
            playLocalCalls += ep
            if (throwOnLocal) error("simulated playLocal failure")
        }

        override suspend fun playStream(episodeId: Long, streamUrl: String, title: String) {
            playStreamCalls += StreamCall(episodeId, streamUrl, title)
            if (throwOnStream) error("simulated playStream failure")
        }
    }

    private class NoopEpisodeDao : EpisodeDao {
        override fun observeAll(): Flow<List<LocalEpisodeEntity>> = flowOf(emptyList())
        override fun observeDownloaded(): Flow<List<LocalEpisodeEntity>> = flowOf(emptyList())
        override suspend fun byId(id: Long): LocalEpisodeEntity? = null
        override suspend fun existingIds(ids: List<Long>): List<Long> = emptyList()
        override suspend fun upsert(e: LocalEpisodeEntity) {}
        override suspend fun markDownloaded(id: Long, path: String, size: Long, ts: Long) {}
        override suspend fun markArchived(id: Long, ts: Long) {}
        override suspend fun delete(id: Long) {}
        override suspend fun downloadedOldestFirst(): List<LocalEpisodeEntity> = emptyList()
    }

    private class NoopPlaybackDao : PlaybackDao {
        override suspend fun get(id: Long): PlaybackPositionEntity? = null
        override fun observeMostRecent(): Flow<PlaybackPositionEntity?> = flowOf(null)
        override fun observeCompletedIds(): Flow<List<Long>> = flowOf(emptyList())
        override suspend fun upsert(p: PlaybackPositionEntity) {}
    }
}
