package com.odyssey.player

import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.debug.DebugLogger
import com.odyssey.work.WorkScheduler
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Self-heal corrupt downloads. When ExoPlayer rejects a local file with
 * ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, the file on disk usually
 * isn't actually an MP3 — most commonly because the server returned an
 * HTML error page that DownloadEpisodeWorker saved as `.mp3`, or the
 * download was truncated.
 *
 * Recovery flow:
 *   1. Sniff the file's first 8 bytes with looksLikeMp3.
 *   2. If it IS a valid MP3 (ExoPlayer was wrong for some reason),
 *      leave it alone — re-downloading would just give us the same bytes.
 *   3. Otherwise: delete the file, clear filePath in the DB, and
 *      re-enqueue DownloadEpisodeWorker. The user sees the row fall
 *      back to "▶ stream" until the redownload completes.
 *
 * Each episode is retried at most once per process lifetime to avoid
 * infinite loops when the upstream URL truly serves bad bytes.
 */
@Singleton
class PlaybackRecovery @Inject constructor(
    private val episodes: EpisodeDao,
    private val scheduler: WorkScheduler,
    private val settings: SettingsRepo,
) {
    private val recoveryAttempts: MutableSet<Long> =
        Collections.synchronizedSet(mutableSetOf())

    suspend fun handleParseError(episodeId: Long) {
        if (!recoveryAttempts.add(episodeId)) {
            DebugLogger.w(
                "PlaybackRecovery",
                "Already attempted recovery for episode $episodeId this session — skipping",
            )
            return
        }

        val ep = episodes.byId(episodeId)
        if (ep == null) {
            DebugLogger.w("PlaybackRecovery", "No episode row for $episodeId — nothing to recover")
            return
        }
        val path = ep.filePath
        if (path == null) {
            DebugLogger.w(
                "PlaybackRecovery",
                "Episode $episodeId has no filePath (already streaming) — nothing to recover",
            )
            return
        }

        val file = File(path)
        val firstBytes = runCatching {
            file.inputStream().use { stream ->
                val buf = ByteArray(8)
                val n = stream.read(buf)
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            }
        }.getOrDefault(ByteArray(0))

        if (looksLikeMp3(firstBytes)) {
            DebugLogger.w(
                "PlaybackRecovery",
                "File at $path looks like a valid MP3 (size=${file.length()}) but " +
                    "ExoPlayer rejected it — leaving alone, recovery would be a wasted retry",
            )
            return
        }

        DebugLogger.i(
            "PlaybackRecovery",
            "File at $path is NOT an MP3 (size=${file.length()}) — deleting and re-enqueueing download",
        )
        runCatching { file.delete() }
            .onFailure { DebugLogger.w("PlaybackRecovery", "delete() failed for $path", it) }
        episodes.markUndownloaded(episodeId)
        val allowMetered = runCatching { settings.flow.first().allowMeteredDownloads }
            .getOrDefault(false)
        scheduler.enqueueDownload(episodeId, allowMetered = allowMetered)
        DebugLogger.i(
            "PlaybackRecovery",
            "Re-enqueued DownloadEpisodeWorker for $episodeId (allowMetered=$allowMetered)",
        )
    }
}
