package com.odyssey.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odyssey.data.local.EpisodeDao
import com.odyssey.download.ArchiveProgressTracker
import com.odyssey.download.DownloadProgressTracker
import com.odyssey.download.RestoreProgressTracker
import com.odyssey.download.TransferKind
import com.odyssey.download.TransferRow
import com.odyssey.download.TransferState
import com.odyssey.download.mergeTransfers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Live view of every transfer happening right now: downloads from
 * oneplace.com (the daily-check workers) and uploads to the backup
 * service (ArchiveEpisodeWorker). Lifetime-only — entries vanish
 * when their worker finishes/fails (the trackers self-clean).
 *
 * Restores from backup aren't represented here yet because the
 * Backup tab is stream-only today; when "Pin offline copy from
 * backup" lands a third TransferKind slots in cleanly.
 */
@HiltViewModel
class TransfersVm @Inject constructor(
    episodes: EpisodeDao,
    downloads: DownloadProgressTracker,
    uploads: ArchiveProgressTracker,
    restores: RestoreProgressTracker,
) : ViewModel() {
    /**
     * Combine 5 streams: active downloads, active uploads, active
     * restores, all episodes (for titles), unarchived-downloaded
     * rows (QUEUED uploads). Settings → Backup uses the unarchived-
     * downloaded list to compute "N waiting", so wiring the same
     * list keeps the two views consistent — N waiting always equals
     * N rows visible.
     */
    val rows = combine(
        downloads.progress,
        uploads.progress,
        restores.progress,
        episodes.observeAll(),
        episodes.observeUnarchivedDownloaded(),
    ) { dl, up, rs, eps, unarchived ->
        val titles = eps.associate { it.episodeId to it.title }
        val airDates = eps.associate { it.episodeId to it.airDate }
        mergeTransfers(
            downloads = dl,
            uploads = up,
            titlesById = titles,
            queuedUploadIds = unarchived.map { it.episodeId }.toSet(),
            restores = rs,
            airDatesById = airDates,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun TransfersScreen(
    onBack: () -> Unit = {},
    vm: TransfersVm = hiltViewModel(),
) {
    val rows by vm.rows.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfers") },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("transfers-back")) {
                        Text("Back")
                    }
                },
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No active transfers.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(32.dp)
                        .testTag("transfers-empty"),
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .semantics { testTagsAsResourceId = true }
                .testTag("transfers-list"),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows, key = { "${it.kind}-${it.episodeId}" }) { row ->
                TransferRowCard(row)
            }
        }
    }
}

@Composable
internal fun TransferRowCard(row: TransferRow) {
    val tag = buildString {
        append("transfer-row-")
        append(
            when (row.kind) {
                TransferKind.DOWNLOAD -> "download"
                TransferKind.UPLOAD -> "upload"
                TransferKind.RESTORE -> "restore"
            },
        )
        if (row.state == TransferState.QUEUED) append("-queued")
        append('-')
        append(row.episodeId)
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (row.kind) {
                        TransferKind.DOWNLOAD -> "↓ Download"
                        TransferKind.UPLOAD -> "↑ Upload to backup"
                        TransferKind.RESTORE -> "↓ Pull from backup"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = when (row.state) {
                        TransferState.ACTIVE -> "${row.percent}%"
                        TransferState.QUEUED -> "queued"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("transfer-row-pct"),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Air-date subtitle — disambiguates daily shows whose
            // title is just the show name (e.g. multiple "Sekulow"
            // rows would otherwise look like duplicate state).
            if (!row.airDate.isNullOrBlank()) {
                Text(
                    text = row.airDate,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag("transfer-row-air-date"),
                )
            }
            Spacer(Modifier.height(6.dp))
            // ACTIVE rows show a real progress bar (or indeterminate
            // when total bytes unknown). QUEUED rows show no bar —
            // nothing's flowing yet, so a moving indicator would
            // misrepresent state.
            if (row.state == TransferState.ACTIVE) {
                if (row.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { row.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
