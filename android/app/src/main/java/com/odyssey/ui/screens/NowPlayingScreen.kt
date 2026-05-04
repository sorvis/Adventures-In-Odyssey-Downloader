package com.odyssey.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import com.odyssey.player.PlayerController
import com.odyssey.player.seekTargetMs
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
    fun seekTo(ms: Long) {
        controller?.let { c ->
            val dur = c.duration.coerceAtLeast(0)
            c.seekTo(ms.coerceIn(0, dur))
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NowPlayingScreen(vm: NowPlayingVm = hiltViewModel()) {
    // While the user is dragging the seek bar, the position observer
    // shouldn't fight the gesture — show the dragged value instead.
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf(0f) }

    val durationMs = vm.durationMs
    val knownDuration = durationMs > 0
    val livePos = vm.positionMs
    val displayPos = if (dragging) (dragFrac * durationMs).toLong() else livePos
    val sliderValue = if (dragging) {
        dragFrac
    } else if (knownDuration) {
        (livePos.toFloat() / durationMs).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .semantics { testTagsAsResourceId = true }
            .testTag("now-playing"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            vm.title.ifBlank { "Nothing playing" },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("now-playing-title"),
        )
        Spacer(Modifier.height(24.dp))
        if (knownDuration) {
            // Draggable seek bar — Material3 Slider is the standard
            // Compose-native answer; releasing the thumb commits the
            // seek via vm.seekTo. While dragging we show the drag
            // value so the time label tracks the user's finger.
            Slider(
                value = sliderValue,
                onValueChange = { v ->
                    dragging = true
                    dragFrac = v
                },
                onValueChangeFinished = {
                    vm.seekTo(seekTargetMs(dragFrac, durationMs))
                    dragging = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("seek-bar"),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${fmt(displayPos)} / ${fmt(durationMs)}",
                modifier = Modifier.testTag("position"),
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton(onClick = vm::back30, modifier = Modifier.testTag("back-30")) {
                Icon(Icons.Default.Replay, "−30s")
            }
            FilledIconButton(
                onClick = vm::togglePlay,
                modifier = Modifier.size(72.dp).testTag("play-pause"),
            ) {
                Icon(if (vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "play/pause")
            }
            IconButton(onClick = vm::fwd30, modifier = Modifier.testTag("fwd-30")) {
                Icon(Icons.Default.Forward30, "+30s")
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
