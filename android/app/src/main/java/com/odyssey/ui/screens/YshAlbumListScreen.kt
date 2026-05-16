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
import com.odyssey.catalog.AlbumFilter
import com.odyssey.catalog.AlbumSort
import com.odyssey.data.local.EpisodeDao
import com.odyssey.show.YshAlbumCatalogRow
import com.odyssey.show.YshCatalog
import com.odyssey.show.filterYshAlbums
import com.odyssey.show.joinYshAlbumOwnership
import com.odyssey.show.sortYshAlbums
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class YshAlbumListVm @Inject constructor(
    episodes: EpisodeDao,
    catalog: YshCatalog,
) : ViewModel() {
    /**
     * One row per YSH catalog album. Catalog drives the universe of
     * albums (so the user can see all ~88 even before any tracks have
     * downloaded); the DB-side summary contributes per-album
     * downloadedTracks for the badge + fade. Emits empty until the
     * catalog has loaded (fresh install before YshCatalogRefreshWorker
     * fires) — the screen renders a "loading the catalog" message in
     * that case rather than the old "no episodes yet" empty state.
     */
    val albums = combine(
        catalog.state,
        episodes.observeAll(),
    ) { idx, rows ->
        if (idx == null) emptyList()
        // Pass the raw row list (not a pre-aggregated summary): the
        // join keys off skuId in the externalId, NOT the row's
        // `albumName` field — which DailyCheckWorker leaves null on
        // every YSH row, so any albumName-based aggregation in the
        // DAO would yield zero downloaded for everything.
        else joinYshAlbumOwnership(idx, rows)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Distinguishes "catalog hasn't loaded yet" from "catalog loaded
     * but turned up zero albums" so the empty state can be specific.
     */
    val catalogLoaded = catalog.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun YshAlbumListScreen(
    onOpenAlbum: (albumName: String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    vm: YshAlbumListVm = hiltViewModel(),
) {
    val albums by vm.albums.collectAsState()
    val catalogLoaded by vm.catalogLoaded.collectAsState()
    // rememberSaveable so the choice survives tab-switches; tied to
    // the screen, not persisted across launches. Matches AIO's pattern.
    var sortMode by rememberSaveable { mutableStateOf(AlbumSort.Default) }
    var filterMode by rememberSaveable { mutableStateOf(AlbumFilter.All) }
    val sorted = remember(albums, sortMode, filterMode) {
        sortYshAlbums(filterYshAlbums(albums, filterMode), sortMode)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your Story Hour") },
                actions = {
                    ShowSwitcher(onOpenSettings = onOpenSettings)
                    // YSH hides HasOnBackup -- no backup upload path
                    // for YSH today (archive-service is AIO-only).
                    AlbumSortFilterActions(
                        sortMode = sortMode,
                        onSortChange = { sortMode = it },
                        filterMode = filterMode,
                        onFilterChange = { filterMode = it },
                        availableFilters = listOf(AlbumFilter.All, AlbumFilter.HasOnPhone),
                    )
                },
            )
        },
    ) { padding ->
        if (sorted.isEmpty()) {
            // Bifurcate empty-state copy: catalog still loading vs
            // filter excluded everything vs catalog truly empty.
            val msg = when {
                catalogLoaded == null -> null   // shows the "loading" state
                albums.isEmpty() -> null        // catalog loaded but no rows
                filterMode == AlbumFilter.HasOnPhone -> "No albums with episodes on phone yet."
                else -> "Nothing to show."
            }
            if (msg != null) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(msg, modifier = Modifier.padding(32.dp))
                }
            } else {
                YshAlbumsEmptyState(
                    catalogLoaded = catalogLoaded != null,
                    modifier = Modifier.padding(padding).padding(24.dp),
                )
            }
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
            items(sorted, key = { it.albumId }) { album ->
                YshAlbumRow(album, onClick = { onOpenAlbum(album.albumName) })
            }
        }
    }
}

@Composable
internal fun YshAlbumRow(
    album: YshAlbumCatalogRow,
    onClick: () -> Unit,
) {
    // Match the AIO Albums tab: rows with zero downloaded tracks are
    // faded so the eye lands on albums the user is actively collecting.
    // Tap is still enabled — the detail screen handles the empty case.
    val faded = album.downloadedTracks == 0
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .alpha(if (faded) 0.45f else 1f)
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
private fun YshAlbumsEmptyState(
    catalogLoaded: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("ysh-albums-empty"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!catalogLoaded) {
            Text("Loading Your Story Hour album catalog…", style = MaterialTheme.typography.titleMedium)
            Text(
                "First launch fetches the album list from yourstoryhour.org. " +
                    "This usually takes a few seconds.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text("No Your Story Hour albums.", style = MaterialTheme.typography.titleMedium)
            Text(
                "The catalog loaded but reported zero albums — try " +
                    "Refresh in Settings, or check the debug log.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Pure helper -- produces the row subtitle. Visible for tests so we
 * don't have to drive a Compose preview to verify pluralization.
 */
internal fun trackCountLabel(album: YshAlbumCatalogRow): String {
    val trackWord = if (album.totalTracks == 1) "track" else "tracks"
    return if (album.downloadedTracks == album.totalTracks && album.totalTracks > 0) {
        "${album.totalTracks} $trackWord · all downloaded"
    } else {
        "${album.downloadedTracks} of ${album.totalTracks} $trackWord downloaded"
    }
}
