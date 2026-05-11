package com.odyssey.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "local_episodes")
data class LocalEpisodeEntity(
    @PrimaryKey val episodeId: Long,
    val title: String,
    val airDate: String?,
    val description: String?,
    val sourceUrl: String,
    val downloadUrl: String,
    val filePath: String?,        // null until downloaded
    val fileSize: Long,
    val durationMs: Long,
    val downloadedAt: Long?,      // epoch ms
    val archivedAt: Long?,        // epoch ms; null while not pushed to NAS
    val imageUrl: String? = null, // remote artwork URL; loaded by Coil in the row
    /**
     * Which ShowProvider this episode came from — "aio" today, "ysh"
     * eventually. Defaulted so old rows backfill cleanly during the
     * v2→v3 migration. The PK stays `episodeId`; we'll move to a
     * composite (providerId, externalId) PK once a second provider
     * actually lands.
     */
    val providerId: String = "aio",
    /**
     * Album the episode belongs to. AIO uses the existing
     * AioCatalogRepo for album organization so this stays null for
     * AIO rows; YSH always populates these three fields from the
     * yourstoryhour.org catalog at ingestion time. Added in v3→v4.
     */
    val albumName: String? = null,
    val albumImageUrl: String? = null,
    val albumTrackOrder: Int? = null,
)

/**
 * One row per oneplace-YSH episode whose title couldn't be matched
 * against the yourstoryhour.org catalog by normalized title. Surfaces
 * in-app via a badge on the show-switcher and a dedicated review
 * screen so misses don't disappear into a log file.
 *
 * Only the YshOneplaceProvider writes here — the free-streaming
 * provider gets sku_id directly from the API and can never have an
 * unmatched title by construction.
 */
@Entity(tableName = "ysh_unmatched_titles")
data class YshUnmatchedTitleEntity(
    @PrimaryKey val oneplaceEpisodeId: Long,
    val title: String,
    val sourceUrl: String,
    val downloadUrl: String,
    val firstSeenAt: Long,         // epoch ms
    val attemptCount: Int,         // bumped on each daily-check re-encounter
)

@Entity(tableName = "playback_positions")
data class PlaybackPositionEntity(
    @PrimaryKey val episodeId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val completedAt: Long?,       // set when ≥95% reached
)

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM local_episodes ORDER BY airDate DESC, episodeId DESC")
    fun observeAll(): Flow<List<LocalEpisodeEntity>>

    @Query("SELECT * FROM local_episodes WHERE filePath IS NOT NULL ORDER BY airDate DESC, episodeId DESC")
    fun observeDownloaded(): Flow<List<LocalEpisodeEntity>>

    @Query("SELECT * FROM local_episodes WHERE episodeId = :id")
    suspend fun byId(id: Long): LocalEpisodeEntity?

    @Query("SELECT episodeId FROM local_episodes WHERE episodeId IN (:ids)")
    suspend fun existingIds(ids: List<Long>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: LocalEpisodeEntity)

    @Query("UPDATE local_episodes SET filePath = :path, fileSize = :size, downloadedAt = :ts WHERE episodeId = :id")
    suspend fun markDownloaded(id: Long, path: String, size: Long, ts: Long)

    /**
     * Reverse of markDownloaded: clear filePath/fileSize/downloadedAt so
     * the row falls back to streamable. Used by PlaybackRecovery when a
     * downloaded file fails ExoPlayer's parser.
     */
    @Query("UPDATE local_episodes SET filePath = NULL, fileSize = 0, downloadedAt = NULL WHERE episodeId = :id")
    suspend fun markUndownloaded(id: Long)

    @Query("UPDATE local_episodes SET archivedAt = :ts WHERE episodeId = :id")
    suspend fun markArchived(id: Long, ts: Long)

    /**
     * Null out archivedAt on every row that has a local file. Used by
     * "Re-archive everything" — after an enrichment fix on the server
     * side that affects how uploads are filed, you want every row to
     * become a candidate for the backfill again so the new metadata
     * gets sent. Does NOT touch rows without a local file (no point —
     * archive worker bails on null filePath anyway).
     */
    @Query("UPDATE local_episodes SET archivedAt = NULL WHERE filePath IS NOT NULL")
    suspend fun clearAllArchived(): Int

    @Query("DELETE FROM local_episodes WHERE episodeId = :id")
    suspend fun delete(id: Long)

    @Query("""SELECT * FROM local_episodes
              WHERE filePath IS NOT NULL
              ORDER BY airDate ASC, episodeId ASC""")
    suspend fun downloadedOldestFirst(): List<LocalEpisodeEntity>

    /**
     * Episodes that exist on the phone but haven't been pushed to the
     * backup service yet (filePath set, archivedAt null). Drives the
     * Settings → Backup auto-backfill: when the user saves valid
     * credentials we enumerate this list and enqueue an archive job
     * per row.
     *
     * Observable so the Settings screen can show a live "X waiting"
     * count without polling.
     */
    @Query("""SELECT * FROM local_episodes
              WHERE filePath IS NOT NULL
                AND archivedAt IS NULL
              ORDER BY airDate ASC, episodeId ASC""")
    fun observeUnarchivedDownloaded(): Flow<List<LocalEpisodeEntity>>

    @Query("""SELECT * FROM local_episodes
              WHERE filePath IS NOT NULL
                AND archivedAt IS NULL""")
    suspend fun unarchivedDownloaded(): List<LocalEpisodeEntity>
}

