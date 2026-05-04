package com.odyssey.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.odyssey.catalog.AlbumSort
import com.odyssey.catalog.AlbumWithOwnership
import com.odyssey.catalog.LocalEpisodeKey
import com.odyssey.catalog.joinAlbumOwnership
import com.odyssey.catalog.ownershipSummary
import com.odyssey.catalog.sortAlbums
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
    // rememberSaveable so the choice survives tab-switches; tied to
    // the screen, not persisted across launches.
    var sortMode by rememberSaveable { mutableStateOf(AlbumSort.Default) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val sorted = remember(albums, sortMode) { sortAlbums(albums, sortMode) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Albums") },
                actions = {
                    Box {
                        IconButton(
                            onClick = { sortMenuOpen = true },
                            modifier = Modifier.testTag("album-sort-menu"),
                        ) {
                            Icon(Icons.Default.Sort, contentDescription = "Sort albums")
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            for (mode in AlbumSort.values()) {
                                DropdownMenuItem(
                                    text = { Text(mode.label()) },
                                    trailingIcon = if (mode == sortMode) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null,
                                    onClick = {
                                        sortMode = mode
                                        sortMenuOpen = false
                                    },
                                    modifier = Modifier.testTag("album-sort-${mode.name}"),
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (sorted.isEmpty()) {
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
            // The catalog has multiple albums sharing an albumNumber
            // (e.g. two "#78.5" entries: regular and special), so
            // albumNumber alone is NOT unique → Compose crashes on
            // duplicate keys. Album NAME is unique; using that.
            items(sorted, key = { it.album.name ?: it.album.albumNumber ?: "" }) { row ->
                AlbumListRow(row, onClick = {
                    val key = row.album.name ?: row.album.albumNumber ?: return@AlbumListRow
                    onOpenAlbum(java.net.URLEncoder.encode(key, "UTF-8"))
                })
            }
        }
    }
}

private fun AlbumSort.label() = when (this) {
    AlbumSort.Default -> "Default"
    AlbumSort.Chronological -> "Chronological"
    AlbumSort.MostDownloaded -> "Most downloaded"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumListRow(row: AlbumWithOwnership, onClick: () -> Unit) {
    // "Gray haze" for albums with nothing on disk yet. Card is still
    // tappable — alpha just dims content so the empty ones recede
    // visually while collected ones pop. testTag includes the haze
    // state so UI tests can assert it.
    val empty = row.downloadedCount == 0
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (empty) 0.45f else 1f)
            .testTag("album-row-${row.album.albumNumber}${if (empty) "-empty" else ""}"),
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

