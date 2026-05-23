package com.odyssey.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.core.content.getSystemService
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odyssey.app.SettingsRepo
import com.odyssey.catalog.AioCatalogRepo
import com.odyssey.catalog.AioMatch
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.LocalEpisodeEntity
import com.odyssey.data.local.PlaybackDao
import com.odyssey.data.local.PlaybackPositionEntity
import com.odyssey.debug.DebugLogger
import com.odyssey.download.DownloadProgressEntry
import com.odyssey.download.DownloadProgressTracker
import com.odyssey.player.EpisodePlayer
import com.odyssey.player.PlaySource
import com.odyssey.player.formatRemaining
import com.odyssey.player.formatResumeSubtitle
import com.odyssey.player.formatTotalDuration
import com.odyssey.player.playSourceFor
import com.odyssey.work.DailyCheckSnapshot
import com.odyssey.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecentVm @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val episodes: EpisodeDao,
    val playback: PlaybackDao,
    private val player: EpisodePlayer,
    private val scheduler: WorkScheduler,
    private val settings: SettingsRepo,
    private val downloadProgress: DownloadProgressTracker,
    private val archiveProgress: com.odyssey.download.ArchiveProgressTracker,
    val catalog: AioCatalogRepo,
    private val yshCatalog: com.odyssey.show.YshCatalog,
    private val nas: com.odyssey.nas.NasClient,
) : ViewModel() {

    /**
     * Fallback artwork URL for YSH rows whose [LocalEpisodeEntity.imageUrl]
     * came back null from the provider (the free-streaming response's
     * `primary_image` is null surprisingly often). The catalog has the
     * album cover reliably; this picks it up by skuId. Returns null for
     * AIO rows and for YSH rows when the catalog hasn't loaded yet.
     */
    fun yshAlbumArtworkFor(ep: LocalEpisodeEntity): String? =
        com.odyssey.show.yshAlbumImageUrlForRow(ep, yshCatalog.state.value)

    val progress = downloadProgress.progress
    val archive = archiveProgress.progress
    // Sort by parsed air-date desc, falling back to externalId desc.
    // The SQL ORDER BY in EpisodeDao.observeAll() sorts the airDate
    // string, which works in-year but breaks across year boundaries —
    // re-sorting here with parseAirDateMillis fixes that.
    //
    // Filtered by activeShow so flipping to YSH in the dropdown
    // immediately shows YSH episodes here too. AIO/YSH externalIds
    // never overlap (different prefixes) so the filter is a clean cut.
    //
    // ALSO filtered to drop "backup-mirror ghosts" — rows inserted by
    // BrowseNasScreen.mirrorServerEpisodes() that exist purely to light
    // up the Albums tab's "☁ on backup" badge. They carry
    // sourceUrl="backup://<id>" + filePath=null, no on-phone audio,
    // and a year-only airDate ("2011") that doesn't parse — so they
    // used to pile up at the bottom of Recent looking like junk. Recent
    // is now "freshly aired ingests + episodes actually on this phone";
    // browsing the full server catalog stays on the Sync tab where it
    // belongs.
    val items = combine(episodes.observeAll(), settings.activeShow) { eps, active ->
        recentItemsFor(
            eps,
            activeShow = active,
            providerId = LocalEpisodeEntity::providerId,
            filePath = LocalEpisodeEntity::filePath,
            sourceUrl = LocalEpisodeEntity::sourceUrl,
            airDate = LocalEpisodeEntity::airDate,
            externalId = LocalEpisodeEntity::externalId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val resume = playback.observeMostRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Episodes the user has finished (≥95% per OdysseyPlaybackService). Used
    // to render the "✓ played" trailing chip on the Recent list.
    val completedIds =
        playback.observeCompletedIds()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<Long>())

    /** episodeId → saved playback position. Drives the "X min left" chip. */
    val positions = playback.observeAllPositions()
        .map { list -> list.associateBy { it.episodeId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // Pair the most-recent playback position with its episode entity so
    // "Continue listening" can show the real title and dispatch to play().
    val resumeEpisode = combine(items, resume) { eps, r ->
        if (r == null) null else eps.firstOrNull { it.episodeId == r.episodeId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val showMeteredWarning = MutableStateFlow(false)

    fun checkNow() {
        DebugLogger.i("RecentVm", "checkNow() — refresh tapped")
        viewModelScope.launch {
            val allowMetered = settings.flow.first().allowMeteredDownloads
            val metered = isOnMeteredNetwork()
            if (!allowMetered && metered) {
                DebugLogger.i(
                    "RecentVm",
                    "checkNow() — short-circuited (on metered, allowMetered=false); showing warning",
                )
                showMeteredWarning.value = true
                return@launch
            }
            DebugLogger.i(
                "RecentVm",
                "checkNow() — enqueueing DailyCheckWorker (metered=$metered, allowMetered=$allowMetered)",
            )
            scheduler.runDailyCheckNow()
        }
    }

    fun dismissWarning() { showMeteredWarning.value = false }

    private fun isOnMeteredNetwork(): Boolean {
        val cm = ctx.getSystemService<ConnectivityManager>() ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Live player state — exposed so the row UI can flip its primary
     * button to a Pause icon while THIS episode is the one playing.
     */
    val playerState = player.state

    /**
     * Single source of truth for "what is the Check-now worker doing."
     * `active` drives the pull-to-refresh spinner; `newCount` drives
     * the "Refresh complete — N new" snackbar. They MUST come from the
     * same StateFlow so Compose recomposes once with both fields in
     * sync — splitting them into two `.stateIn` flows reintroduces the
     * race the WorkInfo refactor was designed to eliminate.
     */
    val dailyCheck: kotlinx.coroutines.flow.StateFlow<DailyCheckSnapshot> =
        scheduler.dailyCheckSnapshot
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                DailyCheckSnapshot(active = false, newCount = 0),
            )

    fun play(ep: LocalEpisodeEntity) {
        // Tap on the row's button while THIS episode is already playing
        // → pause instead of re-issuing playLocal/playStream. Otherwise
        // dispatch as before.
        val s = player.state.value
        if (s.currentEpisodeId == ep.episodeId && s.isPlaying) {
            DebugLogger.i("RecentVm", "play(${ep.episodeId}) — pausing in-place")
            viewModelScope.launch { runCatching { player.pause() } }
            return
        }
        val artwork = catalog.match(ep.title)?.thumbnailUrl
            ?: ep.imageUrl
            ?: yshAlbumArtworkFor(ep)
        viewModelScope.launch {
            try {
                when {
                    // On-disk file beats every other path.
                    ep.filePath != null -> {
                        DebugLogger.i("RecentVm", "play(${ep.episodeId}) — local")
                        player.playLocal(ep, artwork)
                    }
                    // backup:// row — retention-pruned local copy OR
                    // NasMirror-only entry. Resolve via NasClient and
                    // stream from the bearer-protected /audio endpoint.
                    // (v0.1.68 — was previously hidden from Recent by
                    // the ghost filter; now visible + tappable, so
                    // dispatch must be aware of the backup scheme.)
                    ep.downloadUrl.startsWith("backup://") -> {
                        val audio = nas.audioUrl(ep.episodeId).getOrNull()
                        if (audio == null) {
                            DebugLogger.w(
                                "RecentVm",
                                "play(${ep.episodeId}) — backup:// row but NAS unconfigured/unreachable",
                            )
                            return@launch
                        }
                        DebugLogger.i("RecentVm", "play(${ep.episodeId}) — stream from NAS")
                        player.playStream(ep.episodeId, audio.url, ep.title, artwork)
                    }
                    // Public CDN — oneplace stream URL, no auth.
                    else -> {
                        DebugLogger.i("RecentVm", "play(${ep.episodeId}) — stream from CDN")
                        when (val src = playSourceFor(ep.filePath, ep.downloadUrl)) {
                            is PlaySource.Local -> player.playLocal(ep, artwork)
                            is PlaySource.Stream -> player.playStream(ep.episodeId, src.url, ep.title, artwork)
                        }
                    }
                }
            } catch (t: Throwable) {
                DebugLogger.e("RecentVm", "play(${ep.episodeId}) — dispatch threw", t)
            }
        }
    }

    /**
     * Re-enqueue a download for an episode that has no local file yet
     * (after a manual delete, or for an episode that was scraped but
     * never downloaded). Streaming alone doesn't create a downloaded
     * file, so this is the explicit "save for offline" trigger.
     */
    /**
     * Channel of one-line messages to show in a Snackbar. Drives the
     * "Download queued — will start on WiFi" / "Download started"
     * feedback after a pin tap so the user has visible proof the
     * tap registered, instead of having to read logcat.
     */
    val pinMessages = MutableStateFlow<String?>(null)

    fun consumePinMessage() { pinMessages.value = null }

    fun download(ep: LocalEpisodeEntity) {
        if (ep.filePath != null) return
        DebugLogger.i("RecentVm", "download(${ep.providerId}:${ep.externalId}) — enqueueing")
        // Seed the progress tracker IMMEDIATELY (before the suspend
        // boundary below) so the row shows an indeterminate "queued"
        // bar the instant the user taps pin. The worker overrides the
        // placeholder with real bytes when it starts, and clears the
        // entry on success/failure. Reported via user screenshot
        // 2026-05-13: "pin doesn't show a progress bar on YSH rows."
        downloadProgress.queue(ep.episodeId)
        viewModelScope.launch {
            val s = settings.flow.first()
            val allowMetered = s.allowMeteredDownloads
            // Prefer the NAS over the public CDN when (a) the server
            // has it (archivedAt set) AND (b) NAS is configured.
            // LAN/Tailscale beats internet bandwidth, and the NAS is
            // the canonical archive — CDN copies can rotate off.
            // User ask 2026-05-23: "if recents has a server version
            // always prefer the server to download from."
            // v0.1.73 drops the AIO-only guard — RestoreEpisodeWorker
            // now accepts (providerId, externalId) so YSH restores
            // route through the same path.
            val canRestore = ep.archivedAt != null && s.nasConfigured
            if (canRestore) {
                scheduler.enqueueRestoreByKey(
                    providerId = ep.providerId,
                    externalId = ep.externalId,
                    title = ep.title,
                    airDate = ep.airDate,
                    album = ep.albumName,
                    description = ep.description,
                    durationSecs = ep.durationMs / 1000,
                    allowMetered = allowMetered,
                )
                val onWifi = !isOnMeteredNetwork()
                pinMessages.value = when {
                    onWifi || allowMetered -> "Pulling from backup: ${ep.title}"
                    else -> "Pull queued — will start on WiFi"
                }
                return@launch
            }
            // Fallback: provider-aware CDN download so YSH rows route
            // correctly and AIO rows without a NAS copy still work.
            scheduler.enqueueDownload(ep.providerId, ep.externalId, allowMetered = allowMetered)
            val onWifi = !isOnMeteredNetwork()
            pinMessages.value = when {
                onWifi || allowMetered -> "Download started: ${ep.title}"
                else -> "Download queued — will start on WiFi"
            }
        }
    }

    /**
     * Delete the local copy of an episode. Row falls back to streamable
     * (filePath becomes null in DB); on-disk file is removed.
     */
    fun delete(ep: LocalEpisodeEntity) {
        val path = ep.filePath ?: return
        DebugLogger.i("RecentVm", "delete(${ep.episodeId}) path=$path")
        viewModelScope.launch {
            runCatching { java.io.File(path).delete() }
                .onFailure { DebugLogger.w("RecentVm", "File.delete failed for $path", it) }
            episodes.markUndownloaded(ep.episodeId)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun RecentScreen(
    onNavigateToSettings: () -> Unit = {},
    vm: RecentVm = hiltViewModel(),
) {
    val items by vm.items.collectAsState()
    val resume by vm.resume.collectAsState()
    val resumeEp by vm.resumeEpisode.collectAsState()
    val completedIds by vm.completedIds.collectAsState()
    val positions by vm.positions.collectAsState()
    val showWarning by vm.showMeteredWarning.collectAsState()
    val progress by vm.progress.collectAsState()
    val archive by vm.archive.collectAsState()
    val playerState by vm.playerState.collectAsState()
    val dailyCheck by vm.dailyCheck.collectAsState()
    val isRefreshing = dailyCheck.active
    var expandedIds by remember { mutableStateOf(setOf<Long>()) }

    // Visible confirmation that Refresh fired AND completed. The
    // PullToRefreshBox spinner above shows while the worker is running,
    // but daily-check passes often finish in <1s (especially when no
    // new episodes landed since the last run) — without an explicit
    // "done" snackbar the user sees nothing and assumes the button is
    // broken. RefreshCompleteSnackbarEffect fires on every isRefreshing
    // true→false transition.
    val snackbarHostState = remember { SnackbarHostState() }
    RefreshCompleteSnackbarEffect(
        isRefreshing = isRefreshing,
        newCount = dailyCheck.newCount,
        snackbarHostState = snackbarHostState,
    )
    // Same SnackbarHost surfaces pin-tap feedback — "Download started"
    // or "Download queued — will start on WiFi". RecentVm.pinMessages
    // is a single-shot signal that consumes itself on each emission.
    val pinMessage by vm.pinMessages.collectAsState()
    LaunchedEffect(pinMessage) {
        val msg = pinMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.consumePinMessage()
    }

    if (showWarning) {
        AlertDialog(
            onDismissRequest = vm::dismissWarning,
            modifier = Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("metered-warning"),
            title = { Text("On cellular — downloads blocked") },
            text = {
                Text(
                    "You're on a cellular (LTE) network and downloads on cellular are turned off. " +
                            "Enable them in Settings if you want to download today's episodes now without WiFi.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.dismissWarning(); onNavigateToSettings() },
                    modifier = Modifier.testTag("warning-open-settings"),
                ) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissWarning) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Recent") },
                actions = {
                    ShowSwitcher(onOpenSettings = onNavigateToSettings)
                    // Pull-to-refresh is the primary trigger; this icon
                    // is the discoverability fallback for users who
                    // don't think to swipe. Both call vm.checkNow().
                    IconButton(
                        onClick = {
                            DebugLogger.i("RecentScreen", "Refresh icon clicked")
                            vm.checkNow()
                        },
                        modifier = Modifier.testTag("check-now"),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Check for new episodes")
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                DebugLogger.i("RecentScreen", "Pull-to-refresh triggered")
                vm.checkNow()
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("recent-pull-to-refresh"),
        ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .testTag("episode-list"),
        ) {
            resume?.let { r ->
                resumeEp?.let { ep ->
                    item {
                        ElevatedCard(
                            onClick = { vm.play(ep) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("continue-listening"),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                overlineContent = { Text("Continue listening") },
                                headlineContent = { Text(ep.title) },
                                supportingContent = {
                                    Text(formatResumeSubtitle(r.positionMs, r.durationMs))
                                },
                            )
                        }
                    }
                }
            }
            val completedSet = completedIds.toSet()
            // Dedup: don't show the resume episode again in the main list —
            // it's already represented in the Continue listening card above.
            val mainList = dedupResume(items, resumeEp?.episodeId) { it.episodeId }
            items(mainList, key = { it.episodeId }) { ep ->
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    EpisodeRow(
                        ep = ep,
                        played = ep.episodeId in completedSet,
                        expanded = ep.episodeId in expandedIds,
                        downloadProgress = progress[ep.episodeId],
                        archiveProgress = archive[ep.episodeId],
                        match = vm.catalog.match(ep.title),
                        playback = positions[ep.episodeId],
                        isCurrentlyPlaying = playerState.currentEpisodeId == ep.episodeId &&
                                playerState.isPlaying,
                        fallbackArtwork = vm.yshAlbumArtworkFor(ep),
                        onToggleExpand = {
                            expandedIds = if (ep.episodeId in expandedIds) expandedIds - ep.episodeId
                                          else expandedIds + ep.episodeId
                        },
                        onPlay = { vm.play(ep) },
                        onDelete = { vm.delete(ep) },
                        onDownload = { vm.download(ep) },
                    )
                }
            }
        }
        }
    }
}

@Composable
internal fun EpisodeRow(
    ep: LocalEpisodeEntity,
    played: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onPlay: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    downloadProgress: DownloadProgressEntry? = null,
    /** Set while ArchiveEpisodeWorker is streaming this row to backup. */
    archiveProgress: DownloadProgressEntry? = null,
    match: AioMatch? = null,
    playback: PlaybackPositionEntity? = null,
    /**
     * True when THIS episode is the one the player is currently playing
     * (vs paused or playing a different episode). Drives the Play→Pause
     * label/icon flip on the row's primary button so transport feels
     * continuous between row + mini-player + full player.
     */
    isCurrentlyPlaying: Boolean = false,
    /**
     * Last-resort artwork URL when neither the AIO catalog match nor
     * the row's own imageUrl produced one. Used for YSH free-stream
     * rows whose imageUrl is null but whose catalog album has a cover;
     * the caller resolves this via `vm.yshAlbumArtworkFor(ep)`.
     */
    fallbackArtwork: String? = null,
) {
    // Catalog enrichment overrides oneplace's data when we have a match:
    //   - Title becomes the canonical "#NNN: Title" (e.g. "#657: Clutter")
    //   - Thumbnail comes from the per-episode catalog art (real episode-
    //     specific image), not the generic show logo.
    // Falls back to the unenriched values when no match, then to the
    // YSH-catalog fallback when the row's own imageUrl is null.
    val displayTitle = match?.displayName ?: ep.title
    val thumbnailUrl = match?.thumbnailUrl ?: ep.imageUrl ?: fallbackArtwork
    // "Lighter gray haze" for streamable-only rows so downloaded vs
    // not-downloaded reads at a glance, the same pattern used on the
    // Albums tab for empty albums. Click + tap-to-expand still work
    // through the alpha — only the visual rendering is dimmed.
    val rowAlpha = if (ep.filePath == null) 0.5f else 1f
    Column(modifier = Modifier.alpha(rowAlpha)) {
        ListItem(
            modifier = Modifier
                .clickable(onClick = onToggleExpand)
                .testTag(if (ep.filePath != null) "episode-row-playable" else "episode-row-streamable"),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .testTag("episode-row-thumbnail"),
                )
            },
            // Headline shows the canonical "#NNN: Title" when the AIO
            // catalog match exists; otherwise falls back to the bare
            // oneplace title.
            headlineContent = { Text(displayTitle) },
            supportingContent = {
                Column {
                    Text(
                        text = ep.airDate.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    // Show the truncated description only when collapsed —
                    // the expanded view shows the full description below,
                    // and showing both makes the same text appear twice.
                    if (!expanded) {
                        ep.description?.takeIf { it.isNotBlank() }?.let { desc ->
                            Text(
                                text = desc,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("episode-row-description-collapsed"),
                            )
                        }
                    }
                }
            },
            trailingContent = {
                // Two independent dimensions, same shape as the Album
                // view (user ask 2026-05-23): show "✓ on phone" and/or
                // "☁ on backup" as separate chips so the user can see
                // at a glance what they have where. A row can be both,
                // either, or neither.
                //
                // The first slot stays as it was — a length/progress/
                // played cue with priority order:
                //   in-flight download → "NN%"
                //   in-flight upload   → "↑NN%"
                //   played             → "✓ played"
                //   partially played   → "X min left"
                //   not started        → total length ("25 min")
                val remaining = playback?.let {
                    formatRemaining(it.positionMs, it.durationMs)
                }
                val totalLen = formatTotalDuration(ep.durationMs)
                Column(horizontalAlignment = Alignment.End) {
                    when {
                        downloadProgress != null -> Text(
                            text = "${downloadProgress.percent}%",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("episode-row-progress-pct"),
                        )
                        archiveProgress != null -> Text(
                            text = "↑${archiveProgress.percent}%",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("episode-row-archive-pct"),
                        )
                        played -> Text(
                            "✓ played",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("episode-row-played"),
                        )
                        remaining != null -> Text(
                            text = remaining,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("episode-row-remaining"),
                        )
                        totalLen != null -> Text(
                            text = totalLen,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("episode-row-duration"),
                        )
                    }
                    if (ep.filePath != null) {
                        Text(
                            "✓ on phone",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("episode-row-on-phone"),
                        )
                    }
                    if (ep.archivedAt != null) {
                        Text(
                            "☁ on backup",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("episode-row-on-backup"),
                        )
                    }
                }
            },
        )
        // In-flight download progress bar — full-width line under the row
        // so users see real-time download status. Indeterminate when total
        // bytes are unknown (server didn't send Content-Length).
        if (downloadProgress != null) {
            if (downloadProgress.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { downloadProgress.percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("episode-row-progress-bar"),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("episode-row-progress-bar"),
                )
            }
        }
        if (expanded) {
            // "Are you sure?" gate for delete — accidental taps on the
            // trash icon would otherwise destroy the local mp3.
            var confirmDelete by remember { mutableStateOf(false) }

            Column(
                modifier = Modifier
                    .testTag("episode-row-expanded")
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // Trash icon at the top-left of the expanded panel —
                // separated from the primary action area so the user
                // doesn't blow it away while reaching for Play.
                if (ep.filePath != null && onDelete != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.testTag("episode-row-delete-button"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete download",
                            )
                        }
                    }
                }
                Text(
                    text = ep.description ?: "No description available.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("episode-row-description"),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Same button doubles as Play/Pause — when this row's
                    // episode is the active one, the icon flips to a
                    // pause glyph and the click pauses instead of issuing
                    // a redundant playLocal/playStream.
                    Button(
                        onClick = onPlay,
                        modifier = Modifier.testTag(
                            if (isCurrentlyPlaying) "episode-row-pause-button"
                            else "episode-row-play-button",
                        ),
                    ) {
                        Icon(
                            imageVector = if (isCurrentlyPlaying) Icons.Default.Pause
                                          else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (isCurrentlyPlaying) "Pause" else "Play")
                    }
                    // Download offered only on streamable rows (no local
                    // file yet) AND with a download handler wired. Lets
                    // users re-download an episode they previously deleted,
                    // or pin a streamable row for offline.
                    if (ep.filePath == null && onDownload != null && downloadProgress == null) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onDownload,
                            modifier = Modifier.testTag("episode-row-download-button"),
                        ) {
                            Text("Download for offline")
                        }
                    }
                }
            }

            if (confirmDelete && onDelete != null) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Delete download?") },
                    text = {
                        Text(
                            "“${match?.displayName ?: ep.title}” will be removed from " +
                                "the phone. You can re-download it later from the row.",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmDelete = false
                                onDelete()
                            },
                            modifier = Modifier.testTag("episode-row-delete-confirm"),
                        ) { Text("Delete") }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { confirmDelete = false },
                            modifier = Modifier.testTag("episode-row-delete-cancel"),
                        ) { Text("Cancel") }
                    },
                    modifier = Modifier.testTag("episode-row-delete-dialog"),
                )
            }
        }
    }
}

