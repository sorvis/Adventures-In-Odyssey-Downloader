package com.odyssey.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
 * Pins the provider scoping on the recently-played queries.
 *
 * **History (2026-09-28):** the Recent screen's "Recently played" strip
 * called [PlaybackDao.observeRecentlyPlayed], which takes the newest N
 * positions across EVERY provider, and then filtered to the active show
 * in Kotlin. That's filter-after-limit: a short run of plays in the
 * other show consumes the whole window and the strip renders empty even
 * though the active show has plenty of history just past it. The user
 * reported the Recent tab coming up blank.
 *
 * [PlaybackDao.observeRecentlyPlayedFor] pushes the WHERE ahead of the
 * LIMIT so the cap counts only rows the caller can actually use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class RecentlyPlayedQueryTest {

    private lateinit var db: OdysseyDb
    private lateinit var playback: PlaybackDao

    @Before
    fun setUp() {
        val ctx: Application = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, OdysseyDb::class.java)
            .allowMainThreadQueries()
            .build()
        playback = db.playback()
        runBlocking {
            // 6 YSH plays, all NEWER than every AIO play. With a limit of
            // 6 the unscoped query is saturated by YSH alone.
            for (n in 1..6) {
                playback.upsert(pos("ysh", "ysh-sku-50$n", updatedAt = 10_000L + n))
            }
            for (n in 1..3) {
                playback.upsert(pos("aio", "30$n", updatedAt = 1_000L + n))
            }
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `the unscoped query is saturated by the other show -- this is the bug`() = runBlocking {
        val rows = playback.observeRecentlyPlayed(6).first()
        assertEquals(6, rows.size)
        assertTrue(
            "every row in the window is YSH, so an AIO-filtered caller sees nothing",
            rows.all { it.providerId == "ysh" },
        )
    }

    @Test
    fun `the scoped query returns the active show's history despite newer other-show plays`() =
        runBlocking {
            val rows = playback.observeRecentlyPlayedFor("aio", 6).first()
            assertEquals("all three AIO plays survive the limit", 3, rows.size)
            assertTrue(rows.all { it.providerId == "aio" })
        }

    @Test
    fun `the scoped query orders newest-first and honours its limit`() = runBlocking {
        val rows = playback.observeRecentlyPlayedFor("ysh", 2).first()
        assertEquals(2, rows.size)
        assertEquals("ysh-sku-506", rows[0].externalId)
        assertEquals("ysh-sku-505", rows[1].externalId)
    }

    @Test
    fun `play history returns every row for one show, newest first, uncapped`() = runBlocking {
        val rows = playback.observePlayHistoryFor("ysh").first()
        assertEquals("no LIMIT — the history screen is a lazy list", 6, rows.size)
        assertEquals("ysh-sku-506", rows.first().externalId)
        assertEquals("ysh-sku-501", rows.last().externalId)
        assertTrue(rows.all { it.providerId == "ysh" })
    }

    @Test
    fun `play history for a show with no plays is empty, not null`() = runBlocking {
        assertEquals(emptyList<PlaybackPositionEntity>(), playback.observePlayHistoryFor("nope").first())
    }

    private fun pos(providerId: String, externalId: String, updatedAt: Long) =
        PlaybackPositionEntity(
            providerId = providerId,
            externalId = externalId,
            positionMs = 60_000L,
            durationMs = 300_000L,
            updatedAt = updatedAt,
            completedAt = null,
        )
}
