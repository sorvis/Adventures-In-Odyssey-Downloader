package com.odyssey.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            // Artwork sits inside a flexible Box so it shrinks on small
            // screens to keep the transport row visible without scroll.
            // weight(1f, fill = false) lets it claim leftover space but
            // not inflate beyond its 1:1 aspect ratio.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = vm.artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .testTag("now-playing-art"),
                )
            }

            // Title.
            Text(
                text = vm.title.ifBlank { "Nothing playing" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("now-playing-title"),
            )

            // Description capped to 2 lines so transport always fits
            // without scroll. v0.1.77: when the text actually overflows
            // (didOverflowHeight from onTextLayout), surface a
            // "Show more" link that pops a scrollable dialog with the
            // full text — users were missing meaningful description
            // text after the ellipsis.
            if (vm.description.isNotBlank()) {
                var descOverflowed by remember(vm.description) { mutableStateOf(false) }
                var showFullDescription by remember { mutableStateOf(false) }
                Text(
                    text = vm.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layout -> descOverflowed = layout.hasVisualOverflow },
                    modifier = Modifier
                        .let { if (descOverflowed) it.clickable { showFullDescription = true } else it }
                        .testTag("now-playing-description"),
                )
                if (descOverflowed) {
                    TextButton(
                        onClick = { showFullDescription = true },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        modifier = Modifier.testTag("now-playing-description-more"),
                    ) {
                        Text("Show more", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (showFullDescription) {
                    AlertDialog(
                        onDismissRequest = { showFullDescription = false },
                        title = { Text(vm.title.ifBlank { "Episode" }) },
                        text = {
                            // Dialog body scrolls if the description is
                            // genuinely long — covers oneplace's short
                            // blurbs AND the richer AIO-app descriptions
                            // we'll land in v0.1.78.
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                            ) {
                                Text(
                                    text = vm.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("now-playing-description-full"),
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showFullDescription = false }) { Text("Close") }
                        },
                    )
                }
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

            // Transport controls.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                IconButton(onClick = vm::back30, modifier = Modifier.testTag("back-30")) {
                    Icon(Icons.Default.Replay, "−30s", modifier = Modifier.size(32.dp))
                }
                FilledIconButton(
                    onClick = vm::togglePlay,
                    modifier = Modifier.size(72.dp).testTag("play-pause"),
                ) {
                    Icon(
                        if (vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (vm.playing) "Pause" else "Play",
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = vm::fwd30, modifier = Modifier.testTag("fwd-30")) {
                    Icon(Icons.Default.Forward30, "+30s", modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
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
