package com.odyssey.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "local_episodes",
    primaryKeys = ["providerId", "externalId"],
)
data class LocalEpisodeEntity(
    /**
     * Which ShowProvider this episode came from — "aio" today,
     * "ysh" once that lands. Part of the composite PK alongside
     * externalId so sku_id-shaped YSH ids can never collide with AIO
     * broadcast numbers.
     */
    val providerId: String,
    /**
     * Stable id within the provider — oneplace CMS id stringified
     * for AIO, sku_id stringified for YSH (sometimes prefixed,
     * e.g. "ysh-sku-1958"). Stored as TEXT so non-numeric ids
     * (GUIDs from future RSS feeds) work out of the box.
     */
    val externalId: String,
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
     * Album the episode belongs to. AIO uses the existing
     * AioCatalogRepo for album organization so this stays null for
     * AIO rows; YSH always populates these three fields from the
     * yourstoryhour.org catalog at ingestion time. Added in v3→v4.
     */
    val albumName: String? = null,
    val albumImageUrl: String? = null,
    val albumTrackOrder: Int? = null,
) {
    /**
     * Long-keyed view of the row id, kept so code paths that pre-date
     * the composite (providerId, externalId) PK keep compiling AND
     * keep RUNNING when YSH rows flow through them. AIO externalIds
     * are numeric (oneplace CMS ids or broadcast numbers); YSH rows
     * use prefixed strings like "ysh-sku-1958" — `toLong()` throws on
     * those, which previously crashed the Recent/Downloaded LazyColumn
     * the moment a YSH row hit the list (LazyColumn key extractor,
     * `progress[ep.episodeId]` map lookups, expandedIds membership
     * checks, etc.).
     *
     * The hash fallback gives YSH rows a stable, distinct-per-row Long
     * so per-row state (expanded, progress, playback position) works
     * without crashes. Collisions are theoretically possible at scale
     * but vanishingly unlikely for the ~1000-track YSH catalog.
     *
     * Getter-only — Room only persists backing fields, so this is
     * naturally non-column without needing `@Ignore`.
     */
    val episodeId: Long
        get() = externalId.toLongOrNull() ?: externalId.hashCode().toLong()
}

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

