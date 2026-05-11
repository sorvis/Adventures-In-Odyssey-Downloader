package com.odyssey.download

import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.debug.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot phone-disk migration that flattens legacy AIO downloads
 * (which sat directly as mp3 files under `<externalFilesDir>/Episodes/`)
 * into the new per-provider layout, where AIO downloads live under
 * `Episodes/aio/` and YSH downloads live under `Episodes/ysh/`.
 *
 * Runs from `OdysseyApp.onCreate` on the IO dispatcher. Idempotent:
 * once the sentinel marker file exists in `rootDir`, the migration is
 * a no-op. Safe to call on every launch.
 *
 * The migrator also rewrites `local_episodes.filePath` for any row
 * whose path used to point at a top-level file under rootDir; those
 * paths now have `/aio/` inserted between rootDir and the filename so
 * playback after migration still hits a real file.
 */
@Singleton
class DiskLayoutMigrator @Inject constructor(
    private val downloader: EpisodeDownloader,
    private val episodes: EpisodeDao,
) {
    suspend fun migrateIfNeeded() = withContext(Dispatchers.IO) {
        val rootDir = downloader.rootDir
        val marker = File(rootDir, MARKER_NAME)
        if (marker.exists()) return@withContext   // already migrated

        val legacyFiles = rootDir.listFiles { f -> f.isFile && f.extension == "mp3" }
            ?.toList()
            .orEmpty()

        if (legacyFiles.isEmpty()) {
            // Fresh install or already-migrated-but-marker-missing
            // (e.g. user cleared app data). Touch the marker so we
            // skip the scan on every subsequent launch.
            marker.createNewFile()
            return@withContext
        }

        val aioDir = File(rootDir, "aio").apply { mkdirs() }
        var moved = 0
        var failed = 0
        val pathRewrites = mutableMapOf<String, String>()

        for (legacy in legacyFiles) {
            val target = File(aioDir, legacy.name)
            if (target.exists()) {
                // Same-name file already lives in aio/ — keep the new
                // one, drop the legacy. This shouldn't happen unless
                // the user manually mucked with the dir, but be safe.
                legacy.delete()
                continue
            }
            if (legacy.renameTo(target)) {
                pathRewrites[legacy.absolutePath] = target.absolutePath
                moved++
            } else {
                failed++
                DebugLogger.w("DiskLayoutMigrator", "failed to rename ${legacy.absolutePath}")
            }
        }

        // Rewrite local_episodes.filePath rows that pointed at moved
        // files. Only rows whose stored path matches a moved-from path
        // exactly; anything else (rows on cloud-only mirrors, rows
        // whose file was already deleted, etc.) is left alone.
        if (pathRewrites.isNotEmpty()) {
            val downloaded = episodes.downloadedOldestFirst()
            for (row in downloaded) {
                val newPath = pathRewrites[row.filePath] ?: continue
                episodes.upsert(row.copy(filePath = newPath))
            }
        }

        marker.createNewFile()
        DebugLogger.i(
            "DiskLayoutMigrator",
            "phone-disk layout migration: moved=$moved failed=$failed rewrites=${pathRewrites.size}",
        )
    }

    private companion object {
        /** Sentinel file in rootDir; presence means migration ran. */
        const val MARKER_NAME = ".aio-layout-v1"
    }
}
