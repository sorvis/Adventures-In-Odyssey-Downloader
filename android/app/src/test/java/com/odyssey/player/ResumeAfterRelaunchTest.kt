package com.odyssey.player

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.OdysseyDb
import com.odyssey.data.local.PlaybackPositionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end resume contract: a user plays an episode, listens for a
 * while, closes the app, reopens it, taps the same episode, and hits
 * play. Playback must resume from the exact saved position.
 *
 * The seek-to-saved-position step itself happens on a real
 * MediaController inside PlayerController.playLocal/playStream, which
 * we can't run on the JVM. What WE can lock is the data leg of the
 * contract — saved positions survive an app shutdown and come back as
 * the right Long for PlayerController to seek to.
 *
 * This test runs against a file-backed Room DB so close + reopen
 * actually goes through SQLite persistence (not in-memory state),
 * matching what the real app does between launches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ResumeAfterRelaunchTest {

    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private val dbName = "resume-after-relaunch-test.db"

    @After
    fun tearDown() {
        // Robolectric reuses the Application, so the DB file persists
        // across tests inside this class — wipe it so each @Test starts
        // clean.
        ctx.deleteDatabase(dbName)
    }

    @Test
    fun `saved playback position survives app close and reopen`() = runBlocking {
        val episodeId = 1278383L
        val resumeAtMs = 10 * 60_000L  // 10 minutes in
        val durationMs = 25 * 60_000L

        // ---- Session 1: user plays the episode and listens ----
        val db1 = openDb()
        db1.episodes().upsert(seedEpisode(episodeId))
        db1.playback().upsert(
            PlaybackPositionEntity(
                episodeId = episodeId,
                positionMs = resumeAtMs,
                durationMs = durationMs,
                updatedAt = 1L,
                completedAt = null,
            ),
        )
        // App closes — Room flushes WAL, releases SQLite handles.
        db1.close()

        // ---- Session 2: user reopens app, taps the row, hits play ----
        val db2 = openDb()
        val saved = db2.playback().get(episodeId)
        assertNotNull("position must come back after relaunch", saved)
        assertEquals(
            "resume position must be exactly what we saved",
            resumeAtMs,
            saved!!.positionMs,
        )
        assertEquals(durationMs, saved.durationMs)
        // The episode metadata also has to round-trip, since the row
        // is what the user tapped to start playback.
        val ep = db2.episodes().byId(episodeId)
        assertNotNull("episode row must come back after relaunch", ep)
        assertEquals("War of the Words", ep!!.title)
        db2.close()
    }

    @Test
    fun `episode never played has no saved position so playback would start at zero`() = runBlocking {
        // Inverse of the above — no PlaybackPositionEntity exists, so
        // PlayerController's `playback.get(id)?.let { seekTo(...) }`
        // does nothing, and playback starts at 0.
        val episodeId = 999_999L

        val db1 = openDb()
        db1.episodes().upsert(seedEpisode(episodeId))
        db1.close()

        val db2 = openDb()
        assertEquals(null, db2.playback().get(episodeId))
        db2.close()
    }

    @Test
    fun `mid-playback save then immediate close is durable across reopen`() = runBlocking {
        // Pin the worst-case: user closes the app DURING playback (no
        // graceful save loop tick after the last write). The 5-second
        // save loop in PlayerController has just persisted at, say,
        // 12:34, and then the process dies. Reopen must show 12:34, not
        // some earlier snapshot.
        val episodeId = 1278384L
        val mostRecentMs = 12 * 60_000L + 34 * 1_000L

        val db1 = openDb()
        db1.episodes().upsert(seedEpisode(episodeId))
        db1.playback().upsert(
            PlaybackPositionEntity(episodeId, 5 * 60_000L, 25 * 60_000L, 1L, null),
        )
        // 5-second save loop fires again, with the up-to-date offset.
        db1.playback().upsert(
            PlaybackPositionEntity(episodeId, mostRecentMs, 25 * 60_000L, 2L, null),
        )
        db1.close()

        val db2 = openDb()
        assertEquals(mostRecentMs, db2.playback().get(episodeId)!!.positionMs)
        db2.close()
    }

    // --- helpers ------------------------------------------------------

    private fun openDb(): OdysseyDb =
        Room.databaseBuilder(ctx, OdysseyDb::class.java, dbName)
            .allowMainThreadQueries()
            .build()

    private fun seedEpisode(episodeId: Long) = LocalEpisodeEntity(
        episodeId = episodeId,
        title = "War of the Words",
        airDate = "May 8, 2026",
        description = null,
        sourceUrl = "https://example/$episodeId",
        downloadUrl = "https://example/$episodeId.mp3",
        filePath = "/sdcard/odyssey/$episodeId.mp3",
        fileSize = 18_000_000L,
        durationMs = 25 * 60_000L,
        downloadedAt = 1L,
        archivedAt = null,
    )
}
