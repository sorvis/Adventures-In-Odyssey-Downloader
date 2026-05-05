package com.odyssey.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odyssey.catalog.AioCatalogRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.PlaybackDao
import com.odyssey.debug.DebugLogger
import com.odyssey.download.DownloadProgressTracker
import com.odyssey.work.WorkScheduler
import com.odyssey.player.EpisodePlayer
import com.odyssey.player.PlaySource
import com.odyssey.player.playSourceFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Library" view of episodes already on the phone — answers the
 * "I have downloads but they rolled out of Recent and I'm offline"
 * problem. Filters to filePath != null and sorts by parsed air-date desc.
 *
 * Constructor is intentionally smaller than RecentVm: no WorkScheduler
 * (no Check now), no SettingsRepo (no metered gate), no resume episode
 * (resume lives in Recent so taps from here just play).
 */
@HiltViewModel
class DownloadedVm @Inject constructor(
    private val episodes: EpisodeDao,
    val playback: PlaybackDao,
    private val player: EpisodePlayer,
    private val scheduler: WorkScheduler,
    private val downloadProgress: DownloadProgressTracker,
    private val archiveProgress: com.odyssey.download.ArchiveProgressTracker,
    val catalog: AioCatalogRepo,
) : ViewModel() {

    val progress = downloadProgress.progress
    val archive = archiveProgress.progress

    val items = episodes.observeDownloaded()
        .map { eps ->
            eps.sortedWith(
                compareByDescending<LocalEpisodeEntity> { parseAirDateMillis(it.airDate) }
                    .thenByDescending { it.episodeId },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val completedIds = playback.observeCompletedIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<Long>())

    val positions = playback.observeAllPositions()
        .map { list -> list.associateBy { it.episodeId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val playerState = player.state

    fun play(ep: LocalEpisodeEntity) {
        // Pause-in-place when the row's button is tapped on a row that's
        // already playing — same Play↔Pause toggle as Recent.
        val s = player.state.value
        if (s.currentEpisodeId == ep.episodeId && s.isPlaying) {
            DebugLogger.i("DownloadedVm", "play(${ep.episodeId}) — pausing in-place")
            viewModelScope.launch { runCatching { player.pause() } }
            return
        }
        val src = playSourceFor(ep.filePath, ep.downloadUrl)
        val artwork = catalog.match(ep.title)?.thumbnailUrl ?: ep.imageUrl
        DebugLogger.i(
            "DownloadedVm",
            "play(${ep.episodeId}) — ${if (src is PlaySource.Local) "local" else "stream"}",
        )
        viewModelScope.launch {
            try {
                when (src) {
                    is PlaySource.Local -> player.playLocal(ep, artwork)
                    is PlaySource.Stream -> player.playStream(ep.episodeId, ep.downloadUrl, ep.title, artwork)
                }
            } catch (t: Throwable) {
                DebugLogger.e("DownloadedVm", "play(${ep.episodeId}) — dispatch threw", t)
            }
        }
    }

    /**
     * Re-enqueue a download. Library rows are by definition already
     * downloaded; this only matters in the rare race where a row is
     * shown stale (filePath cleared between observation and tap).
     */
    fun download(ep: LocalEpisodeEntity) {
        if (ep.filePath != null) return
        DebugLogger.i("DownloadedVm", "download(${ep.episodeId}) — enqueueing")
        viewModelScope.launch {
            // No SettingsRepo dep here — Library tab keeps a slim VM.
            // Default to allowMetered=false; user can flip it in Recent.
            scheduler.enqueueDownload(ep.episodeId, allowMetered = false)
        }
    }

    /** Delete the local copy. Row leaves the Library list (filterPath != null filter). */
    fun delete(ep: LocalEpisodeEntity) {
        val path = ep.filePath ?: return
        DebugLogger.i("DownloadedVm", "delete(${ep.episodeId}) path=$path")
        viewModelScope.launch {
            runCatching { java.io.File(path).delete() }
                .onFailure { DebugLogger.w("DownloadedVm", "File.delete failed for $path", it) }
            episodes.markUndownloaded(ep.episodeId)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun DownloadedScreen(vm: DownloadedVm = hiltViewModel()) {
    val items by vm.items.collectAsState()
    val completedIds by vm.completedIds.collectAsState()
    val positions by vm.positions.collectAsState()
    val progress by vm.progress.collectAsState()
    val archive by vm.archive.collectAsState()
    val playerState by vm.playerState.collectAsState()
    var expandedIds by remember { mutableStateOf(setOf<Long>()) }

    Scaffold(topBar = { TopAppBar(title = { Text("Library") }) }) { padding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No downloaded episodes yet — pull-to-refresh or tap Check now in Recent.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(32.dp)
                        .testTag("downloaded-empty-state"),
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .semantics { testTagsAsResourceId = true }
                .testTag("downloaded-list"),
        ) {
            val completedSet = completedIds.toSet()
            items(items, key = { it.episodeId }) { ep ->
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    EpisodeRow(
                        ep = ep,
                        played = ep.episodeId in completedSet,
                        expanded = ep.episodeId in expandedIds,
                        downloadProgress = progress[ep.episodeId],
                        archiveProgress = archive[ep.episodeId],
                        match = vm.catalog.match(ep.title),
                        playback = positions[ep.episodeId],
                        isCurrentlyPlaying = playerState.currentEpisodeId == ep.episodeId &&
                                playerState.isPlaying,
                        onToggleExpand = {
                            expandedIds = if (ep.episodeId in expandedIds) expandedIds - ep.episodeId
                                          else expandedIds + ep.episodeId
                        },
                        onPlay = { vm.play(ep) },
                        onDelete = { vm.delete(ep) },
                        onDownload = { vm.download(ep) },
                    )
                }
            }
        }
    }
}
