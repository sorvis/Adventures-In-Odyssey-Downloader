package com.odyssey.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.download.ArchiveProgressTracker
import com.odyssey.download.DownloadProgressTracker
import com.odyssey.download.RestoreProgressTracker
import com.odyssey.download.TransferRow
import com.odyssey.download.mergeTransfers
import com.odyssey.nas.NasAlbum
import com.odyssey.nas.NasClient
import com.odyssey.nas.NasEpisode
import com.odyssey.player.PlayerController
import com.odyssey.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BrowseVm @Inject constructor(
    private val nas: NasClient,
    private val settings: SettingsRepo,
    private val player: PlayerController,
    private val episodes: EpisodeDao,
    private val scheduler: WorkScheduler,
    downloads: DownloadProgressTracker,
    uploads: ArchiveProgressTracker,
    restores: RestoreProgressTracker,
) : ViewModel() {
    val configured = settings.flow.map { it.nasConfigured }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val albums = MutableStateFlow<List<NasAlbum>>(emptyList())
    val results = MutableStateFlow<List<NasEpisode>>(emptyList())
    val error = MutableStateFlow<String?>(null)

    /** Episode ids that already have a local file on disk. Drives the
     *  Pin-offline button — already-on-phone rows shouldn't offer it. */
    val localFiles = episodes.observeDownloaded()
        .map { list -> list.map { it.episodeId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * In-flight transfers (downloads + uploads + restores + queued
     * uploads) merged into a single sorted list. Renders as a
     * collapsible strip at the top of the Sync screen so the user
     * doesn't need to flip to a separate Transfers tab to watch
     * progress.
     *
     * Same merge logic the standalone TransfersScreen uses — wired
     * directly here so the screen has its own copy and doesn't have
     * to share a ViewModel.
     */
    val transferRows = combine(
        downloads.progress,
        uploads.progress,
        restores.progress,
        episodes.observeAll(),
        episodes.observeUnarchivedDownloaded(),
    ) { dl, up, rs, eps, unarchived ->
        val titles = eps.associate { it.episodeId to it.title }
        mergeTransfers(
            downloads = dl,
            uploads = up,
            titlesById = titles,
            queuedUploadIds = unarchived.map { it.episodeId }.toSet(),
            restores = rs,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<TransferRow>())

    fun refresh() = viewModelScope.launch {
        nas.listAlbums().fold({ albums.value = it; error.value = null },
                              { error.value = "Couldn't reach NAS: ${it.message}" })
        // Mirror the full server episode list into the local DB so the
        // Album tab's "☁ on backup" badge lights up across the
        // library, not just for episodes the user has searched. Pure
        // metadata — no audio downloads. Failure is non-fatal: the
        // album list above already loaded; the user keeps a working
        // Backup tab even if the per-episode mirror failed.
        nas.listAllEpisodes().onSuccess { mirrorServerEpisodes(it) }
    }

    fun search(q: String, album: String?) = viewModelScope.launch {
        nas.search(q, album).fold(
            { eps ->
                results.value = eps
                error.value = null
                // Mirror server episodes into the local DB so the Album
                // tab's title-join can recognize them as "on backup"
                // even when the phone never downloaded the episode from
                // oneplace. archivedAt + filePath=null means the row is
                // backed up but not on-disk — exactly the STREAMABLE
                // state with the backedUp flag set.
                mirrorServerEpisodes(eps)
            },
            { error.value = "Search failed: ${it.message}" },
        )
    }

    private suspend fun mirrorServerEpisodes(eps: List<NasEpisode>) {
        val now = System.currentTimeMillis()
        for (ep in eps) {
            val existing = episodes.byId(ep.episode_id)
            if (existing != null) {
                // Don't clobber an existing row's filePath/title — just
                // ensure archivedAt is set so the album view shows the
                // ☁ on backup badge.
                if (existing.archivedAt == null) {
                    episodes.markArchived(ep.episode_id, now)
                }
                continue
            }
            episodes.upsert(
                LocalEpisodeEntity(
                    episodeId = ep.episode_id,
                    title = ep.title,
                    airDate = ep.air_date,
                    description = ep.description,
                    sourceUrl = "backup://${ep.episode_id}",
                    downloadUrl = "backup://${ep.episode_id}",
                    filePath = null,
                    fileSize = ep.file_size,
                    durationMs = (ep.duration_secs ?: 0L) * 1000,
                    downloadedAt = null,
                    archivedAt = now,
                    providerId = "aio",
                ),
            )
        }
    }

    fun stream(ep: NasEpisode) = viewModelScope.launch {
        nas.audioUrl(ep.episode_id).onSuccess {
            // ExoPlayer DataSource auth handled at the OkHttp factory layer in
            // production; for now we pass the bare URL and assume the token
            // is appended via interceptor (TODO).
            player.playStream(ep.episode_id, it.url, ep.title)
        }
    }

    /**
     * "Pin offline" — schedule a RestoreEpisodeWorker to pull this
     * server episode onto the phone for offline playback. Tracker
     * surfaces the in-flight bytes on the Transfers screen.
     */
    fun pinOffline(ep: NasEpisode) = viewModelScope.launch {
        val allowMetered = settings.flow.first().allowMeteredDownloads
        scheduler.enqueueRestore(
            episodeId = ep.episode_id,
            title = ep.title,
            airDate = ep.air_date,
            album = ep.album,
            description = ep.description,
            durationSecs = ep.duration_secs ?: 0L,
            allowMetered = allowMetered,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseNasScreen(
    vm: BrowseVm = hiltViewModel(),
) {
    val configured by vm.configured.collectAsState()
    val transfers by vm.transferRows.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Sync") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Active transfers strip — auto-shows when something's
            // running, hidden when idle. Lives above the browse
            // surface so a user who just tapped Pin offline (or
            // whose daily check fired) sees progress without leaving
            // the tab. The standalone TransfersScreen route is still
            // wired (Settings deep-link) but this is the primary
            // entry point now.
            if (transfers.isNotEmpty()) {
                TransfersStrip(transfers, modifier = Modifier.testTag("transfers-strip"))
                HorizontalDivider()
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (!configured) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No backup server configured.", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The app works fully without one — set up your backup " +
                            "server in Settings to browse and pull from your archive.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                } else {
                    BrowseContent(vm)
                }
            }
        }
    }
}

/**
 * Compact list of in-flight transfer rows. Wraps the same row card
 * the standalone TransfersScreen uses so the look is identical
 * across both surfaces. Capped height — long lists scroll inside
 * the strip rather than pushing the browse surface off-screen.
 */
@Composable
private fun TransfersStrip(
    rows: List<TransferRow>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Active transfers",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
        // Bounded height: 3 rows ≈ 230dp. Beyond that the strip
        // scrolls so we don't crowd out the search/album list below.
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 230.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows, key = { "${it.kind}-${it.episodeId}" }) { row ->
                TransferRowCard(row)
            }
        }
    }
}

