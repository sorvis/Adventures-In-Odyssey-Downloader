package com.odyssey.ui.screens

import android.app.Application
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.PlaybackDao
import com.odyssey.data.local.PlaybackPositionEntity
import androidx.test.core.app.ApplicationProvider
import com.odyssey.catalog.AioCatalogRepo
import com.odyssey.download.DownloadProgressTracker
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
 * Mirrors RecentVmTest for the new Library tab. The Library list only
 * surfaces downloaded episodes (filePath != null), so dispatch should
 * always land on Player.playLocal — but we still test the streaming
 * branch in case a row is shown stale (e.g., the file was deleted out
 * from under us by RetentionWorker between observation and tap).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class DownloadedVmTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // DownloadedVm constructs WorkScheduler which requires a live
        // WorkManager once any property touches getWorkInfosForUniqueWorkFlow.
        // RecentVmTest pulled in the same dependency; mirror it here so
        // both VM tests stay independent.
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            ApplicationProvider.getApplicationContext(),
            androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build(),
        )
    }
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `play(downloaded) calls Player playLocal`() = runTest {
        val fakePlayer = FakeEpisodePlayer()
        val vm = DownloadedVm(NoopEpisodeDao(), NoopPlaybackDao(), fakePlayer, WorkScheduler(ApplicationProvider.getApplicationContext()), DownloadProgressTracker(), com.odyssey.download.ArchiveProgressTracker(), AioCatalogRepo(ApplicationProvider.getApplicationContext()))

        val ep = makeEp(filePath = "/data/odyssey/123.mp3")
        vm.play(ep)

        assertEquals(1, fakePlayer.playLocalCalls.size)
        assertEquals(ep, fakePlayer.playLocalCalls.single())
        assertTrue(fakePlayer.playStreamCalls.isEmpty())
    }

    @Test
    fun `play(stale row with null filePath) falls back to playStream`() = runTest {
        val fakePlayer = FakeEpisodePlayer()
        val vm = DownloadedVm(NoopEpisodeDao(), NoopPlaybackDao(), fakePlayer, WorkScheduler(ApplicationProvider.getApplicationContext()), DownloadProgressTracker(), com.odyssey.download.ArchiveProgressTracker(), AioCatalogRepo(ApplicationProvider.getApplicationContext()))

        // RetentionWorker may have nulled out filePath after the row
        // was observed but before the tap arrived. Dispatching to
        // playStream is the right safety net — better than crashing.
        val ep = makeEp(filePath = null, downloadUrl = "https://cdn.example/123.mp3")
        vm.play(ep)

        assertEquals(1, fakePlayer.playStreamCalls.size)
        assertTrue(fakePlayer.playLocalCalls.isEmpty())
    }

    @Test
    fun `play swallows exceptions thrown by Player`() = runTest {
        val fakePlayer = FakeEpisodePlayer(throwOnLocal = true)
        val vm = DownloadedVm(NoopEpisodeDao(), NoopPlaybackDao(), fakePlayer, WorkScheduler(ApplicationProvider.getApplicationContext()), DownloadProgressTracker(), com.odyssey.download.ArchiveProgressTracker(), AioCatalogRepo(ApplicationProvider.getApplicationContext()))

        // Must not propagate — the user tapping play shouldn't crash.
        vm.play(makeEp(filePath = "/data/odyssey/123.mp3"))
        assertEquals(1, fakePlayer.playLocalCalls.size)
    }

    private fun makeEp(filePath: String? = null, downloadUrl: String = "https://cdn.example/x.mp3") =
        LocalEpisodeEntity(
            providerId = "aio",
            externalId = "1",
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

    private class FakeEpisodePlayer(
        private val throwOnLocal: Boolean = false,
        initialState: com.odyssey.player.PlayerStateSnapshot = com.odyssey.player.PlayerStateSnapshot.IDLE,
    ) : EpisodePlayer {
        val playLocalCalls = mutableListOf<LocalEpisodeEntity>()
        data class StreamCall(val episodeId: Long, val streamUrl: String, val title: String)
        val playStreamCalls = mutableListOf<StreamCall>()
        var pauseCalls = 0

        private val _state = kotlinx.coroutines.flow.MutableStateFlow(initialState)
        override val state = _state

        override suspend fun playLocal(ep: LocalEpisodeEntity, artworkUrl: String?) {
            playLocalCalls += ep
            if (throwOnLocal) error("simulated playLocal failure")
            _state.value = com.odyssey.player.PlayerStateSnapshot(ep.episodeId, isPlaying = true)
        }

        override suspend fun playStream(episodeId: Long, streamUrl: String, title: String, artworkUrl: String?, providerId: String) {
            playStreamCalls += StreamCall(episodeId, streamUrl, title)
            _state.value = com.odyssey.player.PlayerStateSnapshot(episodeId, isPlaying = true)
        }

        override suspend fun pause() {
            pauseCalls++
            _state.value = _state.value.copy(isPlaying = false)
        }
    }

    private class NoopEpisodeDao : EpisodeDao {
        override fun observeAll(): Flow<List<LocalEpisodeEntity>> = flowOf(emptyList())
        override fun observeDownloaded(): Flow<List<LocalEpisodeEntity>> = flowOf(emptyList())
        override suspend fun byId(id: Long): LocalEpisodeEntity? = null
        override suspend fun byKey(providerId: String, externalId: String): LocalEpisodeEntity? = null
        override suspend fun existingIds(ids: List<Long>): List<Long> = emptyList()
        override suspend fun existingKeys(providerId: String, externalIds: List<String>): List<String> = emptyList()
        override suspend fun upsert(e: LocalEpisodeEntity) {}
        override suspend fun markDownloaded(id: Long, path: String, size: Long, ts: Long) {}
        override suspend fun markUndownloaded(id: Long) {}
        override suspend fun markArchived(id: Long, ts: Long) {}
        override suspend fun clearAllArchived(): Int = 0
        override suspend fun delete(id: Long) {}
        override suspend fun downloadedOldestFirst(): List<LocalEpisodeEntity> = emptyList()
        override fun observeUnarchivedDownloaded(): Flow<List<LocalEpisodeEntity>> = flowOf(emptyList())
        override suspend fun unarchivedDownloaded(): List<LocalEpisodeEntity> = emptyList()
    }

    private class NoopPlaybackDao : PlaybackDao {
        override suspend fun get(id: Long): PlaybackPositionEntity? = null
        override suspend fun getByKey(providerId: String, externalId: String): PlaybackPositionEntity? = null
        override fun observeMostRecent(): Flow<PlaybackPositionEntity?> = flowOf(null)
        override fun observeCompletedIds(): Flow<List<Long>> = flowOf(emptyList())
        override fun observeAllPositions(): Flow<List<PlaybackPositionEntity>> = flowOf(emptyList())
        override suspend fun upsert(p: PlaybackPositionEntity) {}
    }
}