@Entity(
    tableName = "playback_positions",
    primaryKeys = ["providerId", "externalId"],
)
data class PlaybackPositionEntity(
    val providerId: String,
    val externalId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val completedAt: Long?,       // set when ≥95% reached
) {
    /** Mirrors LocalEpisodeEntity.episodeId — never-throws getter
     *  with hash fallback for non-numeric YSH externalIds. */
    val episodeId: Long
        get() = externalId.toLongOrNull() ?: externalId.hashCode().toLong()
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM local_episodes ORDER BY airDate DESC, externalId DESC")
    fun observeAll(): Flow<List<LocalEpisodeEntity>>

    @Query("SELECT * FROM local_episodes WHERE filePath IS NOT NULL ORDER BY airDate DESC, externalId DESC")
    fun observeDownloaded(): Flow<List<LocalEpisodeEntity>>

    /**
     * Snapshot of every row that still wants a file on disk. Used by
     * DownloadReconciler at app launch to detect "file is fully present
     * on disk but filePath stayed null" stuck states from pre-v0.1.51
     * 416-loop failures. NOT a Flow — the reconciler runs once and
     * doesn't want to react to ongoing DB churn.
     */
    @Query("SELECT * FROM local_episodes WHERE filePath IS NULL")
    suspend fun allUndownloaded(): List<LocalEpisodeEntity>

    /**
     * AIO-only legacy lookup. Existing callers pass the oneplace CMS
     * id (or broadcast number) as Long — this back-compat method
     * stringifies it and filters on providerId='aio' so AIO row
     * resolution behaves exactly as before. New YSH-aware code should
     * call `byKey(providerId, externalId)` instead.
     */
    @Query("SELECT * FROM local_episodes WHERE externalId = :id AND providerId = 'aio'")
    suspend fun byId(id: Long): LocalEpisodeEntity?

    @Query("SELECT * FROM local_episodes WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun byKey(providerId: String, externalId: String): LocalEpisodeEntity?

    /**
     * AIO-only legacy existence-check, used by DailyCheckWorker to
     * skip already-ingested rows. YSH equivalent is
     * `existingKeys(providerId, externalIds)`.
     */
    @Query("SELECT CAST(externalId AS INTEGER) FROM local_episodes WHERE externalId IN (:ids) AND providerId = 'aio'")
    suspend fun existingIds(ids: List<Long>): List<Long>

    @Query("SELECT externalId FROM local_episodes WHERE providerId = :providerId AND externalId IN (:externalIds)")
    suspend fun existingKeys(providerId: String, externalIds: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: LocalEpisodeEntity)

    @Query("UPDATE local_episodes SET filePath = :path, fileSize = :size, downloadedAt = :ts WHERE externalId = :id AND providerId = 'aio'")
    suspend fun markDownloaded(id: Long, path: String, size: Long, ts: Long)

    /**
     * Reverse of markDownloaded: clear filePath/fileSize/downloadedAt so
     * the row falls back to streamable. Used by PlaybackRecovery when a
     * downloaded file fails ExoPlayer's parser.
     */
    @Query("UPDATE local_episodes SET filePath = NULL, fileSize = 0, downloadedAt = NULL WHERE externalId = :id AND providerId = 'aio'")
    suspend fun markUndownloaded(id: Long)

    @Query("UPDATE local_episodes SET archivedAt = :ts WHERE externalId = :id AND providerId = 'aio'")
    suspend fun markArchived(id: Long, ts: Long)

    /**
     * RetentionWorker uses this to "prune" a row that's safe on the
     * NAS backup: the local file is deleted, but the row stays in the
     * DB shaped like a BrowseNasScreen backup-mirror ghost
     * (filePath=null, sourceUrl/downloadUrl="backup://<id>",
     * archivedAt preserved). Keeping the row prevents DailyCheckWorker
     * from re-ingesting the episode as "new" on the next pull-to-
     * refresh — which used to kick off a fresh CDN download → re-
     * archive → re-prune loop the user noticed after v0.1.59. The
     * existing ghost-promotion path in DailyCheckWorker restores the
     * row's real sourceUrl/title/airDate on the next provider fetch
     * without re-enqueueing a download.
     */
    @Query(
        """
        UPDATE local_episodes
           SET filePath     = NULL,
               fileSize     = 0,
               downloadedAt = NULL,
               sourceUrl    = 'backup://' || externalId,
               downloadUrl  = 'backup://' || externalId
         WHERE providerId   = :providerId
           AND externalId   = :externalId
        """
    )
    suspend fun convertToBackupGhost(providerId: String, externalId: String)

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

    @Query("DELETE FROM local_episodes WHERE externalId = :id AND providerId = 'aio'")
    suspend fun delete(id: Long)

    /**
     * Provider-aware delete. RetentionWorker uses this for YSH rows
     * — the legacy `delete(id: Long)` hard-codes `providerId='aio'`
     * which silently no-ops on YSH externalIds (and would mismatch
     * even if it didn't, since YSH ids aren't numeric).
     */
    @Query("DELETE FROM local_episodes WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun deleteByKey(providerId: String, externalId: String)

    @Query("""SELECT * FROM local_episodes
              WHERE filePath IS NOT NULL
              ORDER BY airDate ASC, externalId ASC""")
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
                AND providerId = 'aio'
              ORDER BY airDate ASC, externalId ASC""")
    fun observeUnarchivedDownloaded(): Flow<List<LocalEpisodeEntity>>

    /**
     * Snapshot of every downloaded-but-not-yet-archived AIO episode.
     * **AIO-only** — the archive-service is AIO-only today (per
     * design step 11b), and YSH rows have non-numeric externalIds
     * whose `episodeId` getter falls back to `String.hashCode()`,
     * which doesn't round-trip through `byId(Long)` (filters on
     * providerId='aio'). Without this filter, YSH rows would loop
     * forever through ArchiveBackfill → enqueueArchive(hash) →
     * ArchiveWorker.byId(hash) miss → Result.failure(), with the
     * row still unarchived so the next snapshot re-yields it. See
     * the v0.1.62 fix and the user device logs that surfaced it.
     */
    @Query("""SELECT * FROM local_episodes
              WHERE filePath IS NOT NULL
                AND archivedAt IS NULL
                AND providerId = 'aio'""")
    suspend fun unarchivedDownloaded(): List<LocalEpisodeEntity>

    /**
     * YSH "Albums" view — every distinct album name across the user's
     * ingested YSH tracks with a downloaded-count badge. AIO doesn't
     * use this query (its album view is backed by AioCatalogRepo, not
     * `local_episodes.albumName`). Drives the YshAlbumListScreen
     * landed in step 10.
     */
    @Query("""
      SELECT albumName       AS albumName,
             MIN(albumImageUrl) AS coverUrl,
             COUNT(*)        AS trackCount,
             SUM(CASE WHEN filePath IS NOT NULL THEN 1 ELSE 0 END) AS downloadedCount
        FROM local_episodes
       WHERE providerId = 'ysh' AND albumName IS NOT NULL
    GROUP BY albumName
    ORDER BY albumName ASC
    """)
    fun observeYshAlbumSummaries(): Flow<List<YshAlbumSummary>>

    /**
     * Tracks for a single YSH album, ordered by the catalog's track
     * order then title (for any rows whose orderIndex didn't survive
     * the join). Drives YshAlbumDetailScreen.
     */
    @Query("""
      SELECT * FROM local_episodes
       WHERE providerId = 'ysh' AND albumName = :albumName
    ORDER BY albumTrackOrder ASC, title ASC
    """)
    fun observeYshAlbumTracks(albumName: String): Flow<List<LocalEpisodeEntity>>
}

/**
 * Row class for the YSH album-list query. Plain data — no Room
 * annotations because the GROUP BY result projects all four columns
 * directly into the constructor.
 */
data class YshAlbumSummary(
    val albumName: String,
    val coverUrl: String?,
    val trackCount: Int,
    val downloadedCount: Int,
)

@Dao
interface PlaybackDao {
    /**
     * AIO-only legacy lookup keyed by oneplace CMS id (Long). YSH-aware
     * callers should use `getByKey(providerId, externalId)`.
     */
    @Query("SELECT * FROM playback_positions WHERE externalId = :id AND providerId = 'aio'")
    suspend fun get(id: Long): PlaybackPositionEntity?

    @Query("SELECT * FROM playback_positions WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun getByKey(providerId: String, externalId: String): PlaybackPositionEntity?

    @Query("SELECT * FROM playback_positions ORDER BY updatedAt DESC LIMIT 1")
    fun observeMostRecent(): Flow<PlaybackPositionEntity?>

    /**
     * Completed-ids stream. AIO-only legacy shape — coerces externalId
     * to Long (always works for AIO; YSH externalIds aren't numeric and
     * are filtered out by the WHERE clause).
     */
    @Query("SELECT CAST(externalId AS INTEGER) FROM playback_positions WHERE completedAt IS NOT NULL AND providerId = 'aio'")
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
    version = 5,
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

/**
 * v4 → v5: composite primary key on `(providerId, externalId)` for
 * `local_episodes` and `playback_positions`. Until v4 the PK was
 * `episodeId: Long` which works for AIO (one provider, ids globally
 * unique) but breaks the moment a second provider's externalId
 * collides with an AIO broadcast number. YSH sku_ids land in the
 * same 2000-range as AIO broadcast numbers — this migration unblocks
 * that.
 *
 * Strategy: rename the existing tables, create the v5 shapes, copy
 * rows with `externalId = CAST(episodeId AS TEXT)` (every v4 row is
 * either AIO with a numeric oneplace CMS id, or AIO with a broadcast
 * number — both round-trip through CAST), drop the v4 tables. Row
 * counts and contents are preserved exactly.
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // --- local_episodes ---------------------------------------------
        db.execSQL("ALTER TABLE local_episodes RENAME TO local_episodes_v4")
        db.execSQL(
            """
            CREATE TABLE local_episodes (
                providerId       TEXT    NOT NULL,
                externalId       TEXT    NOT NULL,
                title            TEXT    NOT NULL,
                airDate          TEXT,
                description      TEXT,
                sourceUrl        TEXT    NOT NULL,
                downloadUrl      TEXT    NOT NULL,
                filePath         TEXT,
                fileSize         INTEGER NOT NULL,
                durationMs       INTEGER NOT NULL,
                downloadedAt     INTEGER,
                archivedAt       INTEGER,
                imageUrl         TEXT,
                albumName        TEXT,
                albumImageUrl    TEXT,
                albumTrackOrder  INTEGER,
                PRIMARY KEY (providerId, externalId)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO local_episodes (
                providerId, externalId, title, airDate, description,
                sourceUrl, downloadUrl, filePath, fileSize, durationMs,
                downloadedAt, archivedAt, imageUrl, albumName,
                albumImageUrl, albumTrackOrder
            )
            SELECT
                providerId,
                CAST(episodeId AS TEXT),
                title, airDate, description, sourceUrl, downloadUrl,
                filePath, fileSize, durationMs, downloadedAt, archivedAt,
                imageUrl, albumName, albumImageUrl, albumTrackOrder
            FROM local_episodes_v4
            """.trimIndent()
        )
        db.execSQL("DROP TABLE local_episodes_v4")

        // --- playback_positions -----------------------------------------
        db.execSQL("ALTER TABLE playback_positions RENAME TO playback_positions_v4")
        db.execSQL(
            """
            CREATE TABLE playback_positions (
                providerId   TEXT    NOT NULL,
                externalId   TEXT    NOT NULL,
                positionMs   INTEGER NOT NULL,
                durationMs   INTEGER NOT NULL,
                updatedAt    INTEGER NOT NULL,
                completedAt  INTEGER,
                PRIMARY KEY (providerId, externalId)
            )
            """.trimIndent()
        )
        // Every v4 playback_positions row is AIO by definition — there
        // was no provider column. Backfill providerId='aio' for all.
        db.execSQL(
            """
            INSERT INTO playback_positions (
                providerId, externalId, positionMs, durationMs,
                updatedAt, completedAt
            )
            SELECT 'aio', CAST(episodeId AS TEXT), positionMs, durationMs,
                   updatedAt, completedAt
            FROM playback_positions_v4
            """.trimIndent()
        )
        db.execSQL("DROP TABLE playback_positions_v4")
    }
}
