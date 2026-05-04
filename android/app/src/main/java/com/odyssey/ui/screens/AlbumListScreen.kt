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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.odyssey.catalog.AioCatalogRepo
import com.odyssey.catalog.AlbumWithOwnership
import com.odyssey.catalog.LocalEpisodeKey
import com.odyssey.catalog.joinAlbumOwnership
import com.odyssey.data.local.EpisodeDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AlbumListVm @Inject constructor(
    private val episodes: EpisodeDao,
    private val catalog: AioCatalogRepo,
) : ViewModel() {

    /**
     * The full catalog joined with the user's local episodes — runs
     * once per Room change, cached in StateFlow. Tested by
     * AlbumOwnershipTest (the join is a pure function).
     */
    val albums = episodes.observeAll()
        .map { eps ->
            val keys = eps.map { LocalEpisodeKey(title = it.title, hasFile = it.filePath != null, raw = it) }
            joinAlbumOwnership(catalog.catalog, keys)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun AlbumListScreen(
    onOpenAlbum: (String) -> Unit = {},
    vm: AlbumListVm = hiltViewModel(),
) {
    val albums by vm.albums.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("Albums") }) }) { padding ->
        if (albums.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("Loading catalog…", modifier = Modifier.padding(32.dp))
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .semantics { testTagsAsResourceId = true }
                .testTag("album-list"),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(albums, key = { it.album.albumNumber ?: it.album.name ?: "" }) { row ->
                AlbumListRow(row, onClick = {
                    val key = row.album.albumNumber ?: row.album.name ?: return@AlbumListRow
                    onOpenAlbum(key)
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumListRow(row: AlbumWithOwnership, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("album-row-${row.album.albumNumber}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            AsyncImage(
                model = row.album.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.album.name ?: "Album ${row.album.albumNumber}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = ownershipSummary(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Pure-string summary so renders don't recompute formatting per frame. */
internal fun ownershipSummary(row: AlbumWithOwnership): String {
    val total = row.totalCount
    val downloaded = row.downloadedCount
    val streamable = row.streamableCount
    val parts = mutableListOf("$total episodes")
    if (downloaded > 0) parts += "$downloaded downloaded"
    if (streamable > 0) parts += "$streamable streamable"
    return parts.joinToString(" • ")
}
