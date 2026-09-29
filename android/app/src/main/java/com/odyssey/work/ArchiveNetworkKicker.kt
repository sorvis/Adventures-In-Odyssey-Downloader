package com.odyssey.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.debug.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-drives stalled uploads when the phone joins a different network.
 *
 * Archive uploads target a LAN address, so the overwhelmingly common
 * failure is simply "not on the home WiFi right now". Every one of
 * those attempts returns retry, and WorkManager's exponential backoff
 * (15-minute base) quickly pushes the next attempt hours out. The
 * phone then rejoins the home network and… nothing happens, because a
 * satisfied constraint does not cancel an in-progress backoff.
 *
 * Shortening the backoff would "fix" this by retrying into a wall over
 * and over while off-LAN — burning battery to discover the same thing.
 * A network transition is the actual signal that the failure cause may
 * be gone, so that's what we hang the retry on. Backoff stays long as
 * the fallback for genuine server-side problems.
 *
 * Guards, because callbacks fire often:
 *  - debounced, so a flurry of capability changes causes one kick;
 *  - no-op when no backup server is configured;
 *  - no-op when nothing is waiting to upload.
 */
@Singleton
class ArchiveNetworkKicker @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val backfill: ArchiveBackfill,
    private val settings: SettingsRepo,
    private val episodes: EpisodeDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null

    fun start() {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            DebugLogger.w("ArchiveNetworkKicker", "no ConnectivityManager — network kicks disabled")
            return
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            cm.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = schedule("onAvailable")
                    override fun onLost(network: Network) = schedule("onLost")
                },
            )
        }.onFailure {
            DebugLogger.e("ArchiveNetworkKicker", "registerNetworkCallback failed", it)
        }
    }

    private fun schedule(reason: String) {
        pending?.cancel()
        pending = scope.launch {
            // Let the interface settle: onAvailable fires before routes
            // and DNS are necessarily usable, and a WiFi handover emits
            // several callbacks in a row.
            delay(DEBOUNCE_MS)
            runCatching { kick(reason) }
                .onFailure { DebugLogger.e("ArchiveNetworkKicker", "kick failed", it) }
        }
    }

    private suspend fun kick(reason: String) {
        if (!settings.flow.first().nasConfigured) return
        val waiting = episodes.unarchivedDownloaded().size
        if (waiting == 0) return
        DebugLogger.i(
            "ArchiveNetworkKicker",
            "network $reason — forcing backfill for $waiting waiting upload(s)",
        )
        backfill.run(force = true)
    }

    private companion object {
        const val DEBOUNCE_MS = 5_000L
    }
}
