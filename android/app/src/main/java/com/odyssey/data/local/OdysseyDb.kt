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

    @Query("DELETE FROM local_episodes WHERE episodeId = :id")
    suspend fun delete(id: Long)

    @Query("""SELECT * FROM local_episodes
              WHERE filePath IS NOT NULL
              ORDER BY airDate ASC, episodeId ASC""")
    suspend fun downloadedOldestFirst(): List<LocalEpisodeEntity>
}

@Dao
interface PlaybackDao {
    @Query("SELECT * FROM playback_positions WHERE episodeId = :id")
    suspend fun get(id: Long): PlaybackPositionEntity?

    @Query("SELECT * FROM playback_positions ORDER BY updatedAt DESC LIMIT 1")
    fun observeMostRecent(): Flow<PlaybackPositionEntity?>

    @Query("SELECT episodeId FROM playback_positions WHERE completedAt IS NOT NULL")
    fun observeCompletedIds(): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: PlaybackPositionEntity)
}

@Database(
    entities = [LocalEpisodeEntity::class, PlaybackPositionEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class OdysseyDb : RoomDatabase() {
    abstract fun episodes(): EpisodeDao
    abstract fun playback(): PlaybackDao
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
