package com.odyssey.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the DAO queries that used to be hardcoded to
 * `providerId = 'aio'`.
 *
 * Both of these were live bugs, found while removing the AIO-only
 * legacy methods (2026-10-23):
 *
 *  - `observeCompletedIds()` filtered to AIO and cast externalId to an
 *    integer, so a finished Your Story Hour episode never showed its
 *    "played" chip on any screen.
 *  - Recent and Downloaded called the AIO-only `markUndownloaded(Long)`
 *    with a derived episodeId. For a YSH row that matched nothing, so
 *    deleting a downloaded YSH episode removed the file but left the
 *    row still claiming to be downloaded.
 *
 * These run against real Room rather than a fake because the defect
 * was in the SQL, which a fake DAO cannot reproduce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ProviderScopedDaoTest {

    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: OdysseyDb

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ctx, OdysseyDb::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun close() = db.close()

    @Test
    fun `observeCompleted returns finished episodes from both shows`() = runBlocking {
        db.playback().upsert(position("aio", "1278383", completedAt = 1L))
        db.playback().upsert(position("ysh", "ysh-sku-1958", completedAt = 2L))
        db.playback().upsert(position("aio", "1278384", completedAt = null))

        val completed = db.playback().observeCompleted().first().map { it.episodeId }.toSet()

        assertEquals(
            setOf(derivedEpisodeId("1278383"), derivedEpisodeId("ysh-sku-1958")),
            completed,
        )
    }

    @Test
    fun `markUndownloadedByKey clears the file fields of a YSH row`() = runBlocking {
        db.episodes().upsert(episode("ysh", "ysh-sku-1958", filePath = "/sdcard/ysh/1958.mp3"))

        db.episodes().markUndownloadedByKey("ysh", "ysh-sku-1958")

        val row = db.episodes().byKey("ysh", "ysh-sku-1958")!!
        assertNull("filePath must be cleared", row.filePath)
        assertEquals(0L, row.fileSize)
        assertNull(row.downloadedAt)
    }

    @Test
    fun `markUndownloadedByKey does not touch the other show's row with the same derived id`() =
        runBlocking {
            // The reason the composite key exists: a numeric YSH sku id
            // collides head-on with an AIO oneplace id of the same
            // number, so both rows derive episodeId 1958. Keying the
            // update on that Long would hit whichever row the WHERE
            // clause happened to match.
            db.episodes().upsert(episode("aio", "1958", filePath = "/sdcard/aio/1958.mp3"))
            db.episodes().upsert(episode("ysh", "1958", filePath = "/sdcard/ysh/1958.mp3"))
            assertEquals(
                derivedEpisodeId("1958"),
                db.episodes().byKey("ysh", "1958")!!.episodeId,
            )

            db.episodes().markUndownloadedByKey("ysh", "1958")

            assertNull(db.episodes().byKey("ysh", "1958")!!.filePath)
            assertEquals(
                "the AIO row must be untouched",
                "/sdcard/aio/1958.mp3",
                db.episodes().byKey("aio", "1958")!!.filePath,
            )
        }

    @Test
    fun `markDownloadedByKey fills the file fields for a YSH row`() = runBlocking {
        // Replaces a providerId == "aio" branch in DownloadEpisodeWorker
        // that read-modify-wrote the whole entity for YSH, which could
        // clobber concurrent field updates.
        db.episodes().upsert(episode("ysh", "ysh-sku-1958", filePath = null))

        db.episodes().markDownloadedByKey(
            providerId = "ysh",
            externalId = "ysh-sku-1958",
            path = "/sdcard/ysh/1958.mp3",
            size = 4_200_000L,
            ts = 99L,
        )

        val row = db.episodes().byKey("ysh", "ysh-sku-1958")!!
        assertEquals("/sdcard/ysh/1958.mp3", row.filePath)
        assertEquals(4_200_000L, row.fileSize)
        assertEquals(99L, row.downloadedAt)
        // Untouched metadata survives, which is the point of an UPDATE
        // over an entity upsert.
        assertEquals("A Story", row.title)
    }

    private fun position(providerId: String, externalId: String, completedAt: Long?) =
        PlaybackPositionEntity(
            providerId = providerId,
            externalId = externalId,
            positionMs = 60_000L,
            durationMs = 1_500_000L,
            updatedAt = 1L,
            completedAt = completedAt,
        )

    private fun episode(providerId: String, externalId: String, filePath: String?) =
        LocalEpisodeEntity(
            providerId = providerId,
            externalId = externalId,
            title = "A Story",
            airDate = "2026-05-08",
            description = null,
            sourceUrl = "https://example/$externalId",
            downloadUrl = "https://example/$externalId.mp3",
            filePath = filePath,
            fileSize = if (filePath == null) 0L else 18_000_000L,
            durationMs = 1_500_000L,
            downloadedAt = if (filePath == null) null else 1L,
            archivedAt = null,
        )
}
