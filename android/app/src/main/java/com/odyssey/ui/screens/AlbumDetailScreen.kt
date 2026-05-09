package com.odyssey.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.odyssey.catalog.AioCatalogRepo
import com.odyssey.catalog.AlbumWithOwnership
import com.odyssey.catalog.CatalogEpisodeWithOwnership
import com.odyssey.catalog.EpisodeOwnership
import com.odyssey.catalog.LocalEpisodeKey
import com.odyssey.catalog.joinAlbumOwnership
import com.odyssey.catalog.ownershipSummary
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.PlaybackDao
import com.odyssey.nas.NasClient
import com.odyssey.work.WorkScheduler
import kotlinx.coroutines.flow.first
import com.odyssey.debug.DebugLogger
import com.odyssey.player.EpisodePlayer
import com.odyssey.player.PlaySource
import com.odyssey.player.playSourceFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumDetailVm @Inject constructor(
    savedState: SavedStateHandle,
    private val episodes: EpisodeDao,
    private val playback: PlaybackDao,
    private val catalog: AioCatalogRepo,
    private val player: EpisodePlayer,
    private val nas: NasClient,
    private val scheduler: WorkScheduler,
    private val settings: SettingsRepo,
) : ViewModel() {

    /**
     * "albumKey" route arg is the album NAME (URL-encoded). Album names
     * are unique even when albumNumber isn't (two "#78.5" entries
     * exist in the catalog), so name is the right join key.
     */
    private val albumKey: String = savedState.get<String>("albumKey")
        ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""

    val album = episodes.observeAll()
        .map { eps ->
            val keys = eps.map {
                LocalEpisodeKey(
                    title = it.title,
                    hasFile = it.filePath != null,
                    backedUp = it.archivedAt != null,
                    raw = it,
                )
            }
            joinAlbumOwnership(catalog.catalog, keys).firstOrNull {
                it.album.name == albumKey
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Episode IDs the user has finished (≥95% played per
     * OdysseyPlaybackService). Drives the "Hide listened" toggle on
     * the album detail rows so a user mid-binge can quickly see
     * what's left.
     */
    val completedIds = playback.observeCompletedIds()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun play(row: CatalogEpisodeWithOwnership) {
        val local = row.localEpisode as? LocalEpisodeEntity ?: run {
            DebugLogger.w("AlbumDetailVm", "play() — no local row for ${row.catalogEp.name}")
            return
        }
        // Album detail already has a high-quality catalog thumbnail —
        // pass it through to the player so MiniPlayer + NowPlayingScreen
        // get the right per-episode art on lockscreen.
        val artwork = row.catalogEp.thumbnailMedium ?: row.catalogEp.thumbnailSmall ?: local.imageUrl
        DebugLogger.i("AlbumDetailVm", "play(${local.episodeId}) from album detail")
        viewModelScope.launch {
            try {
                when {
                    // On-disk file — playLocal beats every other path.
                    local.filePath != null -> player.playLocal(local, artwork)
                    // Server-mirrored row (downloadUrl is "backup://N").
                    // Resolve the real audio URL via NasClient and stream
                    // — auth header is injected by MediaCache's HTTP
                    // factory so the bearer-token-protected /audio
                    // endpoint accepts the request.
                    local.downloadUrl.startsWith("backup://") -> {
                        val audio = nas.audioUrl(local.episodeId).getOrNull()
                        if (audio == null) {
                            DebugLogger.w("AlbumDetailVm", "play(${local.episodeId}) — backup URL but NAS not configured")
                            return@launch
                        }
                        player.playStream(local.episodeId, audio.url, local.title, artwork)
                    }
                    // Oneplace download URL — public, no auth needed.
                    else -> when (playSourceFor(local.filePath, local.downloadUrl)) {
                        is PlaySource.Local -> player.playLocal(local, artwork)
                        is PlaySource.Stream -> player.playStream(local.episodeId, local.downloadUrl, local.title, artwork)
                    }
                }
            } catch (t: Throwable) {
                DebugLogger.e("AlbumDetailVm", "play threw", t)
            }
        }
    }

    /**
     * Pin a server-only episode onto the phone for offline play.
     * Schedules the same RestoreEpisodeWorker the Backup tab uses.
     * Same call regardless of whether the row is server-mirrored
     * or genuinely UNAVAILABLE (the catalog episode just isn't on
     * the user's phone yet) — the worker creates the row.
     */
    fun pinOffline(row: CatalogEpisodeWithOwnership) = viewModelScope.launch {
        val local = row.localEpisode as? LocalEpisodeEntity ?: run {
            DebugLogger.w("AlbumDetailVm", "pinOffline — no local row for ${row.catalogEp.name}")
            return@launch
        }
        val allowMetered = settings.flow.first().allowMeteredDownloads
        scheduler.enqueueRestore(
            episodeId = local.episodeId,
            title = local.title,
            airDate = local.airDate,
            album = null, // server-side enrichment handles album
            description = local.description,
            durationSecs = local.durationMs / 1000,
            allowMetered = allowMetered,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun AlbumDetailScreen(
    onBack: () -> Unit = {},
    vm: AlbumDetailVm = hiltViewModel(),
) {
    val album by vm.album.collectAsState()
    val completedIds by vm.completedIds.collectAsState()
    var downloadedOnly by remember { mutableStateOf(false) }
    var hideListened by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(album?.album?.name ?: "Album") },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("album-back")) {
                        Text("Back")
                    }
                },
            )
        },
    ) { padding ->
        val a = album
        if (a == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { Text("Loading…") }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .semantics { testTagsAsResourceId = true }
                .testTag("album-detail"),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { AlbumDetailHeader(a) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show only what I have", modifier = Modifier.weight(1f))
                    Switch(
                        checked = downloadedOnly,
                        onCheckedChange = { downloadedOnly = it },
                        modifier = Modifier.testTag("album-downloaded-toggle"),
                    )
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hide listened", modifier = Modifier.weight(1f))
                    Switch(
                        checked = hideListened,
                        onCheckedChange = { hideListened = it },
                        modifier = Modifier.testTag("album-hide-listened-toggle"),
                    )
                }
            }
            var visible = a.episodes
            if (downloadedOnly) {
                // "What I have" = on phone OR on backup (a row that's
                // only on the server still counts as "I have it").
                visible = visible.filter { it.ownership != EpisodeOwnership.UNAVAILABLE || it.backedUp }
            }
            if (hideListened) {
                // Filter rows where the matched local episode is in
                // the completedIds set. UNAVAILABLE rows have no local
                // episodeId at all and stay visible (can't be "listened
                // to" without a local row).
                visible = visible.filter { row ->
                    val ep = row.localEpisode as? LocalEpisodeEntity
                    ep == null || ep.episodeId !in completedIds
                }
            }
            items(visible, key = { it.catalogEp.shortName.ifBlank { it.catalogEp.name } }) { row ->
                AlbumEpisodeRow(
                    row,
                    onPlay = { vm.play(row) },
                    onPinOffline = { vm.pinOffline(row) },
                )
            }
        }
    }
}

