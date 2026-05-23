package com.odyssey.ui.screens

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.odyssey.app.SettingsRepo
import com.odyssey.catalog.AioCatalogRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.PlaybackDao
import com.odyssey.data.local.PlaybackPositionEntity
import com.odyssey.download.DownloadProgressTracker
import com.odyssey.player.EpisodePlayer
import com.odyssey.work.WorkScheduler
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
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
import org.junit.Assert.assertNotNull
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
        // RecentVm now collects scheduler.dailyCheckSnapshot which
        // touches WorkManager.getInstance(ctx). Init the test
        // WorkManager so that doesn't throw during VM construction.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ApplicationProvider.getApplicationContext(),
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build(),
        )
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
    fun `play(currently-playing same ep) pauses instead of re-issuing playLocal`() = runTest {
        // Row's button is the user-facing toggle: when this row's
        // episode IS the one playing, tapping it must pause the player,
        // not call playLocal/playStream again. Locks the row UX
        // continuity the user asked for ("hit play, turns into pause").
        val ep = makeEp(filePath = "/data/odyssey/123.mp3")
        val fakePlayer = FakePlayer(
            initialState = com.odyssey.player.PlayerStateSnapshot(ep.episodeId, isPlaying = true),
        )
        val vm = makeVm(fakePlayer)

        vm.play(ep)

        assertEquals("pause must be called once", 1, fakePlayer.pauseCalls)
        assertTrue("playLocal must not fire while paused-pivoting", fakePlayer.playLocalCalls.isEmpty())
        assertTrue("playStream must not fire either", fakePlayer.playStreamCalls.isEmpty())
    }

    @Test
    fun `play(same ep but paused) starts playback again`() = runTest {
        // Same episode is loaded but currently paused — tap should
        // resume playback (call playLocal), not double-pause.
        val ep = makeEp(filePath = "/data/odyssey/123.mp3")
        val fakePlayer = FakePlayer(
            initialState = com.odyssey.player.PlayerStateSnapshot(ep.episodeId, isPlaying = false),
        )
        val vm = makeVm(fakePlayer)

        vm.play(ep)

        assertEquals(0, fakePlayer.pauseCalls)
        assertEquals(1, fakePlayer.playLocalCalls.size)
    }

    @Test
    fun `download(YSH row) enqueues via provider-aware unique work name`() = kotlinx.coroutines.runBlocking {
        // Regression for the v0.1.40 user-reported bug: tapping the
        // pin button on a YSH episode did nothing. RecentVm.download
        // used to call the legacy `enqueueDownload(Long)` shim, which
        // hardcodes provider="aio". For YSH rows the resulting
        // (aio, <ysh-row-hashCode>) lookup found no row in the
        // DownloadEpisodeWorker, so the worker returned failure() and
        // the pin had no observable effect.
        val fakePlayer = FakePlayer()
        val vm = makeVm(fakePlayer)
        val ysh = LocalEpisodeEntity(
            providerId = "ysh",
            externalId = "ysh-sku-1958",
            title = "Madeleine's Courage",
            airDate = null,
            description = null,
            sourceUrl = "https://yourstoryhour.org/x",
            downloadUrl = "https://s3/EE-11-02.mp3",
            filePath = null,
            fileSize = 0L,
            durationMs = 0L,
            downloadedAt = null,
            archivedAt = null,
        )

        vm.download(ysh)

        // The launched viewModelScope coroutine reads settings.flow
        // off the IO dispatcher before enqueuing — poll briefly so
        // the test isn't tied to a specific dispatcher implementation.
        // The unique-work name is "download-<providerId>-<externalId>".
        // Pre-fix: "download-aio-<hashCode>" (broken).
        // Post-fix: "download-ysh-ysh-sku-1958".
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val wm = androidx.work.WorkManager.getInstance(ctx)
        val target = "download-ysh-ysh-sku-1958"
        repeat(40) {  // ~2s budget
            val info = wm.getWorkInfosForUniqueWork(target).get()
            if (info.isNotEmpty()) return@runBlocking
            kotlinx.coroutines.delay(50)
        }
        org.junit.Assert.fail("expected YSH-keyed download enqueue ($target) within 2s")
    }

    @Test
    fun `download -- AIO row with archivedAt set + NAS configured prefers Restore over CDN`() = kotlinx.coroutines.runBlocking {
        // User ask 2026-05-23: "if recents has a server version always
        // prefer the server to download from." LAN/Tailscale beats
        // CDN bandwidth and the NAS is the canonical archive. When the
        // row has archivedAt set and the NAS is configured, the pin
        // tap must route through RestoreEpisodeWorker (unique work
        // "restore-<id>"), NOT DownloadEpisodeWorker ("download-aio-<id>").
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val settings = SettingsRepo(ctx)
        settings.setNas("http://nas.example", "tok")
        val vm = makeVm(FakePlayer())
        val onBackup = LocalEpisodeEntity(
            providerId = "aio",
            externalId = "1234",
            title = "Backed Up Episode",
            airDate = "May 8, 2026",
            description = "x",
            sourceUrl = "https://oneplace.com/1234",
            downloadUrl = "https://zcast/1234.mp3",
            filePath = null,
            fileSize = 0L,
            durationMs = 25 * 60_000L,
            downloadedAt = null,
            archivedAt = 12345L,         // ← key bit: server has it
        )

        vm.download(onBackup)

        val wm = androidx.work.WorkManager.getInstance(ctx)
        // v0.1.73 unique-work name shape: "restore-<providerId>-<externalId>"
        // (was "restore-<id>" pre-v0.1.73; bumped so AIO and YSH restores
        // with overlapping numeric ranges don't collide).
        val restoreWork = "restore-aio-1234"
        val downloadWork = "download-aio-1234"
        repeat(40) {  // ~2s budget for the coroutine to fire
            val info = wm.getWorkInfosForUniqueWork(restoreWork).get()
            if (info.isNotEmpty()) {
                // Pinned via Restore (NAS) — confirm the CDN-keyed work
                // name was NOT enqueued.
                val dlInfo = wm.getWorkInfosForUniqueWork(downloadWork).get()
                org.junit.Assert.assertTrue(
                    "must NOT enqueue the CDN download when restore is available",
                    dlInfo.isEmpty(),
                )
                return@runBlocking
            }
            kotlinx.coroutines.delay(50)
        }
        org.junit.Assert.fail("expected restore enqueue ($restoreWork) within 2s but only saw CDN download")
    }

    @Test
    fun `download -- AIO row with archivedAt set BUT no NAS configured falls back to CDN`() = kotlinx.coroutines.runBlocking {
        // If the user has marked rows as archived in the past but their
        // NAS isn't currently configured (e.g. they removed the NAS
        // settings), don't get stuck enqueuing restores against a NAS
        // we can't reach. Fall back to the CDN download path.
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val settings = SettingsRepo(ctx)
        settings.clearAllForTest()           // no NAS
        val vm = makeVm(FakePlayer())
        val onBackupNoNas = LocalEpisodeEntity(
            providerId = "aio", externalId = "5678",
            title = "Stranded Backup", airDate = "May 1, 2026", description = null,
            sourceUrl = "https://oneplace.com/5678",
            downloadUrl = "https://zcast/5678.mp3",
            filePath = null, fileSize = 0L, durationMs = 25 * 60_000L,
            downloadedAt = null,
            archivedAt = 99L,
        )

        vm.download(onBackupNoNas)

        val wm = androidx.work.WorkManager.getInstance(ctx)
        repeat(40) {
            val info = wm.getWorkInfosForUniqueWork("download-aio-5678").get()
            if (info.isNotEmpty()) return@runBlocking
            kotlinx.coroutines.delay(50)
        }
        org.junit.Assert.fail("expected CDN download fallback when NAS isn't configured")
    }

    @Test
    fun `download -- YSH row with archivedAt set + NAS configured restores from NAS (v0_1_73)`() = kotlinx.coroutines.runBlocking {
        // v0.1.73 extends Restore to YSH. The archive-service accepts
        // YSH uploads (v0.1.72) and RestoreEpisodeWorker now takes
        // (providerId, externalId), so YSH rows that are on backup
        // restore through the same NAS-preferred path AIO uses.
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val settings = SettingsRepo(ctx)
        settings.setNas("http://nas.example", "tok")
        val vm = makeVm(FakePlayer())
        val yshArchived = LocalEpisodeEntity(
            providerId = "ysh", externalId = "ysh-sku-1958",
            title = "Madeleine's Courage", airDate = null, description = null,
            sourceUrl = "https://yourstoryhour.org/x",
            downloadUrl = "https://s3/EE-11-02.mp3",
            filePath = null, fileSize = 0L, durationMs = 0L,
            downloadedAt = null,
            archivedAt = 99L,
        )

        vm.download(yshArchived)

        val wm = androidx.work.WorkManager.getInstance(ctx)
        val restoreWork = "restore-ysh-ysh-sku-1958"
        val cdnWork = "download-ysh-ysh-sku-1958"
        repeat(40) {
            val info = wm.getWorkInfosForUniqueWork(restoreWork).get()
            if (info.isNotEmpty()) {
                // Restore won — confirm CDN download was NOT enqueued.
                val cdnInfo = wm.getWorkInfosForUniqueWork(cdnWork).get()
                org.junit.Assert.assertTrue(
                    "YSH on backup must NOT fall through to CDN download",
                    cdnInfo.isEmpty(),
                )
                return@runBlocking
            }
            kotlinx.coroutines.delay(50)
        }
        org.junit.Assert.fail("expected YSH restore enqueue ($restoreWork) within 2s but only saw CDN download")
    }

    @Test
    fun `download -- YSH row WITHOUT archivedAt falls through to CDN`() = kotlinx.coroutines.runBlocking {
        // Symmetric back-compat: YSH rows that aren't backed up yet
        // (no archivedAt) still use the CDN download path. Sanity-
        // checks that the v0.1.73 "drop AIO-only guard" change still
        // gates on archivedAt.
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val settings = SettingsRepo(ctx)
        settings.setNas("http://nas.example", "tok")
        val vm = makeVm(FakePlayer())
        val yshFresh = LocalEpisodeEntity(
            providerId = "ysh", externalId = "ysh-sku-2745",
            title = "Fresh YSH", airDate = null, description = null,
            sourceUrl = "https://yourstoryhour.org/y",
            downloadUrl = "https://s3/y.mp3",
            filePath = null, fileSize = 0L, durationMs = 0L,
            downloadedAt = null,
            archivedAt = null,
        )

        vm.download(yshFresh)

        val wm = androidx.work.WorkManager.getInstance(ctx)
        repeat(40) {
            val info = wm.getWorkInfosForUniqueWork("download-ysh-ysh-sku-2745").get()
            if (info.isNotEmpty()) return@runBlocking
            kotlinx.coroutines.delay(50)
        }
        org.junit.Assert.fail("YSH without archivedAt must enqueue CDN download")
    }

    @Test
    fun `download seeds tracker IMMEDIATELY so the row shows queued progress on pin-tap`() = runTest {
        // User report (screenshot 2026-05-13): tapping pin on YSH does
        // not surface a progress bar — the row stayed visually
        // unchanged until the worker downloaded enough bytes for an
        // update() call, which can be tens of seconds away on metered
        // / wifi-only constraints. The fix: RecentVm.download() seeds
        // the tracker with a (0, 0) placeholder BEFORE the suspend
        // boundary so EpisodeRow's "totalBytes==0 → indeterminate bar"
        // path fires right away.
        val tracker = DownloadProgressTracker()
        val vm = makeVm(FakePlayer(), tracker = tracker)
        val ysh = LocalEpisodeEntity(
            providerId = "ysh",
            externalId = "ysh-sku-1958",
            title = "Madeleine's Courage",
            airDate = null,
            description = null,
            sourceUrl = "https://yourstoryhour.org/x",
            downloadUrl = "https://s3/EE-11-02.mp3",
            filePath = null,
            fileSize = 0L,
            durationMs = 0L,
            downloadedAt = null,
            archivedAt = null,
        )

        // Tracker is empty before the tap.
        assertTrue(tracker.progress.value.isEmpty())

        vm.download(ysh)

        // Synchronous side-effect of download() — does NOT depend on
        // viewModelScope's coroutine completing. The entry MUST exist
        // immediately after download() returns.
        val key = "ysh-sku-1958".hashCode().toLong()
        val entry = tracker.progress.value[key]
        assertNotNull(
            "RecentVm.download() must seed the progress tracker for the " +
                "tapped episode's key so the row's indeterminate bar appears " +
                "without waiting on WorkManager to start the actual transfer",
            entry,
        )
        // Placeholder shape: 0 bytes read, 0 total — drives the
        // indeterminate (spinning) variant of LinearProgressIndicator.
        assertEquals(0L, entry!!.bytesRead)
        assertEquals(0L, entry.totalBytes)
    }

    @Test
    fun `tracker key from RecentVm matches the worker's progressKey for YSH (hashCode-of-externalId)`() = runTest {
        // Regression guard: LocalEpisodeEntity.episodeId AND
        // DownloadEpisodeWorker.progressKey both fall back to
        // externalId.hashCode().toLong() when the externalId isn't
        // numeric. If those two formulas ever diverge, the row's
        // progress lookup (keyed by episodeId) misses every update the
        // worker fires (keyed by progressKey) — silent failure.
        val tracker = DownloadProgressTracker()
        val vm = makeVm(FakePlayer(), tracker = tracker)
        val externalId = "ysh-sku-9999"
        val ysh = LocalEpisodeEntity(
            providerId = "ysh",
            externalId = externalId,
            title = "x",
            airDate = null,
            description = null,
            sourceUrl = "https://yourstoryhour.org/x",
            downloadUrl = "https://s3/x.mp3",
            filePath = null,
            fileSize = 0L,
            durationMs = 0L,
            downloadedAt = null,
            archivedAt = null,
        )

        vm.download(ysh)

        // The key the row would look up.
        val rowKey = ysh.episodeId
        // The key the worker writes under (mirrors line 51 of
        // DownloadEpisodeWorker.kt — keep these in sync).
        val workerKey = externalId.toLongOrNull() ?: externalId.hashCode().toLong()

        assertEquals(
            "row's progress[ep.episodeId] lookup MUST find the worker's " +
                "writes — if these two derivations of the key drift, the " +
                "row will silently never show a progress bar for YSH.",
            rowKey, workerKey,
        )
        assertTrue("seeded entry must be findable under the row's key",
            rowKey in tracker.progress.value)
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

    private fun makeVm(
        player: EpisodePlayer,
        tracker: DownloadProgressTracker = DownloadProgressTracker(),
    ): RecentVm = RecentVm(
        ctx = ApplicationProvider.getApplicationContext(),
        episodes = NoopEpisodeDao(),
        playback = NoopPlaybackDao(),
        player = player,
        scheduler = WorkScheduler(ApplicationProvider.getApplicationContext()),
        settings = SettingsRepo(ApplicationProvider.getApplicationContext()),
        downloadProgress = tracker,
        archiveProgress = com.odyssey.download.ArchiveProgressTracker(),
        catalog = AioCatalogRepo(ApplicationProvider.getApplicationContext()),
        yshCatalog = com.odyssey.show.YshCatalog(
            ApplicationProvider.getApplicationContext(),
            okhttp3.OkHttpClient(),
        ),
        nas = com.odyssey.nas.NasClient(
            SettingsRepo(ApplicationProvider.getApplicationContext()),
            okhttp3.OkHttpClient(),
        ),
    )

    private fun makeEp(
        filePath: String? = null,
        downloadUrl: String = "https://cdn.example/x.mp3",
    ) = LocalEpisodeEntity(
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

    /** Captures calls into the dispatch boundary. */
    private class FakePlayer(
        private val throwOnLocal: Boolean = false,
        private val throwOnStream: Boolean = false,
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
            if (throwOnStream) error("simulated playStream failure")
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
        override suspend fun allUndownloaded(): List<LocalEpisodeEntity> = emptyList()
        override suspend fun byId(id: Long): LocalEpisodeEntity? = null
        override suspend fun byKey(providerId: String, externalId: String): LocalEpisodeEntity? = null
        override suspend fun existingIds(ids: List<Long>): List<Long> = emptyList()
        override suspend fun existingKeys(providerId: String, externalIds: List<String>): List<String> = emptyList()
        override suspend fun upsert(e: LocalEpisodeEntity) {}
        override suspend fun markDownloaded(id: Long, path: String, size: Long, ts: Long) {}
        override suspend fun markUndownloaded(id: Long) {}
        override suspend fun markArchived(id: Long, ts: Long) {}
        override suspend fun markUnarchived(id: Long) {}
        override suspend fun markArchivedByKey(providerId: String, externalId: String, ts: Long) {}
        override suspend fun markUnarchivedByKey(providerId: String, externalId: String) {}
        override suspend fun convertToBackupGhost(providerId: String, externalId: String) {}
        override suspend fun clearAllArchived(): Int = 0
        override suspend fun delete(id: Long) {}
        override suspend fun deleteByKey(providerId: String, externalId: String) {}
        override suspend fun downloadedOldestFirst(): List<LocalEpisodeEntity> = emptyList()
        override fun observeUnarchivedDownloaded(): Flow<List<LocalEpisodeEntity>> = flowOf(emptyList())
        override suspend fun unarchivedDownloaded(): List<LocalEpisodeEntity> = emptyList()
        override fun observeYshAlbumSummaries(): Flow<List<com.odyssey.data.local.YshAlbumSummary>> = flowOf(emptyList())
        override fun observeYshAlbumTracks(albumName: String): Flow<List<LocalEpisodeEntity>> = flowOf(emptyList())
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