/**
 * Snackbar effect that fires "Refresh complete — N new episodes"
 * every time `isRefreshing` transitions true → false. Uses the
 * worker-published `newCount` (carried in WorkInfo.outputData and
 * surfaced alongside `active` in the same DailyCheckSnapshot
 * emission) rather than diffing `items.size` from start to end —
 * the old delta approach lost a race between Room's Flow emission
 * and WorkManager's state transition, causing the snackbar to claim
 * "no new episodes" when 3 had actually landed.
 *
 * Visible for tests so plural/empty cases can be locked without a
 * Compose harness.
 */
@Composable
internal fun RefreshCompleteSnackbarEffect(
    isRefreshing: Boolean,
    newCount: Int,
    snackbarHostState: SnackbarHostState,
) {
    val sawRefreshing = remember { mutableStateOf(false) }
    LaunchedEffect(isRefreshing) {
        DebugLogger.i(
            "RefreshSnackbarEffect",
            "LaunchedEffect fired — isRefreshing=$isRefreshing sawRefreshing=${sawRefreshing.value} newCount=$newCount",
        )
        if (isRefreshing) {
            sawRefreshing.value = true
        } else if (sawRefreshing.value) {
            sawRefreshing.value = false
            val msg = refreshCompleteMessage(newCount)
            DebugLogger.i("RefreshSnackbarEffect", "showing snackbar: \"$msg\"")
            snackbarHostState.showSnackbar(msg)
        }
    }
}

/**
 * Pure helper: format the "Refresh complete" snackbar text from the
 * worker-published count of newly ingested rows. Visible for tests
 * so plural/empty cases are locked without a Compose harness.
 */
internal fun refreshCompleteMessage(newCount: Int): String =
    when (newCount.coerceAtLeast(0)) {
        0 -> "Refresh complete — no new episodes"
        1 -> "Refresh complete — 1 new episode"
        else -> "Refresh complete — $newCount new episodes"
    }
