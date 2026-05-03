package com.odyssey.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.PlaybackDao
import com.odyssey.player.PlayerController
import com.odyssey.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecentVm @Inject constructor(
    private val episodes: EpisodeDao,
    val playback: PlaybackDao,
    private val player: PlayerController,
    private val scheduler: WorkScheduler,
) : ViewModel() {
    val items = episodes.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val resume = playback.observeMostRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun checkNow() = scheduler.runDailyCheckNow()
    fun play(ep: LocalEpisodeEntity) {
        if (ep.filePath == null) return
        viewModelScope.launch { player.playLocal(ep) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(vm: RecentVm = hiltViewModel()) {
    val items by vm.items.collectAsState()
    val resume by vm.resume.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recent") },
                actions = { TextButton(onClick = vm::checkNow) { Text("Check now") } },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
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
        modifier = Modifier.clickable(enabled = ep.filePath != null, onClick = onPlay),
        headlineContent = { Text(ep.title) },
        supportingContent = { Text(ep.airDate.orEmpty()) },
        trailingContent = {
            when {
                ep.filePath == null -> Text("⬇", style = MaterialTheme.typography.labelMedium)
                ep.archivedAt != null -> Text("✓ archived", style = MaterialTheme.typography.labelSmall)
                played -> Text("✓ played", style = MaterialTheme.typography.labelSmall)
                else -> null
            }
        },
    )
}
