package com.odyssey.work

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the auto-push contract for Settings → Backup save:
 *
 *   - Only files with filePath != null AND archivedAt == null get
 *     enqueued (already-backed-up files MUST NOT be re-uploaded).
 *   - allowMetered is read from SettingsRepo at run-time so the
 *     metered-network policy is respected.
 *   - Empty case is a clean no-op (no enqueues, no exceptions).
 *
 * Robolectric so SettingsRepo's DataStore can spin up; the rest is a
 * fake EpisodeDao + recording ArchiveEnqueuer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ArchiveBackfillTest {

    private lateinit var settings: SettingsRepo
    private lateinit var enqueuer: RecordingArchiveEnqueuer

    @Before
    fun setUp() {
        val ctx: Application = ApplicationProvider.getApplicationContext()
        settings = SettingsRepo(ctx)
        enqueuer = RecordingArchiveEnqueuer()
        // SettingsRepo's DataStore persists across tests in the class —
        // reset to a known state so each @Test starts clean.
        runBlocking { settings.setAllowMeteredDownloads(false) }
    }

    @After
    fun tearDown() {
        runBlocking { settings.setAllowMeteredDownloads(false) }
    }

    @Test
    fun `enqueues only unarchived downloaded rows`() = runBlocking {
        // Three episodes:
        //   100 — downloaded + archived       → must NOT be enqueued
        //   200 — downloaded, archivedAt null → MUST be enqueued
        //   300 — streaming-only (no file)    → must NOT be enqueued
        val backfill = ArchiveBackfill(
            episodes = FakeEpisodeDao(
                listOf(
                    ep(100L, filePath = "/sdcard/100.mp3", archivedAt = 1L),
                    ep(200L, filePath = "/sdcard/200.mp3", archivedAt = null),
                    ep(300L, filePath = null, archivedAt = null),
                ),
            ),
            scheduler = enqueuer,
            settings = settings,
        )

        val count = backfill.run()
        assertEquals(1, count)
        assertEquals(listOf(200L), enqueuer.calls.map { it.episodeId })
    }

    @Test
    fun `respects allowMetered from settings`() = runBlocking {
        settings.setAllowMeteredDownloads(true)
        val backfill = ArchiveBackfill(
            episodes = FakeEpisodeDao(listOf(ep(200L, filePath = "/x", archivedAt = null))),
            scheduler = enqueuer,
            settings = settings,
        )
        backfill.run()
        assertEquals(true, enqueuer.calls.single().allowMetered)
    }

    @Test
    fun `empty input is a clean no-op`() = runBlocking {
        val backfill = ArchiveBackfill(
            episodes = FakeEpisodeDao(emptyList()),
            scheduler = enqueuer,
            settings = settings,
        )
        assertEquals(0, backfill.run())
        assertTrue(enqueuer.calls.isEmpty())
    }

    @Test
    fun `re-running is safe - already-archived rows from a prior call don't reappear`() = runBlocking {
        // Simulates: first run pushed two files; backend marked them
        // archived; user taps the push button again. The second call
        // must not re-enqueue the same ids.
        val dao = FakeEpisodeDao(
            listOf(
                ep(200L, filePath = "/a", archivedAt = null),
                ep(201L, filePath = "/b", archivedAt = null),
            ),
        )
        val backfill = ArchiveBackfill(dao, enqueuer, settings)

        assertEquals(2, backfill.run())
        // Simulate ArchiveEpisodeWorker setting archivedAt for both.
        dao.markAllArchived()

        enqueuer.calls.clear()
        assertEquals(0, backfill.run())
        assertTrue(enqueuer.calls.isEmpty())
    }

    // ---- helpers ----------------------------------------------------

    private fun ep(id: Long, filePath: String?, archivedAt: Long?) = LocalEpisodeEntity(
        episodeId = id,
        title = "ep $id",
        airDate = null,
        description = null,
        sourceUrl = "",
        downloadUrl = "",
        filePath = filePath,
        fileSize = 0L,
        durationMs = 0L,
        downloadedAt = if (filePath != null) 1L else null,
        archivedAt = archivedAt,
    )

    private class RecordingArchiveEnqueuer : ArchiveEnqueuer {
        data class Call(val episodeId: Long, val allowMetered: Boolean)
        val calls = mutableListOf<Call>()
        override fun enqueueArchive(episodeId: Long, allowMetered: Boolean) {
            calls += Call(episodeId, allowMetered)
        }
    }

    /** Mutable fake DAO — the only methods the backfill touches. */
    private class FakeEpisodeDao(initial: List<LocalEpisodeEntity>) : EpisodeDao {
        private val rows = initial.toMutableList()
        fun markAllArchived() {
            rows.replaceAll { if (it.archivedAt == null) it.copy(archivedAt = 1L) else it }
        }
        override fun observeAll(): Flow<List<LocalEpisodeEntity>> = flowOf(rows)
        override fun observeDownloaded(): Flow<List<LocalEpisodeEntity>> = flowOf(rows)
        override suspend fun byId(id: Long): LocalEpisodeEntity? = rows.firstOrNull { it.episodeId == id }
        override suspend fun existingIds(ids: List<Long>): List<Long> = emptyList()
        override suspend fun upsert(e: LocalEpisodeEntity) {}
        override suspend fun markDownloaded(id: Long, path: String, size: Long, ts: Long) {}
        override suspend fun markUndownloaded(id: Long) {}
        override suspend fun markArchived(id: Long, ts: Long) {}
        override suspend fun delete(id: Long) {}
        override suspend fun downloadedOldestFirst(): List<LocalEpisodeEntity> = emptyList()
        override fun observeUnarchivedDownloaded(): Flow<List<LocalEpisodeEntity>> = flowOf(unarchivedNow())
        override suspend fun unarchivedDownloaded(): List<LocalEpisodeEntity> = unarchivedNow()
        private fun unarchivedNow() = rows.filter { it.filePath != null && it.archivedAt == null }
    }
}
