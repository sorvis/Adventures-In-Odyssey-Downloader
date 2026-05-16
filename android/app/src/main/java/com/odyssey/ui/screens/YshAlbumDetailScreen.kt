package com.odyssey.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.odyssey.data.local.EpisodeDao
import com.odyssey.player.EpisodePlayer
import com.odyssey.show.YshAlbumDetailRow
import com.odyssey.show.YshCatalog
import com.odyssey.show.YshTrackOwnership
import com.odyssey.show.joinYshAlbumDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

const val YSH_ALBUM_DETAIL_ARG = "albumName"

@HiltViewModel
class YshAlbumDetailVm @Inject constructor(
    episodes: EpisodeDao,
    catalog: YshCatalog,
    private val player: EpisodePlayer,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val albumName: String = savedState.get<String>(YSH_ALBUM_DETAIL_ARG).orEmpty()

    val title: String get() = albumName

    /**
     * One row per CATALOG track for this album — ALL of them, with
     * per-track ownership overlay from the DB. Tracks the user has
     * pinned or has streamable via the rotating free pool come back
     * as DOWNLOADED/STREAMABLE; the rest as UNAVAILABLE (faded card,
     * play disabled). Mirrors AIO's album-detail UX.
     *
     * Pre-v0.1.55 the screen only listed DB-ingested tracks, so
     * tapping a faded album opened a near-empty list.
     */
    val rows = combine(
        catalog.state,
        episodes.observeAll(),
    ) { idx, dbRows ->
        if (idx == null) emptyList()
        // Pass ALL rows (filter by providerId happens inside the
        // helper via skuId join). Pre-v0.1.58 used
        // `observeYshAlbumTracks(albumName)` which is `WHERE
        // albumName = :albumName` — but DailyCheckWorker never sets
        // albumName on YSH rows, so the query returned zero and
        // every track rendered as UNAVAILABLE.
        else joinYshAlbumDetail(idx, albumName, dbRows)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun play(row: YshAlbumDetailRow) {
        val local = row.localRow ?: return  // UNAVAILABLE — UI disables tap, but defensive here too
        viewModelScope.launch {
            // YSH playback paths today: prefer the local file if we have
            // it; otherwise stream from the original (yourstoryhour S3 or
            // oneplace) downloadUrl. NAS pin-from-backup for YSH lands in
            // a later step alongside the archive-service rewrite.
            if (local.filePath != null) {
                player.playLocal(local, artworkUrl = row.albumImageUrl ?: local.albumImageUrl)
            } else {
                // We don't have a Long episodeId for YSH (externalId is
                // a string like "ysh-sku-1958"); for now we pass 0L as
                // the placeholder so the existing playStream signature
                // works. The mediaId in PlayerController will use the
                // composite key once the player layer goes fully
                // provider-aware in a later cleanup.
                player.playStream(
                    episodeId = 0L,
                    streamUrl = local.downloadUrl,
                    title = local.title,
                    artworkUrl = row.albumImageUrl ?: local.albumImageUrl,
                    providerId = "ysh",
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun YshAlbumDetailScreen(
    onBack: () -> Unit = {},
    vm: YshAlbumDetailVm = hiltViewModel(),
) {
    val rows by vm.rows.collectAsState()
    val coverUrl = rows.firstOrNull { !it.albumImageUrl.isNullOrBlank() }?.albumImageUrl

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.title) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("ysh-album-back")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true },
        ) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(16.dp)
                        .size(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .align(Alignment.CenterHorizontally),
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("ysh-track-list"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(rows, key = { it.skuId }) { row ->
                    YshTrackRow(row, onPlay = { vm.play(row) })
                }
            }
        }
    }
}

@Composable
internal fun YshTrackRow(row: YshAlbumDetailRow, onPlay: () -> Unit) {
    // UNAVAILABLE tracks: catalog knows the title but we have no DB
    // row, so streaming/playing would have no URL. Render faded, no
    // tap target, no play button. Matches AIO's catalog-only episode
    // treatment.
    val available = row.ownership != YshTrackOwnership.UNAVAILABLE
    val tagId = "ysh-sku-${row.skuId}"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (available) 1f else 0.45f)
            .let { if (available) it.clickable(onClick = onPlay) else it }
            .testTag("ysh-track-row-$tagId"),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = yshTrackSubtitle(row),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (available) {
                IconButton(onClick = onPlay, modifier = Modifier.testTag("ysh-track-play-$tagId")) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play ${row.title}")
                }
            }
        }
    }
}

/**
 * Subtitle for a YSH track row. Visible for tests so we lock the
 * "downloaded" / "stream" / "not in free pool" copy without rendering
 * Compose.
 */
internal fun yshTrackSubtitle(row: YshAlbumDetailRow): String {
    val orderPart = "#${row.orderIndex + 1}"
    val statePart = when (row.ownership) {
        YshTrackOwnership.DOWNLOADED -> "downloaded"
        YshTrackOwnership.STREAMABLE -> "stream"
        YshTrackOwnership.UNAVAILABLE -> "not in free pool"
    }
    return "$orderPart · $statePart"
}
