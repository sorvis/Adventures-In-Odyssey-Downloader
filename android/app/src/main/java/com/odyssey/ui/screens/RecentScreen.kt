package com.odyssey.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.PlaybackDao
import com.odyssey.player.PlaySource
import com.odyssey.player.PlayerController
import com.odyssey.player.playSourceFor
import com.odyssey.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecentVm @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val episodes: EpisodeDao,
    val playback: PlaybackDao,
    private val player: PlayerController,
    private val scheduler: WorkScheduler,
    private val settings: SettingsRepo,
) : ViewModel() {
    val items = episodes.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val resume = playback.observeMostRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val showMeteredWarning = MutableStateFlow(false)

    fun checkNow() {
        viewModelScope.launch {
            val allowMetered = settings.flow.first().allowMeteredDownloads
            if (!allowMetered && isOnMeteredNetwork()) {
                showMeteredWarning.value = true
                return@launch
            }
            scheduler.runDailyCheckNow()
        }
    }

    fun dismissWarning() { showMeteredWarning.value = false }

    private fun isOnMeteredNetwork(): Boolean {
        val cm = ctx.getSystemService<ConnectivityManager>() ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun play(ep: LocalEpisodeEntity) {
        viewModelScope.launch {
            when (playSourceFor(ep.filePath, ep.downloadUrl)) {
                is PlaySource.Local -> player.playLocal(ep)
                is PlaySource.Stream -> player.playStream(ep.episodeId, ep.downloadUrl, ep.title)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun RecentScreen(
    onNavigateToSettings: () -> Unit = {},
    vm: RecentVm = hiltViewModel(),
) {
    val items by vm.items.collectAsState()
    val resume by vm.resume.collectAsState()
    val showWarning by vm.showMeteredWarning.collectAsState()

    if (showWarning) {
        AlertDialog(
            onDismissRequest = vm::dismissWarning,
            modifier = Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("metered-warning"),
            title = { Text("On cellular — downloads blocked") },
            text = {
                Text(
                    "You're on a cellular (LTE) network and downloads on cellular are turned off. " +
                            "Enable them in Settings if you want to download today's episodes now without WiFi.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.dismissWarning(); onNavigateToSettings() },
                    modifier = Modifier.testTag("warning-open-settings"),
                ) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissWarning) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recent") },
                actions = {
                    TextButton(onClick = vm::checkNow, modifier = Modifier.testTag("check-now")) {
                        Text("Check now")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .semantics { testTagsAsResourceId = true }
                .testTag("episode-list"),
        ) {
            resume?.let { r ->
                item {
                    ListItem(
                        headlineContent = { Text("Continue listening") },
                        supportingContent = { Text("Episode ${r.episodeId} · ${r.positionMs / 1000}s in") },
                    )
                    HorizontalDivider()
                }
            }
            items(items, key = { it.episodeId }) { ep ->
                EpisodeRow(ep, played = false, onPlay = { vm.play(ep) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: LocalEpisodeEntity, played: Boolean, onPlay: () -> Unit) {
    ListItem(
        modifier = Modifier
            .clickable(onClick = onPlay)
            .testTag(if (ep.filePath != null) "episode-row-playable" else "episode-row-streamable"),
        headlineContent = { Text(ep.title) },
        supportingContent = { Text(ep.airDate.orEmpty()) },
        trailingContent = {
            when {
                ep.filePath == null -> Text("▶ stream", style = MaterialTheme.typography.labelSmall)
                ep.archivedAt != null -> Text("✓ archived", style = MaterialTheme.typography.labelSmall)
                played -> Text("✓ played", style = MaterialTheme.typography.labelSmall)
                else -> null
            }
        },
    )
}
