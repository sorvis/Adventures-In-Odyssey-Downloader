package com.odyssey.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import com.odyssey.player.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NowPlayingVm @Inject constructor(private val player: PlayerController) : ViewModel() {
    var controller by mutableStateOf<MediaController?>(null); private set
    var positionMs by mutableStateOf(0L); private set
    var durationMs by mutableStateOf(0L); private set
    var playing by mutableStateOf(false); private set
    var title by mutableStateOf(""); private set

    init {
        viewModelScope.launch {
            val c = player.connect()
            controller = c
            while (true) {
                positionMs = c.currentPosition
                durationMs = c.duration.coerceAtLeast(0)
                playing = c.isPlaying
                title = c.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
                delay(500)
            }
        }
    }

    fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun back30()     { controller?.let { it.seekTo((it.currentPosition - 30_000).coerceAtLeast(0)) } }
    fun fwd30()      { controller?.let { it.seekTo((it.currentPosition + 30_000).coerceAtMost(it.duration)) } }
}

@Composable
fun NowPlayingScreen(vm: NowPlayingVm = hiltViewModel()) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(vm.title.ifBlank { "Nothing playing" }, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        if (vm.durationMs > 0) {
            LinearProgressIndicator(
                progress = { (vm.positionMs.toFloat() / vm.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text("${fmt(vm.positionMs)} / ${fmt(vm.durationMs)}")
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton(onClick = vm::back30) { Icon(Icons.Default.Replay, "−30s") }
            FilledIconButton(onClick = vm::togglePlay, modifier = Modifier.size(72.dp)) {
                Icon(if (vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "play/pause")
            }
            IconButton(onClick = vm::fwd30) { Icon(Icons.Default.Forward30, "+30s") }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
