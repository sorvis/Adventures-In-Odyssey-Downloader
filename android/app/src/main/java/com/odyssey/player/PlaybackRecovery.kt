package com.odyssey.player

import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.debug.DebugLogger
import com.odyssey.work.DownloadEnqueuer
import com.odyssey.work.RestoreEnqueuer
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Self-heal corrupt downloads, for every provider. When ExoPlayer
 * rejects a local file with ERROR_CODE_PARSING_CONTAINER_*, the file on
 * disk usually isn't actually an MP3 — most commonly because the server
 * returned an HTML error page that the download worker saved as `.mp3`,
 * or the download was truncated.
 *
 * Recovery flow:
 *   1. Resolve the player's media id back to a row — see [resolve];
 *      works for AIO's numeric externalIds AND for providers whose ids
 *      aren't numeric ("ysh-sku-1958"), which hash into the media id.
 *   2. Ask [decideRecovery] what to do (pure policy, JVM-tested).
 *   3. Skip → leave the file alone (re-fetching would give us the same
 *      bytes). Otherwise delete the file, clear filePath on the row via
 *      the provider-aware `markUndownloadedByKey`, and re-enqueue —
 *      through the CDN download worker, or through the restore worker
 *      when the bytes live on the NAS backup. The user sees the row
 *      fall back to "▶ stream" until the re-fetch completes.
 *
 * Each media id is retried at most once per process lifetime to avoid
 * infinite loops when the upstream URL truly serves bad bytes.
 *
 * Provider-awareness (v0.1.87): every step keys off
 * `(providerId, externalId)` read from the resolved row, so YSH — and
 * any future provider — self-heals the same way AIO always did. The
 * previous version resolved through `EpisodeDao.byId(Long)`, which
 * hard-codes `providerId='aio'`, so a corrupt YSH file silently found
 * no row and stayed broken.
 */
@Singleton
class PlaybackRecovery @Inject constructor(
    private val episodes: EpisodeDao,
    private val downloads: DownloadEnqueuer,
    private val restores: RestoreEnqueuer,
    private val settings: SettingsRepo,
) {
    private val recoveryAttempts: MutableSet<Long> =
        Collections.synchronizedSet(mutableSetOf())

    suspend fun handleParseError(mediaId: Long) {
        if (!recoveryAttempts.add(mediaId)) {
            DebugLogger.w(
                TAG,
                "Already attempted recovery for media id $mediaId this session — skipping",
            )
            return
        }

        val ep = resolve(mediaId)
        if (ep == null) {
            DebugLogger.w(TAG, "No downloaded row for media id $mediaId — nothing to recover")
            return
        }
        val key = "${ep.providerId}:${ep.externalId}"
        val path = ep.filePath

        when (val action = decideRecovery(path, ep.downloadUrl, firstBytesOf(path))) {
            is RecoveryAction.Skip -> DebugLogger.w(TAG, "$key — ${action.reason}")
            RecoveryAction.Redownload -> {
                discard(key, path)
                episodes.markUndownloadedByKey(ep.providerId, ep.externalId)
                val allowMetered = allowMetered()
                downloads.enqueueDownload(ep.providerId, ep.externalId, allowMetered)
                DebugLogger.i(
                    TAG,
                    "$key — re-enqueued DownloadEpisodeWorker (allowMetered=$allowMetered)",
                )
            }
            RecoveryAction.Restore -> {
                discard(key, path)
                episodes.markUndownloadedByKey(ep.providerId, ep.externalId)
                val allowMetered = allowMetered()
                restores.enqueueRestoreByKey(
                    providerId = ep.providerId,
                    externalId = ep.externalId,
                    title = ep.title,
                    airDate = ep.airDate,
                    album = ep.albumName,
                    description = ep.description,
                    durationSecs = ep.durationMs / 1000,
                    allowMetered = allowMetered,
                )
                DebugLogger.i(
                    TAG,
                    "$key — backup-sourced row, re-enqueued RestoreEpisodeWorker " +
                        "(allowMetered=$allowMetered)",
                )
            }
        }
    }

    /**
     * Map a Media3 media id back to its row, whatever provider it came
     * from. Media ids on the wire are Longs
     * ([LocalEpisodeEntity.episodeId]) — the numeric externalId for AIO,
     * a `hashCode()` fallback for providers whose ids aren't numeric.
     * The hash can't be reversed in SQL, so we match on the same getter
     * over the downloaded set. Recovery only ever applies to rows with a
     * file on disk, so that set is exactly the candidate pool (and is
     * bounded by the retention caps).
     */
    private suspend fun resolve(mediaId: Long): LocalEpisodeEntity? =
        episodes.downloadedOldestFirst().firstOrNull { it.episodeId == mediaId }

    private fun firstBytesOf(path: String?): ByteArray {
        if (path == null) return ByteArray(0)
        return runCatching {
            File(path).inputStream().use { stream ->
                val buf = ByteArray(8)
                val n = stream.read(buf)
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            }
        }.onFailure {
            DebugLogger.w(TAG, "Could not read first bytes of $path — treating as corrupt", it)
        }.getOrDefault(ByteArray(0))
    }

    private fun discard(key: String, path: String?) {
        if (path == null) return
        val file = File(path)
        DebugLogger.i(
            TAG,
            "$key — file at $path is NOT an MP3 (size=${file.length()}) — deleting and re-fetching",
        )
        runCatching { file.delete() }
            .onFailure { DebugLogger.w(TAG, "delete() failed for $path", it) }
    }

    private suspend fun allowMetered(): Boolean =
        runCatching { settings.flow.first().allowMeteredDownloads }.getOrDefault(false)

    private companion object {
        const val TAG = "PlaybackRecovery"
    }
}
