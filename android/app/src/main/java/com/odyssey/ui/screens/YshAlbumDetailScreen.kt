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
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.player.EpisodePlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

const val YSH_ALBUM_DETAIL_ARG = "albumName"

@HiltViewModel
class YshAlbumDetailVm @Inject constructor(
    private val episodes: EpisodeDao,
    private val player: EpisodePlayer,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val albumName: String = savedState.get<String>(YSH_ALBUM_DETAIL_ARG).orEmpty()

    val title: String get() = albumName

    val tracks = episodes.observeYshAlbumTracks(albumName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun play(track: LocalEpisodeEntity) {
        viewModelScope.launch {
            // YSH playback paths today: prefer the local file if we have
            // it; otherwise stream from the original (yourstoryhour S3 or
            // oneplace) downloadUrl. NAS pin-from-backup for YSH lands in
            // a later step alongside the archive-service rewrite.
            if (track.filePath != null) {
                player.playLocal(track, artworkUrl = track.albumImageUrl)
            } else {
                // We don't have a Long episodeId for YSH (externalId is
                // a string like "ysh-sku-1958"); for now we pass 0L as
                // the placeholder so the existing playStream signature
                // works. The mediaId in PlayerController will use the
                // composite key once the player layer goes fully
                // provider-aware in a later cleanup.
                player.playStream(
                    episodeId = 0L,
                    streamUrl = track.downloadUrl,
                    title = track.title,
                    artworkUrl = track.albumImageUrl,
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
    val tracks by vm.tracks.collectAsState()
    val coverUrl = tracks.firstOrNull()?.albumImageUrl

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
                items(tracks, key = { it.externalId }) { t ->
                    YshTrackRow(t, onPlay = { vm.play(t) })
                }
            }
        }
    }
}

@Composable
internal fun YshTrackRow(track: LocalEpisodeEntity, onPlay: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .testTag("ysh-track-row-${track.externalId}"),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = yshTrackSubtitle(track),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onPlay, modifier = Modifier.testTag("ysh-track-play-${track.externalId}")) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play ${track.title}")
            }
        }
    }
}

/**
 * Subtitle for a YSH track row. Visible for tests so we lock the
 * "downloaded" / "stream" copy without rendering Compose.
 */
internal fun yshTrackSubtitle(track: LocalEpisodeEntity): String {
    val orderPart = track.albumTrackOrder?.let { "#${it + 1}" }
    val statePart = if (track.filePath != null) "downloaded" else "stream"
    return listOfNotNull(orderPart, statePart).joinToString(" · ")
}
