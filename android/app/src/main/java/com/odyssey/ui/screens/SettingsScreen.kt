package com.odyssey.ui.screens

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odyssey.app.SettingsRepo
import com.odyssey.data.local.EpisodeDao
import com.odyssey.debug.DebugLogger
import com.odyssey.work.ArchiveBackfill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsVm @Inject constructor(
    private val settings: SettingsRepo,
    private val episodes: EpisodeDao,
    private val backfill: ArchiveBackfill,
) : ViewModel() {
    val state = settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Live count of "downloaded but not yet pushed to backup". Drives
     * the "Push N waiting" button + status line on Settings → Backup.
     */
    val unarchivedCount = episodes.observeUnarchivedDownloaded()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * UI feedback for the most recent backfill run — null = not yet run,
     * 0 = ran with nothing pending, >0 = enqueued that many uploads.
     */
    val lastBackfillEnqueued: MutableStateFlow<Int?> = MutableStateFlow(null)

    fun saveNas(url: String, token: String) = viewModelScope.launch {
        settings.setNas(url, token)
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

    fun saveRetention(n: Int) = viewModelScope.launch { settings.setRetention(n) }
    fun setAllowMetered(allow: Boolean) = viewModelScope.launch { settings.setAllowMeteredDownloads(allow) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    onOpenDebug: () -> Unit = {},
    vm: SettingsVm = hiltViewModel(),
) {
    val s by vm.state.collectAsState()
    val current = s ?: return
    val unarchivedCount by vm.unarchivedCount.collectAsState()
    val lastBackfill by vm.lastBackfillEnqueued.collectAsState()

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
    var retention by remember(current.retentionCount) { mutableStateOf(current.retentionCount.toString()) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())  // anything past the fold was clipped
                .padding(16.dp)
                .semantics { testTagsAsResourceId = true },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("NAS archive (optional)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Leave blank to run standalone — daily downloads still work without a NAS.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = nasUrl, onValueChange = { nasUrl = it },
                label = { Text("URL e.g. http://192.168.2.50:8088") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = nasToken, onValueChange = { nasToken = it },
                label = { Text("Bearer token") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.saveNas(nasUrl, nasToken) },
                modifier = Modifier.testTag("save-nas"),
            ) { Text("Save NAS settings") }

            // Push-to-backup status: shows how many local files haven't
            // been backed up yet, plus a manual trigger button. Save
            // already kicks an auto-backfill — this is for re-runs.
            if (current.nasConfigured) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        unarchivedCount == 0 -> "All downloaded episodes are backed up."
                        else -> "$unarchivedCount episode${if (unarchivedCount == 1) "" else "s"} on this phone not yet backed up."
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
            OutlinedTextField(
                value = retention, onValueChange = { retention = it.filter(Char::isDigit) },
                label = { Text("Episodes to keep on phone") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { retention.toIntOrNull()?.let(vm::saveRetention) }) {
                Text("Save retention")
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
