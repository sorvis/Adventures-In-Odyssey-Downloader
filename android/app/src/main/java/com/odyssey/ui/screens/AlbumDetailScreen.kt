package com.odyssey.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
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
    private val catalog: AioCatalogRepo,
    private val player: EpisodePlayer,
) : ViewModel() {

    /** "albumKey" route arg: album_number string ("81", "78.5", "OHC"). */
    private val albumKey: String = savedState["albumKey"] ?: ""

    /**
     * Reuses the SAME pure join helper as AlbumListScreen, then picks
     * the one album we want. Cheap because the catalog is in memory.
     */
    val album = episodes.observeAll()
        .map { eps ->
            val keys = eps.map { LocalEpisodeKey(it.title, it.filePath != null, it) }
            joinAlbumOwnership(catalog.catalog, keys).firstOrNull {
                it.album.albumNumber == albumKey
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun play(row: CatalogEpisodeWithOwnership) {
        val local = row.localEpisode as? LocalEpisodeEntity ?: run {
            DebugLogger.w("AlbumDetailVm", "play() — no local row for ${row.catalogEp.name}")
            return
        }
        DebugLogger.i("AlbumDetailVm", "play(${local.episodeId}) from album detail")
        viewModelScope.launch {
            try {
                when (playSourceFor(local.filePath, local.downloadUrl)) {
                    is PlaySource.Local -> player.playLocal(local)
                    is PlaySource.Stream -> player.playStream(local.episodeId, local.downloadUrl, local.title)
                }
            } catch (t: Throwable) {
                DebugLogger.e("AlbumDetailVm", "play threw", t)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun AlbumDetailScreen(
    onBack: () -> Unit = {},
    vm: AlbumDetailVm = hiltViewModel(),
) {
    val album by vm.album.collectAsState()
    var downloadedOnly by remember { mutableStateOf(false) }

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
            val visible = if (downloadedOnly) {
                a.episodes.filter { it.ownership != EpisodeOwnership.UNAVAILABLE }
            } else a.episodes
            items(visible, key = { it.catalogEp.shortName.ifBlank { it.catalogEp.name } }) { row ->
                AlbumEpisodeRow(row, onPlay = { vm.play(row) })
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
private fun AlbumEpisodeRow(row: CatalogEpisodeWithOwnership, onPlay: () -> Unit) {
    val playable = row.ownership != EpisodeOwnership.UNAVAILABLE
    val displayName = row.catalogEp.shortName.ifBlank { row.catalogEp.name }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = playable, onClick = onPlay)
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
                Text(
                    text = when (row.ownership) {
                        EpisodeOwnership.DOWNLOADED -> "✓ on phone"
                        EpisodeOwnership.STREAMABLE -> "▶ stream"
                        EpisodeOwnership.UNAVAILABLE -> "—"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            },
        )
    }
}