@Composable
private fun AlbumDetailHeader(a: AlbumWithOwnership) {
    Column {
        Row(verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = a.album.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = a.album.name ?: "Album",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = ownershipSummary(a),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!a.album.description.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = a.album.description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("album-description"),
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun AlbumEpisodeRow(
    row: CatalogEpisodeWithOwnership,
    onPlay: () -> Unit,
    onPinOffline: () -> Unit = {},
) {
    val onPhone = row.ownership == EpisodeOwnership.DOWNLOADED
    // Playable whenever we have ANY way to source bytes: local file,
    // oneplace stream URL, or a backup://N row resolvable via NAS audio
    // URL inside AlbumDetailVm.play.
    val playable = row.ownership != EpisodeOwnership.UNAVAILABLE
    val displayName = row.catalogEp.shortName.ifBlank { row.catalogEp.name }
    val local = row.localEpisode as? LocalEpisodeEntity
    // Catalog description (from the Salesforce-backed AIO API) is the
    // most reliable source — it's there for every episode the AIO
    // catalog knows about. Local DB description is a fallback for
    // catalog-misses (rare) or for episodes the user has on disk that
    // somehow don't match a catalog row.
    val description = row.catalogEp.description?.takeIf { it.isNotBlank() }
        ?: local?.description?.takeIf { it.isNotBlank() }
    // Card only earns its tap when there's something to reveal —
    // description text or any actionable button.
    val canExpand = description != null || playable || (row.backedUp && !onPhone)
    var expanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(enabled = canExpand) { expanded = !expanded }
            .testTag(
                when (row.ownership) {
                    EpisodeOwnership.DOWNLOADED -> "album-ep-downloaded"
                    EpisodeOwnership.STREAMABLE -> "album-ep-streamable"
                    EpisodeOwnership.UNAVAILABLE -> "album-ep-unavailable"
                },
            ),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                AsyncImage(
                    model = row.catalogEp.thumbnailMedium ?: row.catalogEp.thumbnailSmall,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
                )
            },
            headlineContent = { Text(displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            trailingContent = {
                // Two independent dimensions: on-phone (local file) and
                // on-backup (archivedAt set). A row can be both, either,
                // or — for catalog episodes the user has never touched —
                // neither (UNAVAILABLE). Stack so each badge gets its
                // own line without truncating.
                Column(horizontalAlignment = Alignment.End) {
                    when (row.ownership) {
                        EpisodeOwnership.DOWNLOADED -> Text(
                            "✓ on phone",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("album-ep-on-phone"),
                        )
                        EpisodeOwnership.STREAMABLE -> Text(
                            "▶ stream",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        EpisodeOwnership.UNAVAILABLE -> if (!row.backedUp) {
                            Text("—", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (row.backedUp) {
                        Text(
                            "☁ on backup",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("album-ep-on-backup"),
                        )
                    }
                }
            },
        )
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .testTag("album-ep-expanded"),
            ) {
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (playable) {
                        FilledTonalButton(
                            onClick = onPlay,
                            modifier = Modifier.testTag("album-ep-play"),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Play")
                        }
                    }
                    if (row.backedUp && !onPhone) {
                        if (playable) Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = onPinOffline,
                            modifier = Modifier.testTag("album-ep-pin-offline"),
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Pin offline")
                        }
                    }
                }
            }
        }
    }
}
