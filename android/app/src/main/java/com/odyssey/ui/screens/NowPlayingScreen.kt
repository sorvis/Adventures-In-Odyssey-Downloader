package com.odyssey.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
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
    var description by mutableStateOf(""); private set
    var artworkUri by mutableStateOf<Uri?>(null); private set

    /** Has anything ever been loaded? Drives MiniPlayer visibility. */
    val hasContent: Boolean get() = title.isNotEmpty() || artworkUri != null

    init {
        viewModelScope.launch {
            val c = player.connect()
            controller = c
            while (true) {
                positionMs = c.currentPosition
                durationMs = c.duration.coerceAtLeast(0)
                playing = c.isPlaying
                val item = c.currentMediaItem
                title = item?.mediaMetadata?.title?.toString().orEmpty()
                description = item?.mediaMetadata?.description?.toString().orEmpty()
                artworkUri = item?.mediaMetadata?.artworkUri
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

/**
 * Persistent mini-player above the bottom NavigationBar. Renders only
 * when there's something to show. Tap → onExpand (caller navigates to
 * full NowPlayingScreen).
 */
@Composable
fun MiniPlayerBar(
    onExpand: () -> Unit,
    vm: NowPlayingVm = hiltViewModel(),
) {
    if (!vm.hasContent) return
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mini-player"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onExpand)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            AsyncImage(
                model = vm.artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = vm.title.ifBlank { "Nothing playing" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (vm.durationMs > 0) {
                    LinearProgressIndicator(
                        progress = { (vm.positionMs.toFloat() / vm.durationMs).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .testTag("mini-progress"),
                    )
                }
            }
            IconButton(
                onClick = vm::togglePlay,
                modifier = Modifier.testTag("mini-play-pause"),
            ) {
                Icon(
                    if (vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (vm.playing) "Pause" else "Play",
                )
            }
        }
    }
}

/**
 * Full-screen player. Modeled on BeyondPod's player: down-arrow back
 * at top, big square artwork, title, position/-remaining time labels,
 * draggable seek bar, ±30s skip + big play/pause.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit = {},
    vm: NowPlayingVm = hiltViewModel(),
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf(0f) }

    val durationMs = vm.durationMs
    val knownDuration = durationMs > 0
    val livePos = vm.positionMs
    val displayPos = if (dragging) (dragFrac * durationMs).toLong() else livePos
    val sliderValue = if (dragging) dragFrac else if (knownDuration)
        (livePos.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("now-playing-collapse"),
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Back to list")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .semantics { testTagsAsResourceId = true }
                .testTag("now-playing"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            // Big square artwork — visual anchor of the screen.
            AsyncImage(
                model = vm.artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .testTag("now-playing-art"),
            )

            // Title.
            Text(
                text = vm.title.ifBlank { "Nothing playing" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("now-playing-title"),
            )

            // Description (oneplace blurb / catalog full description) —
            // rendered when present, capped to 4 lines so it doesn't push
            // the seek bar off-screen on small devices.
            if (vm.description.isNotBlank()) {
                Text(
                    text = vm.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("now-playing-description"),
                )
            }

            // Seek bar + position/remaining row.
            if (knownDuration) {
                Slider(
                    value = sliderValue,
                    onValueChange = { v -> dragging = true; dragFrac = v },
                    onValueChangeFinished = {
                        vm.seekTo(seekTargetMs(dragFrac, durationMs))
                        dragging = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("seek-bar"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(fmt(displayPos), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("position"))
                    // Remaining time as a negative — matches BeyondPod and
                    // most podcast apps.
                    Text(
                        text = "-" + fmt((durationMs - displayPos).coerceAtLeast(0)),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("remaining"),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            // Transport controls.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                IconButton(onClick = vm::back30, modifier = Modifier.testTag("back-30")) {
                    Icon(Icons.Default.Replay, "−30s", modifier = Modifier.size(36.dp))
                }
                FilledIconButton(
                    onClick = vm::togglePlay,
                    modifier = Modifier.size(80.dp).testTag("play-pause"),
                ) {
                    Icon(
                        if (vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (vm.playing) "Pause" else "Play",
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = vm::fwd30, modifier = Modifier.testTag("fwd-30")) {
                    Icon(Icons.Default.Forward30, "+30s", modifier = Modifier.size(36.dp))
                }
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
