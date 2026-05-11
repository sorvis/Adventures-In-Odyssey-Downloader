package com.odyssey.ui.screens

import androidx.compose.foundation.background
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
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.YshAlbumSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class YshAlbumListVm @Inject constructor(
    episodes: EpisodeDao,
) : ViewModel() {
    /**
     * One row per YSH album the user has at least one track of. YSH
     * library grows over time as the rotating pools surface new
     * stories — empty albums (with no ingested tracks) are not shown
     * here. A "Browse full catalog" subscreen could land later if the
     * user wants to wishlist by album.
     */
    val albums = episodes.observeYshAlbumSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun YshAlbumListScreen(
    onOpenAlbum: (albumName: String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    vm: YshAlbumListVm = hiltViewModel(),
) {
    val albums by vm.albums.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your Story Hour") },
                actions = { ShowSwitcher(onOpenSettings = onOpenSettings) },
            )
        },
    ) { padding ->
        if (albums.isEmpty()) {
            YshAlbumsEmptyState(modifier = Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .testTag("ysh-album-list"),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(albums, key = { it.albumName }) { album ->
                YshAlbumRow(album, onClick = { onOpenAlbum(album.albumName) })
            }
        }
    }
}

@Composable
internal fun YshAlbumRow(
    album: YshAlbumSummary,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("ysh-album-row-${album.albumName}"),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!album.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = album.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    album.albumName,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    trackCountLabel(album),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun YshAlbumsEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("ysh-albums-empty"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No Your Story Hour episodes yet.",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "The next daily check will pick up the currently-free " +
                "samples from yourstoryhour.org plus today's broadcast " +
                "from oneplace.com. Your library grows over time as " +
                "they rotate new stories in.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Pure helper — produces the row subtitle. Visible for tests so we
 * don't have to drive a Compose preview to verify pluralization.
 */
internal fun trackCountLabel(album: YshAlbumSummary): String {
    val trackWord = if (album.trackCount == 1) "track" else "tracks"
    return if (album.downloadedCount == album.trackCount) {
        "${album.trackCount} $trackWord · all downloaded"
    } else {
        "${album.downloadedCount} of ${album.trackCount} $trackWord downloaded"
    }
}