@Composable
private fun BrowseContent(vm: BrowseVm) {
    val albums by vm.albums.collectAsState()
    val results by vm.results.collectAsState()
    val error by vm.error.collectAsState()
    val localFiles by vm.localFiles.collectAsState()
    var query by remember { mutableStateOf("") }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Search") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = { vm.search(query, selectedAlbum) }) { Text("Go") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (results.isNotEmpty()) {
            Text("Results", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(results, key = { it.episode_id }) { ep ->
                    val onPhone = ep.episode_id in localFiles
                    ListItem(
                        modifier = Modifier.clickable { vm.stream(ep) },
                        headlineContent = { Text(ep.title) },
                        supportingContent = { Text("${ep.album ?: "Unsorted"} · ${ep.air_date ?: ""}") },
                        trailingContent = {
                            if (onPhone) {
                                Text(
                                    "✓ on phone",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            } else {
                                TextButton(
                                    onClick = { vm.pinOffline(ep) },
                                    modifier = Modifier.testTag("pin-offline-${ep.episode_id}"),
                                ) { Text("Pin offline") }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        } else {
            Text("Albums", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(albums, key = { it.name }) { a ->
                    ListItem(
                        modifier = Modifier.clickable { selectedAlbum = a.name; vm.search("", a.name) },
                        headlineContent = { Text(a.name) },
                        trailingContent = { Text("${a.episode_count}") },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
