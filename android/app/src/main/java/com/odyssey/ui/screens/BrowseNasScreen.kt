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
import com.odyssey.nas.NasAlbum
import com.odyssey.nas.NasClient
import com.odyssey.nas.NasEpisode
import com.odyssey.player.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BrowseVm @Inject constructor(
    private val nas: NasClient,
    private val settings: SettingsRepo,
    private val player: PlayerController,
) : ViewModel() {
    val configured = settings.flow.map { it.nasConfigured }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val albums = MutableStateFlow<List<NasAlbum>>(emptyList())
    val results = MutableStateFlow<List<NasEpisode>>(emptyList())
    val error = MutableStateFlow<String?>(null)

    fun refresh() = viewModelScope.launch {
        nas.listAlbums().fold({ albums.value = it; error.value = null },
                              { error.value = "Couldn't reach NAS: ${it.message}" })
    }

    fun search(q: String, album: String?) = viewModelScope.launch {
        nas.search(q, album).fold({ results.value = it; error.value = null },
                                  { error.value = "Search failed: ${it.message}" })
    }

    fun stream(ep: NasEpisode) = viewModelScope.launch {
        nas.audioUrl(ep.episode_id).onSuccess {
            // ExoPlayer DataSource auth handled at the OkHttp factory layer in
            // production; for now we pass the bare URL and assume the token
            // is appended via interceptor (TODO).
            player.playStream(ep.episode_id, it.url, ep.title)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseNasScreen(
    onOpenTransfers: () -> Unit = {},
    vm: BrowseVm = hiltViewModel(),
) {
    val configured by vm.configured.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NAS Archive") },
                actions = {
                    TextButton(
                        onClick = onOpenTransfers,
                        modifier = Modifier.testTag("open-transfers"),
                    ) { Text("Transfers") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (!configured) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No NAS configured.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The app works fully without one — set up your NAS in Settings " +
                        "to browse and pull from your archive.",
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

@Composable
private fun BrowseContent(vm: BrowseVm) {
    val albums by vm.albums.collectAsState()
    val results by vm.results.collectAsState()
    val error by vm.error.collectAsState()
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
                    ListItem(
                        modifier = Modifier.clickable { vm.stream(ep) },
                        headlineContent = { Text(ep.title) },
                        supportingContent = { Text("${ep.album ?: "Unsorted"} · ${ep.air_date ?: ""}") },
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
