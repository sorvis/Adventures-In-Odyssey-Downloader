package com.odyssey.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.YshUnmatchedDao
import com.odyssey.debug.DebugLogger
import com.odyssey.download.ArchiveProgressTracker
import com.odyssey.qr.decodeServerQr
import com.odyssey.qr.encodeServerQr
import com.odyssey.qr.renderServerQrBitmap
import com.odyssey.show.ProviderRegistry
import com.odyssey.show.ShowProvider
import com.odyssey.work.ArchiveBackfill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsVm @Inject constructor(
    private val settings: SettingsRepo,
    private val episodes: EpisodeDao,
    private val backfill: ArchiveBackfill,
    archiveProgress: ArchiveProgressTracker,
    /** All registered ShowProviders, deduplicated by id. */
    val providerRegistry: ProviderRegistry,
    yshUnmatched: YshUnmatchedDao,
) : ViewModel() {
    /** Count of YSH titles that didn't match the catalog. Drives a
     *  "Review unmatched (N)" entry on the Shows card so misses are
     *  visible without opening Debug logs. */
    val yshUnmatchedCount = yshUnmatched.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val state = settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Provider ids the daily-check worker is allowed to ingest from. */
    val enabledProviders = settings.enabledProviders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), setOf("aio"))

    /** Currently-active show. Drives the radio selection in the Shows card. */
    val activeShow = settings.activeShow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "aio")

    /** All registered providers (one entry per show id). */
    val allProviders: List<ShowProvider> get() = providerRegistry.all

    fun setProviderEnabled(providerId: String, enabled: Boolean) = viewModelScope.launch {
        settings.setProviderEnabled(providerId, enabled)
        // If the user just disabled the show they were viewing, slide
        // them back to a still-enabled one. Prefers AIO; if AIO is
        // also disabled, picks whichever id remains. If nothing is
        // enabled, leave the activeShow stale — no useful target.
        if (!enabled && activeShow.value == providerId) {
            val stillEnabled = settings.enabledProviders.first()
            val fallback = stillEnabled.firstOrNull { it == "aio" } ?: stillEnabled.firstOrNull()
            if (fallback != null) settings.setActiveShow(fallback)
        }
    }

    fun setActiveShow(providerId: String) = viewModelScope.launch {
        settings.setActiveShow(providerId)
    }

    /**
     * Live count of "downloaded but not yet pushed to backup". Drives
     * the "Push N waiting" button + status line on Settings → Backup.
     */
    val unarchivedCount = episodes.observeUnarchivedDownloaded()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * How many archive jobs are streaming bytes RIGHT NOW. Lets the
     * Settings screen show a live "(N uploading)" pulse so the user
     * sees progress between taps even before the unarchived count
     * decrements (which only happens on completion).
     */
    val uploadingCount = archiveProgress.progress
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * UI feedback for the most recent backfill run — null = not yet run,
     * 0 = ran with nothing pending, >0 = enqueued that many uploads.
     */
    val lastBackfillEnqueued: MutableStateFlow<Int?> = MutableStateFlow(null)

    fun saveNas(
        url: String,
        token: String,
        cfClientId: String = "",
        cfClientSecret: String = "",
    ) = viewModelScope.launch {
        settings.setNas(url, token)
        // Persist (or clear) the Cloudflare Access service tokens
        // alongside the bearer so a save covers all four credentials
        // in one tap — no chance of "I saved the URL but forgot the
        // CF headers and now nothing connects".
        settings.setCloudflareAccess(cfClientId, cfClientSecret)
        // Auto-trigger the backfill when valid creds are saved — first
        // connect to a backup server should push everything that's
        // already on-device. ArchiveBackfill is idempotent, so even if
        // some rows are mid-archive this is safe to re-run.
        if (url.isNotBlank() && token.isNotBlank()) {
            runCatching { lastBackfillEnqueued.value = backfill.run() }
                .onFailure { DebugLogger.e("SettingsVm", "saveNas backfill failed", it) }
        }
    }

    /** User-tapped "Push N waiting" button. Same code path as auto-trigger. */
    fun pushUnarchivedNow() = viewModelScope.launch {
        runCatching { lastBackfillEnqueued.value = backfill.run() }
            .onFailure { DebugLogger.e("SettingsVm", "pushUnarchivedNow failed", it) }
    }

    /**
     * Null out archivedAt on every downloaded row, then re-run the
     * backfill so the server gets all uploads again with current
     * metadata (album, etc.). Used after a server-side or phone-side
     * fix that changes how uploads get filed — e.g. phone now sends
     * the album name from its catalog, but already-archived rows
     * never get re-pushed without this nudge.
     */
    fun reArchiveAll() = viewModelScope.launch {
        runCatching {
            val cleared = episodes.clearAllArchived()
            DebugLogger.i("SettingsVm", "reArchiveAll cleared $cleared rows; firing backfill")
            lastBackfillEnqueued.value = backfill.run()
        }.onFailure { DebugLogger.e("SettingsVm", "reArchiveAll failed", it) }
    }

    fun saveRetention(n: Int) = viewModelScope.launch { settings.setRetention(n) }

    /**
     * Per-provider retention cap. The Settings screen renders one input
     * per registered provider so AIO and YSH can have independent ring
     * sizes — previously they shared the legacy `retention_count` and
     * YSH downloads squeezed the AIO slot to nothing.
     */
    fun saveRetentionFor(providerId: String, n: Int) =
        viewModelScope.launch { settings.setRetentionFor(providerId, n) }

    /**
     * StateFlow of the per-provider cap, suitable for binding the
     * text-field initial value. Default seeded with `DEFAULT_RETENTION`
     * so the field never blinks empty on first composition.
     */
    fun retentionCountFor(providerId: String) =
        settings.retentionCountFor(providerId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.odyssey.app.DEFAULT_RETENTION)

    fun setAllowMetered(allow: Boolean) = viewModelScope.launch { settings.setAllowMeteredDownloads(allow) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    onOpenDebug: () -> Unit = {},
    onOpenTransfers: () -> Unit = {},
    onOpenYshUnmatched: () -> Unit = {},
    vm: SettingsVm = hiltViewModel(),
) {
    val s by vm.state.collectAsState()
    val current = s ?: return
    val unarchivedCount by vm.unarchivedCount.collectAsState()
    val uploadingCount by vm.uploadingCount.collectAsState()
    val lastBackfill by vm.lastBackfillEnqueued.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Surface the backfill result as a Snackbar so the user gets a
    // visible "yes, I heard you" pulse the moment Push fires —
    // previous quiet text label below the button was easy to miss.
    LaunchedEffect(lastBackfill) {
        val n = lastBackfill ?: return@LaunchedEffect
        val msg = when {
            n == 0 -> "Nothing waiting — all episodes are already backed up."
            n == 1 -> "Queued 1 upload. Watch progress on the Backup tab."
            else -> "Queued $n uploads. Watch progress on the Backup tab."
        }
        snackbarHostState.showSnackbar(msg)
    }

    val ctx = LocalContext.current
    val versionLabel = remember {
        // PackageManager round-trip — works without enabling AGP's
        // buildConfig feature, and surfaces both the user-facing
        // versionName ("0.1.7") and the monotonic versionCode (7).
        runCatching {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${info.versionName} (build ${info.longVersionCode})"
        }.getOrDefault("unknown")
    }

    var nasUrl by remember(current.nasUrl) { mutableStateOf(current.nasUrl) }
    var nasToken by remember(current.nasToken) { mutableStateOf(current.nasToken) }
    var cfClientId by remember(current.cfAccessClientId) { mutableStateOf(current.cfAccessClientId) }
    var cfClientSecret by remember(current.cfAccessClientSecret) { mutableStateOf(current.cfAccessClientSecret) }
    var showQrDialog by remember { mutableStateOf(false) }
    // Reveal the Cloudflare Access pair only when the user has
    // already pasted at least one of them, OR they expand the
    // optional section. Default-collapsed keeps the screen tidy
    // for the LAN/Tailscale-only majority case.
    var showCfFields by remember(current.cfAccessConfigured) {
        mutableStateOf(current.cfAccessConfigured)
    }
    val scope = rememberCoroutineScope()

    // ScanContract takes us into ZXing's CaptureActivity (no Play
    // Services), then returns the decoded string here. Camera
    // permission is requested by the embedded activity itself —
    // we just react to the result string.
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (raw == null) {
            // User cancelled — silent, that's fine.
            return@rememberLauncherForActivityResult
        }
        val decoded = decodeServerQr(raw)
        if (decoded == null) {
            scope.launch {
                snackbarHostState.showSnackbar("That QR isn't a backup-server share.")
            }
            return@rememberLauncherForActivityResult
        }
        nasUrl = decoded.url
        nasToken = decoded.token
        cfClientId = decoded.cfClientId
        cfClientSecret = decoded.cfClientSecret
        if (decoded.cfClientId.isNotBlank()) showCfFields = true
        scope.launch {
            snackbarHostState.showSnackbar(
                if (decoded.cfClientId.isNotBlank())
                    "Scanned (incl. Cloudflare Access tokens). Tap Save to apply."
                else "Scanned. Tap Save to apply.",
            )
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())  // anything past the fold was clipped
                .padding(16.dp)
                .semantics { testTagsAsResourceId = true },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShowsCard(
                providers = vm.allProviders,
                enabledIds = vm.enabledProviders.collectAsState().value,
                activeId = vm.activeShow.collectAsState().value,
                onToggle = vm::setProviderEnabled,
                onPickActive = vm::setActiveShow,
                yshUnmatchedCount = vm.yshUnmatchedCount.collectAsState().value,
                onOpenYshUnmatched = onOpenYshUnmatched,
            )

            Text("NAS archive (optional)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Leave blank to run standalone — daily downloads still work without a NAS.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = nasUrl, onValueChange = { nasUrl = it },
                label = { Text("URL e.g. http://archive.lan:8088") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = nasToken, onValueChange = { nasToken = it },
                label = { Text("Bearer token") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            // Cloudflare Access service-token pair. Only populated when
            // the server is fronted by a Cloudflare Tunnel + Access app
            // — friends who connect over the public hostname need both
            // headers on every request. Empty for the LAN / Tailscale
            // case (the majority — owner's primary phone). The toggle
            // hides it by default so the panel stays tidy.
            TextButton(
                onClick = { showCfFields = !showCfFields },
                modifier = Modifier.testTag("toggle-cf-access"),
            ) {
                Text(
                    if (showCfFields) "Hide Cloudflare Access (advanced)"
                    else "Add Cloudflare Access (advanced)",
                )
            }
            if (showCfFields) {
                OutlinedTextField(
                    value = cfClientId, onValueChange = { cfClientId = it },
                    label = { Text("CF-Access-Client-Id") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("cf-client-id"),
                )
                OutlinedTextField(
                    value = cfClientSecret, onValueChange = { cfClientSecret = it },
                    label = { Text("CF-Access-Client-Secret") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("cf-client-secret"),
                )
                Text(
                    "Both required when the server is exposed through a Cloudflare " +
                    "Tunnel + Access app. Leave blank for LAN or Tailscale.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = { vm.saveNas(nasUrl, nasToken, cfClientId, cfClientSecret) },
                modifier = Modifier.testTag("save-nas"),
            ) { Text("Save NAS settings") }

            // QR share — generate a code from the current fields, or
            // scan one from another phone to fill the fields. Faster +
            // less error-prone than retyping a 64-char bearer token on
            // a second device. Buttons sit side-by-side; Show is
            // disabled when there's nothing to share.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { showQrDialog = true },
                    enabled = nasUrl.isNotBlank() && nasToken.isNotBlank(),
                    modifier = Modifier.testTag("show-server-qr"),
                ) { Text("Show QR") }
                OutlinedButton(
                    onClick = {
                        val opts = ScanOptions().apply {
                            setPrompt("Point at a backup-server QR")
                            setBeepEnabled(false)
                            setOrientationLocked(false)
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        }
                        scanLauncher.launch(opts)
                    },
                    modifier = Modifier.testTag("scan-server-qr"),
                ) { Text("Scan QR") }
            }
            Text(
                "Share with another phone: tap Show QR here, Scan QR there. " +
                "Both fields fill in automatically; tap Save on the new phone.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (showQrDialog) {
                ServerQrDialog(
                    url = nasUrl,
                    token = nasToken,
                    cfClientId = cfClientId,
                    cfClientSecret = cfClientSecret,
                    onDismiss = { showQrDialog = false },
                )
            }

            // Push-to-backup status: shows how many local files haven't
            // been backed up yet, plus a manual trigger button. Save
            // already kicks an auto-backfill — this is for re-runs.
            if (current.nasConfigured) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(
                            when {
                                unarchivedCount == 0 -> "All downloaded episodes are backed up."
                                else -> "$unarchivedCount episode${if (unarchivedCount == 1) "" else "s"} on this phone not yet backed up."
                            },
                        )
                        // Live pulse: how many archive jobs are streaming
                        // bytes RIGHT NOW. Updates every few hundred ms via
                        // ArchiveProgressTracker, so the user sees the
                        // upload is alive even while the unarchived count
                        // is still ticking down to zero.
                        if (uploadingCount > 0) append(" ($uploadingCount uploading now.)")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("backup-pending-count"),
                )
                Button(
                    onClick = vm::pushUnarchivedNow,
                    enabled = unarchivedCount > 0,
                    modifier = Modifier.testTag("push-unarchived"),
                ) {
                    Text(
                        if (unarchivedCount > 0) "Push $unarchivedCount to backup"
                        else "Nothing to push",
                    )
                }
                lastBackfill?.let { count ->
                    Text(
                        text = if (count == 0) "Last push: nothing was waiting."
                               else "Last push: queued $count upload${if (count == 1) "" else "s"} (run in background).",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("backup-last-result"),
                    )
                }

                // Direct link to the Transfers screen — was previously
                // only reachable from a Backup-tab TopAppBar action,
                // which the user couldn't find when uploads looked
                // stuck. Anchor it to the same panel that shows the
                // unarchived count so the obvious next action ("show
                // me which files") is one tap away.
                TextButton(
                    onClick = onOpenTransfers,
                    modifier = Modifier.testTag("settings-open-transfers"),
                ) {
                    Text(
                        if (uploadingCount > 0)
                            "View transfer activity ($uploadingCount uploading) →"
                        else "View transfer activity →",
                    )
                }

                // "Re-archive everything" — for after a fix that
                // changes how uploads land on the server (e.g. album
                // resolution moved phone-side). Clears archivedAt on
                // every downloaded row so the next backfill re-pushes
                // them with current metadata. Server upload is
                // idempotent on episode_id so re-running is safe.
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = vm::reArchiveAll,
                    modifier = Modifier.testTag("re-archive-all"),
                ) { Text("Re-archive everything (rare)") }
                Text(
                    "Resends every downloaded episode to the backup. " +
                            "Use after a server change that affects how " +
                            "files are filed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text("Downloads", style = MaterialTheme.typography.titleMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Allow downloads on cellular (LTE)")
                    Text(
                        if (current.allowMeteredDownloads)
                            "On — episodes will download on any network, including LTE."
                        else
                            "Off — episodes only download on WiFi.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = current.allowMeteredDownloads,
                    onCheckedChange = vm::setAllowMetered,
                    modifier = Modifier.testTag("allow-metered-toggle"),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text("Retention", style = MaterialTheme.typography.titleMedium)
            Text(
                "Episodes to keep on phone, per show. Pruning happens after " +
                    "each successful archive — older episodes stay accessible on " +
                    "the NAS backup (AIO) or are removed entirely (no NAS / non-AIO).",
                style = MaterialTheme.typography.bodySmall,
            )
            // One input per registered provider so AIO and YSH have
            // independent ring sizes. Pre-v0.1.66 these shared the
            // legacy `retention_count` key and YSH downloads squeezed
            // the AIO slot to nothing.
            for (provider in vm.allProviders) {
                val cap by vm.retentionCountFor(provider.id).collectAsState()
                var text by remember(cap) { mutableStateOf(cap.toString()) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit) },
                    label = { Text("${provider.displayName} — episodes to keep") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("retention-input-${provider.id}"),
                    trailingIcon = {
                        TextButton(
                            onClick = {
                                text.toIntOrNull()?.let { vm.saveRetentionFor(provider.id, it) }
                            },
                            modifier = Modifier.testTag("retention-save-${provider.id}"),
                        ) { Text("Save") }
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text("Last run: ${if (current.lastRunAtMs == 0L) "never" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(current.lastRunAtMs))}")
            Text("Last seen episode: ${if (current.lastSeenEpisodeId == 0L) "—" else current.lastSeenEpisodeId}")

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            OutlinedButton(
                onClick = onOpenDebug,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("open-debug-logs"),
            ) { Text("Open debug logs") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Odyssey $versionLabel",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("app-version"),
            )
        }
    }
}

@Composable
private fun ServerQrDialog(
    url: String,
    token: String,
    cfClientId: String,
    cfClientSecret: String,
    onDismiss: () -> Unit,
) {
    // Render once per (url, token, cf...) — not on every recomposition.
    // Bitmap is expensive enough (matrix encode + IntArray fill) that
    // re-rendering it on dismiss-animation frames is wasteful.
    val payload = remember(url, token, cfClientId, cfClientSecret) {
        encodeServerQr(url, token, cfClientId, cfClientSecret)
    }
    val bitmap = remember(payload) { renderServerQrBitmap(payload, sizePx = 720) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        title = { Text("Share backup-server connection") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Backup server QR code",
                    modifier = Modifier
                        .size(260.dp)
                        .testTag("server-qr-image"),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (cfClientId.isNotBlank())
                        "Point another phone's Scan QR at this code. Includes " +
                        "URL, bearer token, and Cloudflare Access service tokens — " +
                        "the receiving phone is ready after one Save tap."
                    else
                        "Point another phone's Scan QR at this code to copy " +
                        "URL and token over without retyping.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        },
    )
}

/**
 * Per-show enable + active-show picker. Lives at the top of the
 * Settings screen so users can find the YSH toggle without scrolling.
 *
 * Each registered provider gets a row with a Switch on the right; the
 * active show carries a leading check icon. AIO is always shown
 * first (alphabetical fallback after that). Disabling the active
 * show automatically moves the active selection to a still-enabled
 * provider (preferring AIO) — handled in the ViewModel.
 */
@Composable
internal fun ShowsCard(
    providers: List<ShowProvider>,
    enabledIds: Set<String>,
    activeId: String,
    onToggle: (providerId: String, enabled: Boolean) -> Unit,
    onPickActive: (providerId: String) -> Unit,
    yshUnmatchedCount: Int = 0,
    onOpenYshUnmatched: () -> Unit = {},
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("shows-card")) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Shows", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pick which shows the daily check downloads. Tap a row to make it active — " +
                "the active show is what you see when you open the app.",
                style = MaterialTheme.typography.bodySmall,
            )
            providers
                .sortedWith(compareBy({ it.id != "aio" }, { it.displayName }))
                .forEach { p ->
                    val enabled = p.id in enabledIds
                    val isActive = p.id == activeId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("show-row-${p.id}"),
                    ) {
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Plain unicode checkmark avoids pulling in
                                // material-icons-extended just for one
                                // small affordance.
                                if (isActive) {
                                    Text("✓ ", style = MaterialTheme.typography.bodyLarge)
                                }
                                Text(p.displayName, style = MaterialTheme.typography.bodyLarge)
                            }
                            if (enabled && !isActive) {
                                TextButton(
                                    onClick = { onPickActive(p.id) },
                                    modifier = Modifier.testTag("make-active-${p.id}"),
                                ) { Text("Make active") }
                            }
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { onToggle(p.id, it) },
                            modifier = Modifier.testTag("show-toggle-${p.id}"),
                        )
                    }
                }
            // Unmatched-titles surface — only shown when there's
            // something to review. Tapping deep-links to the
            // dedicated review screen.
            if (yshUnmatchedCount > 0) {
                Divider(Modifier.padding(vertical = 4.dp))
                TextButton(
                    onClick = onOpenYshUnmatched,
                    modifier = Modifier.testTag("ysh-unmatched-button"),
                ) {
                    Text("Review unmatched YSH titles ($yshUnmatchedCount)")
                }
            }
        }
    }
}
