package com.odyssey.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
    private val albumResolver: com.odyssey.ui.AlbumNavResolver,
    private val queuePrimer: com.odyssey.player.AlbumQueuePrimer,
    private val dispatcher: com.odyssey.player.EpisodePlayDispatcher,
    private val acquirer: com.odyssey.download.EpisodeAcquirer,
) : ViewModel() {

    /**
     * Album an episode belongs to, for the row's "Go to album" button.
     * Null when none resolves — the row then hides the affordance.
     */
    fun albumTargetFor(ep: LocalEpisodeEntity): com.odyssey.ui.AlbumNavTarget? =
        albumResolver.targetFor(ep)

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

    /**
     * When the user last opened the Aired view for the active show.
     *
     * Read ONCE per show and immediately advanced, so the "New" badges
     * stay put while you look at the list instead of clearing out from
     * under you on the next recomposition. Re-snapshots when the show
     * switcher changes, since the marker is per provider.
     *
     * Writing inside the flow is a side effect, which is not lovely —
     * but the alternative (advance it from the UI) would fire on every
     * recomposition of the view.
     */
    val airedSeenAt: StateFlow<Long> = settings.activeShow
        .mapLatest { show ->
            val previous = settings.airedSeenAtFor(show).first()
            settings.setAiredSeenAt(show, System.currentTimeMillis())
            previous
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
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
        playback.observeCompleted()
            .map { rows -> rows.map { it.episodeId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<Long>())

    /** episodeId → saved playback position. Drives the "X min left" chip. */
    val positions = playback.observeAllPositions()
        .map { list -> list.associateBy { it.episodeId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // Pair the most-recent playback position with its episode entity so
    // "Continue listening" can show the real title and dispatch to play().
    // Resolved against the UNFILTERED episode list on purpose.
    //
    // This used to resolve against `items`, which is filtered to the
    // active show — so if the last thing you played was from the other
    // show, `resume` was non-null but `resumeEpisode` came back null and
    // the Continue-listening card silently vanished (the call site
    // nests `resume?.let { resumeEp?.let { ... } }`, so a null episode
    // renders nothing at all, with no fallback). The user then had no
    // way back to what they were listening to except remembering the
    // album and navigating to it — reported 2026-09-28.
    //
    // "The single most recent thing you played" should always be one tap
    // away regardless of which show the dropdown is on. The per-show
    // scoping the user asked for on 2026-06-14 was about the recently-
    // played STRIP mixing shows, which stays scoped below.
    //
    // Still null when the position is orphaned (episode row hard-deleted
    // by the no-NAS retention path while its playback_positions row
    // survived). That case is handled by the empty state rather than by
    // deleting the row here — `episodes.observeAll()` starts as an empty
    // list, so cleaning up on a null lookup would wipe real history on
    // the first frame.
    val resumeEpisode = combine(episodes.observeAll(), resume) { eps, r ->
        if (r == null) null else eps.firstOrNull { it.episodeId == r.episodeId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Inline "Recently played" strip for the Recent screen. Filtered to
     * the currently-active show — flipping the dropdown to YSH shouldn't
     * surface AIO plays here (user report 2026-06-14: "ysh is mixed with
     * aio"). The strip is meant as a quick way back into recent listens
     * inside the show you're currently browsing, NOT a cross-provider
     * history view.
     *
     * Capped at [MAX_RECENTLY_PLAYED] entries; the strip pulls one extra
     * row from the DB so excluding the Continue-listening row still
     * leaves the target count visible.
     */
    val recentlyPlayed = combine(
        episodes.observeAll(),
        settings.activeShow.flatMapLatest { active ->
            // Scope the query to the active show so the LIMIT counts only
            // usable rows. Previously this pulled the newest N positions
            // across ALL providers and filtered afterwards, so a handful
            // of recent plays in the other show could push every row of
            // the active show out of the window and render the strip
            // empty despite plenty of history.
            playback.observeRecentlyPlayedFor(active, MAX_RECENTLY_PLAYED + 1)
        },
        resume,
        settings.activeShow,
    ) { eps, plays, resumePos, active ->
        recentlyPlayedFor(
            episodes = eps.filter { it.providerId == active },
            positions = plays,
            excludeEpisodeId = resumePos?.episodeId,
            maxItems = MAX_RECENTLY_PLAYED,
            episodeId = LocalEpisodeEntity::episodeId,
            positionEpisodeId = PlaybackPositionEntity::episodeId,
            updatedAt = PlaybackPositionEntity::updatedAt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Full play history for the active show, newest first and uncapped —
     * backs the Recent History screen ("See all" from the strip above).
     *
     * Scoped to the active show, same as [recentlyPlayed]: Steven's call
     * on 2026-09-29, consistent with the 2026-06-14 "ysh is mixed with
     * aio" report. The provider filter is pushed into SQL via
     * observePlayHistoryFor so it isn't a filter-after-limit trap the
     * way the strip's was — though with no LIMIT here it's correctness
     * of scope rather than of windowing.
     *
     * Pairs each episode with its position so the screen can render the
     * "N min left" / "played" chip and the day grouping without a second
     * lookup. Positions with no surviving episode row (retention
     * hard-deleted it) drop out.
     */
    val playHistory = combine(
        episodes.observeAll(),
        settings.activeShow.flatMapLatest { active ->
            playback.observePlayHistoryFor(active)
        },
        settings.activeShow,
    ) { eps, plays, active ->
        val index = eps.filter { it.providerId == active }.associateBy { it.episodeId }
        plays.mapNotNull { pos -> index[pos.episodeId]?.let { ep -> ep to pos } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        // → pause instead of re-issuing playLocal/playStream. Pause-in-
        // place is per-surface UI policy, so it stays here rather than
        // moving into the dispatcher.
        val s = player.state.value
        if (s.currentEpisodeId == ep.episodeId && s.isPlaying) {
            DebugLogger.i("RecentVm", "play(${ep.episodeId}) — pausing in-place")
            viewModelScope.launch { runCatching { player.pause() } }
            return
        }
        // Everything else — on-disk / backup:// via NAS / CDN, plus
        // album-queue priming — lives in EpisodePlayDispatcher so
        // NowPlayingScreen can start an episode the same way.
        viewModelScope.launch { dispatcher.play(ep, tag = "RecentVm") }
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
        // All of this used to be inlined here, and it was the only
        // correct copy in the app — see EpisodeAcquirer's header for
        // what the other surfaces did instead.
        acquirer.acquire(viewModelScope, ep, tag = "RecentVm") { pinMessages.value = it }
    }

    /**
     * Delete the local copy of an episode. Row falls back to streamable
     * (filePath becomes null in DB); on-disk file is removed.
     */
    companion object {
        /**
         * How many entries the inline "Recently played" strip on the
         * Recent screen shows. Five fits one screen on a typical phone
         * without burying the main daily-check list; raise this if the
         * design moves to a dedicated screen later.
         */
        const val MAX_RECENTLY_PLAYED = 5
    }

    fun delete(ep: LocalEpisodeEntity) {
        val path = ep.filePath ?: return
        DebugLogger.i("RecentVm", "delete(${ep.episodeId}) path=$path")
        viewModelScope.launch {
            runCatching { java.io.File(path).delete() }
                .onFailure { DebugLogger.w("RecentVm", "File.delete failed for $path", it) }
            episodes.markUndownloadedByKey(ep.providerId, ep.externalId)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun RecentScreen(
    onNavigateToSettings: () -> Unit = {},
    onOpenAlbum: (com.odyssey.ui.AlbumNavTarget) -> Unit = {},
    onOpenHistory: () -> Unit = {},
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
    val history by vm.playHistory.collectAsState()
    // Survives rotation and process death so flipping to Played and
    // backgrounding the app does not silently reset the view.
    var showPlayed by rememberSaveable { mutableStateOf(false) }

    // Sampled once per list render rather than per row, so every
    // relative time and bucket boundary in one frame agrees.
    val now = remember(items, history) { System.currentTimeMillis() }
    // Passed explicitly rather than defaulted inside the bucketing
    // helpers: a ZoneId.systemDefault() default is invisible at the call
    // site and untestable (this dev box is UTC, the user is Eastern).
    val zone = remember { java.time.ZoneId.systemDefault() }
    val airedGroups = remember(items, now, zone) {
        groupByAiredBucket(
            items,
            airDateMs = { parseAirDateMillis(it.airDate) },
            nowMs = now,
            zone = zone,
        )
    }
    val airedSeenAt by vm.airedSeenAt.collectAsState()
    val playedGroups = remember(history, now, zone) {
        groupPlaysByDay(history, playedAt = { it.second.updatedAt }, nowMs = now, zone = zone)
    }

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
            // Aired / Played toggle.
            //
            // The tab used to stack a Continue-listening card, a
            // "Recently played" strip and then an air-date list, which
            // made the whole thing read as history: "recent is just a
            // history of what I've played but I can't see the recently
            // aired episodes" (2026-11-14). Two explicit views fix that
            // without a sixth bottom tab, which the nav bar has no room
            // for.
            //
            // The Played view REPLACES the old strip rather than sitting
            // beside it — it is the same list the standalone Recent
            // History screen renders (shared playHistorySection), with
            // no row cap, so keeping both would be a third rendering of
            // one dataset.
            item(key = "recent-view-toggle") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !showPlayed,
                        onClick = { showPlayed = false },
                        label = { Text("Aired") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("recent-view-aired"),
                    )
                    FilterChip(
                        selected = showPlayed,
                        onClick = { showPlayed = true },
                        label = { Text("Played") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("recent-view-played"),
                    )
                }
            }

            val completedSet = completedIds.toSet()

            if (showPlayed) {
                if (playedGroups.isEmpty()) {
                    item(key = "played-empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 48.dp)
                                .testTag("recent-played-empty"),
                        ) {
                            Text("Nothing played yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Episodes you play in this show show up here, " +
                                    "newest first, so you can pick up where you left off.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                playHistorySection(
                    grouped = playedGroups,
                    completed = completedSet,
                    nowMs = now,
                    onPlay = { vm.play(it) },
                )
            } else {
                // Deliberately NOT deduped against the Continue-listening
                // card, unlike the old main list: hiding the newest
                // episode from an air-date view because you happened to
                // start playing it is exactly the confusion this view
                // exists to remove.
                if (airedGroups.isEmpty()) {
                    item(key = "aired-empty") {
                        RecentEmptyState(
                            isRefreshing = isRefreshing,
                            hasOrphanedResume = resume != null,
                        )
                    }
                }
                for ((bucket, rows) in airedGroups) {
                    item(key = "aired-hdr-${bucket.name}") {
                        Text(
                            text = bucket.label,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("aired-header-${bucket.name}"),
                        )
                    }
                    items(rows, key = { it.episodeId }) { ep ->
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
                                albumNavName = vm.albumTargetFor(ep)?.albumName,
                                onGoToAlbum = vm.albumTargetFor(ep)?.let { t -> { onOpenAlbum(t) } },
                                onToggleExpand = {
                                    expandedIds = if (ep.episodeId in expandedIds) {
                                        expandedIds - ep.episodeId
                                    } else {
                                        expandedIds + ep.episodeId
                                    }
                                },
                                onPlay = { vm.play(ep) },
                                onDelete = { vm.delete(ep) },
                                onDownload = { vm.download(ep) },
                                isNew = isNewlyAired(
                                    airDateMs = parseAirDateMillis(ep.airDate),
                                    seenAtMs = airedSeenAt,
                                ),
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
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
    /**
     * Album name this episode belongs to, resolved by the caller via
     * `AlbumNavResolver`. When non-null (together with [onGoToAlbum]),
     * the expanded panel shows a "Go to album" button. Null hides it —
     * e.g. an AIO oneplace title with no catalog match.
     */
    albumNavName: String? = null,
    onGoToAlbum: (() -> Unit)? = null,
    /**
     * Aired since the user last opened the Aired view. Drives a "New"
     * badge beside the title. Keyed on air date rather than an ingest
     * timestamp because the episode table has no first-seen column —
     * see isNewlyAired.
     */
    isNew: Boolean = false,
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
            headlineContent = {
                if (isNew) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(displayTitle, modifier = Modifier.weight(1f, fill = false))
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.testTag("aired-new-badge"),
                        ) {
                            Text(
                                text = "New",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                            )
                        }
                    }
                } else {
                    Text(displayTitle)
                }
            },
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

            // v0.1.77: when a row near the bottom of the list is
            // expanded, the new panel renders below the viewport and
            // the user has to manually scroll to see the description /
            // Play button. BringIntoViewRequester walks the parent
            // chain (LazyColumn is the nearest scrollable container)
            // and tells it to bring the bounds of this Column fully
            // into view. Fires once per expand transition.
            val bringIntoView = remember { BringIntoViewRequester() }
            LaunchedEffect(expanded) {
                if (expanded) {
                    runCatching { bringIntoView.bringIntoView() }
                        .onFailure {
                            // Layout may not have measured yet; not fatal.
                            DebugLogger.d("EpisodeRow", "bringIntoView declined: ${it.message}")
                        }
                }
            }

            Column(
                modifier = Modifier
                    .testTag("episode-row-expanded")
                    .bringIntoViewRequester(bringIntoView)
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
                // Jump to this episode's album. Shown only when the
                // caller resolved an album for the row.
                if (albumNavName != null && onGoToAlbum != null) {
                    TextButton(
                        onClick = onGoToAlbum,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        modifier = Modifier.testTag("episode-row-go-to-album"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Album,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Go to album: $albumNavName", maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/**
 * Shown when the Recent tab has nothing to display at all. Distinguishes
 * three cases so the message is actionable rather than a shrug:
 *
 *  - a refresh is in flight  → say so, don't imply emptiness is final
 *  - a saved position exists but its episode row is gone (orphan, e.g.
 *    the no-NAS retention path hard-deleted the row while the
 *    playback_positions row survived) → explain, since "you were
 *    definitely listening to something" is the user's mental model
 *  - genuinely nothing yet   → point at Refresh
 */
@Composable
fun RecentEmptyState(
    isRefreshing: Boolean,
    hasOrphanedResume: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp)
            .testTag("recent-empty-state"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when {
                isRefreshing -> "Checking for new episodes…"
                hasOrphanedResume -> "Nothing to show yet"
                else -> "No episodes yet"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                isRefreshing -> "Hang tight — this usually takes a second."
                hasOrphanedResume ->
                    "The episode you were last listening to is no longer on this " +
                        "phone. Pull down to refresh, or browse the Albums tab to " +
                        "find it again."
                else ->
                    "Pull down to refresh, or tap Refresh above to check for " +
                        "today's episode. Anything you've downloaded also shows " +
                        "up on the Downloaded tab."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
