package com.odyssey.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.debug.DebugLogger
import com.odyssey.work.DownloadEnqueuer
import com.odyssey.work.RestoreEnqueuer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Get this episode onto the phone" — the whole decision, in one place.
 *
 * The counterpart to
 * [com.odyssey.player.EpisodePlayDispatcher]: that class owns "start
 * playing this", this one owns "fetch this". Both exist because the
 * logic was originally inlined in one ViewModel and the other surfaces
 * each grew their own, worse copy. For acquisition the copies were:
 *
 *  - Recent: NAS when available, else CDN. Correct.
 *  - AIO album detail: backup restore, unconditionally — so the button
 *    silently did nothing for an episode that was never archived or
 *    when no NAS was configured.
 *  - YSH album detail: no per-row affordance at all, which is what
 *    "download to device does not exist for ysh" was (2026-10-22).
 *
 * Takes a [CoroutineScope] rather than owning one so the work is tied
 * to the calling ViewModel's lifecycle, and so the synchronous part
 * (the guard and the progress-bar seed) runs on the caller's thread
 * before any suspension.
 */
@Singleton
class EpisodeAcquirer @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val downloads: DownloadEnqueuer,
    private val restores: RestoreEnqueuer,
    private val settings: SettingsRepo,
    private val progress: DownloadProgressTracker,
) {

    /**
     * Enqueue [ep] for download, from whichever source is available.
     *
     * No-ops when the file is already on disk. [onMessage] receives a
     * user-facing confirmation; surfaces that have no snackbar can
     * ignore it.
     *
     * @param tag log prefix identifying the calling surface.
     */
    fun acquire(
        scope: CoroutineScope,
        ep: LocalEpisodeEntity,
        tag: String = "EpisodeAcquirer",
        onMessage: (String) -> Unit = {},
    ) {
        if (ep.filePath != null) return
        DebugLogger.i(tag, "acquire(${ep.providerId}:${ep.externalId}) — enqueueing")
        // Seed the progress tracker BEFORE the suspend boundary below so
        // the row shows an indeterminate "queued" bar the instant the
        // user taps. The worker replaces the placeholder with real byte
        // counts when it starts and clears it on success/failure.
        // Reported via screenshot 2026-05-13: "pin doesn't show a
        // progress bar on YSH rows."
        progress.queue(ep.episodeId)
        scope.launch {
            val s = settings.flow.first()
            val allowMetered = s.allowMeteredDownloads
            val source = acquisitionSourceFor(ep.archivedAt, s.nasConfigured)
            when (source) {
                AcquireSource.BACKUP -> restores.enqueueRestoreByKey(
                    providerId = ep.providerId,
                    externalId = ep.externalId,
                    title = ep.title,
                    airDate = ep.airDate,
                    album = ep.albumName,
                    description = ep.description,
                    durationSecs = ep.durationMs / 1000,
                    allowMetered = allowMetered,
                )
                AcquireSource.CDN ->
                    downloads.enqueueDownload(ep.providerId, ep.externalId, allowMetered)
            }
            onMessage(
                acquisitionMessage(
                    title = ep.title,
                    source = source,
                    startsNow = startsImmediately(isOnMeteredNetwork(), allowMetered),
                ),
            )
        }
    }

    private fun isOnMeteredNetwork(): Boolean {
        val cm = ctx.getSystemService<ConnectivityManager>() ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