@Dao
interface PlaybackDao {
    @Query("SELECT * FROM playback_positions WHERE episodeId = :id")
    suspend fun get(id: Long): PlaybackPositionEntity?

    @Query("SELECT * FROM playback_positions ORDER BY updatedAt DESC LIMIT 1")
    fun observeMostRecent(): Flow<PlaybackPositionEntity?>

    @Query("SELECT episodeId FROM playback_positions WHERE completedAt IS NOT NULL")
    fun observeCompletedIds(): Flow<List<Long>>

    /** Used by the row UI to show "X min left" on episodes the user
     *  has started but not finished. */
    @Query("SELECT * FROM playback_positions")
    fun observeAllPositions(): Flow<List<PlaybackPositionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: PlaybackPositionEntity)
}

@Dao
interface YshUnmatchedDao {
    @Query("SELECT COUNT(*) FROM ysh_unmatched_titles")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM ysh_unmatched_titles ORDER BY firstSeenAt DESC")
    fun observeAll(): Flow<List<YshUnmatchedTitleEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: YshUnmatchedTitleEntity)

    @Query("UPDATE ysh_unmatched_titles SET attemptCount = attemptCount + 1 WHERE oneplaceEpisodeId = :id")
    suspend fun bumpAttempt(id: Long)

    @Query("DELETE FROM ysh_unmatched_titles WHERE oneplaceEpisodeId = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [
        LocalEpisodeEntity::class,
        PlaybackPositionEntity::class,
        YshUnmatchedTitleEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class OdysseyDb : RoomDatabase() {
    abstract fun episodes(): EpisodeDao
    abstract fun playback(): PlaybackDao
    abstract fun yshUnmatched(): YshUnmatchedDao
}

/**
 * v1 → v2: add LocalEpisodeEntity.imageUrl. Pure additive — old rows
 * land with imageUrl=null, get backfilled on next daily check.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_episodes ADD COLUMN imageUrl TEXT")
    }
}

/**
 * v2 → v3: add LocalEpisodeEntity.providerId for the multi-show plugin
 * abstraction (H-lite). Existing rows are AIO by definition, so the
 * column lands NOT NULL with DEFAULT 'aio' and SQLite backfills every
 * row during the ALTER. PK is unchanged.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_episodes ADD COLUMN providerId TEXT NOT NULL DEFAULT 'aio'")
    }
}

/**
 * v3 → v4: additive prep for YSH support.
 *  - Adds three nullable album columns to `local_episodes` (AIO rows
 *    leave them null and continue to source album info from
 *    AioCatalogRepo; YSH rows populate them from the yourstoryhour.org
 *    catalog at ingestion time).
 *  - Creates `ysh_unmatched_titles` to record oneplace-YSH episodes
 *    whose titles don't normalize-match the YSH catalog. Surfaced
 *    in-app so the user can see what didn't land.
 *
 * PK on local_episodes is intentionally unchanged here — the
 * composite-PK migration happens in v4→v5 alongside the wider
 * `episodeId: Long` → `(providerId, externalId)` type ripple.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_episodes ADD COLUMN albumName TEXT")
        db.execSQL("ALTER TABLE local_episodes ADD COLUMN albumImageUrl TEXT")
        db.execSQL("ALTER TABLE local_episodes ADD COLUMN albumTrackOrder INTEGER")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ysh_unmatched_titles (
                oneplaceEpisodeId INTEGER NOT NULL PRIMARY KEY,
                title             TEXT    NOT NULL,
                sourceUrl         TEXT    NOT NULL,
                downloadUrl       TEXT    NOT NULL,
                firstSeenAt       INTEGER NOT NULL,
                attemptCount      INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}
