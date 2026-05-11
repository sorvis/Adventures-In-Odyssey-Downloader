package com.odyssey.data.local

import android.app.Application
import android.content.ContentValues
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the v3 → v4 → v5 migration chain. v3 shipped with a
 * single-column providerId on local_episodes; v4 adds three nullable
 * album columns and creates `ysh_unmatched_titles`; v5 rewrites both
 * `local_episodes` and `playback_positions` to use a composite PK on
 * (providerId, externalId) instead of the single Long `episodeId`.
 *
 * Approach: hand-build a v3 schema via SupportSQLiteOpenHelper, seed
 * a sample AIO row, close, then open the DB through Room at v5 with
 * all migrations registered. Room runs 3→4 then 4→5 in sequence.
 * Read back via the DAOs and confirm:
 *   - the AIO row survived with externalId = the stringified episodeId
 *   - the new ysh_unmatched_titles table exists and supports insert
 *     + observeCount via the DAO
 *   - the new composite-PK accessors round-trip a YSH row with the
 *     three album columns populated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class OdysseyDbMigration3to4Test {

    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private val dbName = "odyssey-migration-3to4-test.db"

    @After fun tearDown() { ctx.deleteDatabase(dbName) }

    @Test
    fun aioRowSurvivesMigration_and_newColumnsAreNull() {
        // --- seed a v3 database directly via SQLite -------------------
        seedV3Database()

        // --- open through Room at v4 to trigger MIGRATION_3_4 ---------
        val db = openV4()
        val row = runBlocking { db.episodes().byId(1278294L) }
        assertNotNull("seeded AIO row should survive the migration", row)
        assertEquals("Clutter", row!!.title)
        assertEquals("aio", row.providerId)
        assertNull("albumName starts null after additive migration", row.albumName)
        assertNull("albumImageUrl starts null after additive migration", row.albumImageUrl)
        assertNull("albumTrackOrder starts null after additive migration", row.albumTrackOrder)
        db.close()
    }

    @Test
    fun yshUnmatchedTitlesTableIsUsableAfterMigration() {
        seedV3Database()
        val db = openV4()

        runBlocking {
            assertEquals(0, db.yshUnmatched().observeCount().first())
            db.yshUnmatched().insert(
                YshUnmatchedTitleEntity(
                    oneplaceEpisodeId = 1277617L,
                    title = "Child of Privilege (Lottie Moon Part 1)",
                    sourceUrl = "https://oneplace.com/.../1277617",
                    downloadUrl = "https://zcast.swncdn.com/.../1277617.mp3",
                    firstSeenAt = 1L,
                    attemptCount = 1,
                ),
            )
            assertEquals(1, db.yshUnmatched().observeCount().first())
        }

        db.close()
    }

    @Test
    fun newAlbumColumnsRoundTripForYshRow() {
        // After migration, inserting a row with the new album fields
        // populated must read back identically — proves the columns
        // are wired through Room's entity binding, not just SQLite.
        // YSH externalIds are non-numeric ("ysh-sku-1958") so we have
        // to use byKey(providerId, externalId) — the legacy byId(Long)
        // shim is intentionally AIO-only.
        seedV3Database()
        val db = openV4()

        val yshRow = LocalEpisodeEntity(
            providerId = "ysh",
            externalId = "ysh-sku-1958",
            title = "Madeleine's Courage",
            airDate = "2021-06-01",
            description = "Madeleine defends the fort.",
            sourceUrl = "https://yourstoryhour.org/exciting-events-volume-11",
            downloadUrl = "https://your-story-hour.s3.amazonaws.com/.../EE-11-02.mp3",
            filePath = null,
            fileSize = 0L,
            durationMs = 30 * 60_000L,
            downloadedAt = null,
            archivedAt = null,
            imageUrl = null,
            albumName = "Exciting Events - Volume 11",
            albumImageUrl = "https://your-story-hour.s3.amazonaws.com/.../EE11.jpg",
            albumTrackOrder = 2,
        )

        runBlocking {
            db.episodes().upsert(yshRow)
            val readBack = db.episodes().byKey("ysh", "ysh-sku-1958")
            assertNotNull(readBack)
            assertEquals("Exciting Events - Volume 11", readBack!!.albumName)
            assertEquals(2, readBack.albumTrackOrder)
            assertEquals("ysh", readBack.providerId)
            assertEquals("ysh-sku-1958", readBack.externalId)
            // Legacy byId(1958) must NOT return this YSH row — it
            // filters on providerId='aio'.
            assertNull(db.episodes().byId(1958L))
        }

        db.close()
    }

    @Test
    fun aioRowSurvivesV5_PKChange_with_externalIdStringifiedFromLegacyEpisodeId() {
        seedV3Database()
        val db = openV4()
        runBlocking {
            // Legacy AIO byId(Long) still works as a back-compat shim.
            val row = db.episodes().byId(1278294L)
            assertNotNull("AIO row should survive composite-PK migration", row)
            // And the new composite key access returns the same row.
            val byKey = db.episodes().byKey("aio", "1278294")
            assertNotNull(byKey)
            assertEquals(row!!.title, byKey!!.title)
            // externalId is the stringified legacy episodeId.
            assertEquals("1278294", byKey.externalId)
        }
        db.close()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun openV4(): OdysseyDb =
        Room.databaseBuilder(ctx, OdysseyDb::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()

    /**
     * Hand-build the v3 schema using SupportSQLiteOpenHelper so Room
     * sees a "real" pre-migration database when we open at v4.
     *
     * Schema reproduces what MIGRATION_2_3 leaves behind:
     *   - local_episodes with providerId TEXT NOT NULL DEFAULT 'aio'
     *   - playback_positions unchanged from v1
     *   - PRAGMA user_version = 3
     */
    private fun seedV3Database() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    // local_episodes — v3 shape (no album columns yet,
                    // single-PK on episodeId).
                    db.execSQL(
                        """
                        CREATE TABLE local_episodes (
                            episodeId    INTEGER NOT NULL PRIMARY KEY,
                            title        TEXT    NOT NULL,
                            airDate      TEXT,
                            description  TEXT,
                            sourceUrl    TEXT    NOT NULL,
                            downloadUrl  TEXT    NOT NULL,
                            filePath     TEXT,
                            fileSize     INTEGER NOT NULL,
                            durationMs   INTEGER NOT NULL,
                            downloadedAt INTEGER,
                            archivedAt   INTEGER,
                            imageUrl     TEXT,
                            providerId   TEXT    NOT NULL DEFAULT 'aio'
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE playback_positions (
                            episodeId   INTEGER NOT NULL PRIMARY KEY,
                            positionMs  INTEGER NOT NULL,
                            durationMs  INTEGER NOT NULL,
                            updatedAt   INTEGER NOT NULL,
                            completedAt INTEGER
                        )
                        """.trimIndent()
                    )
                    // Room writes its identity into room_master_table at
                    // every open. Pre-seed an empty version so Room's
                    // post-migration onValidateSchema hook has something
                    // to write into rather than tripping on
                    // "no room_master_table" surprise.
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS room_master_table (
                            id INTEGER PRIMARY KEY,
                            identity_hash TEXT
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(
                    db: androidx.sqlite.db.SupportSQLiteDatabase,
                    oldVersion: Int, newVersion: Int,
                ) {
                    // No-op — caller controls the seeded version directly.
                }
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        db.execSQL("PRAGMA user_version = 3")
        val cv = ContentValues().apply {
            put("episodeId", 1278294L)
            put("title", "Clutter")
            put("airDate", "May 3, 2026")
            put("description", "Connie's room is buried.")
            put("sourceUrl", "https://oneplace.com/adventures-in-odyssey/listen/1278294")
            put("downloadUrl", "https://zcast.swncdn.com/.../1278294.mp3")
            putNull("filePath")
            put("fileSize", 0L)
            put("durationMs", 25 * 60_000L)
            putNull("downloadedAt")
            putNull("archivedAt")
            putNull("imageUrl")
            put("providerId", "aio")
        }
        db.insert("local_episodes", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, cv)
        helper.close()
    }
}
