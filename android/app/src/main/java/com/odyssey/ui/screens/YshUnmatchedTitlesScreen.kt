package com.odyssey.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.odyssey.data.local.YshUnmatchedDao
import com.odyssey.data.local.YshUnmatchedTitleEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class YshUnmatchedTitlesVm @Inject constructor(
    private val unmatched: YshUnmatchedDao,
) : ViewModel() {
    val rows = unmatched.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun dismiss(oneplaceEpisodeId: Long) = viewModelScope.launch {
        unmatched.delete(oneplaceEpisodeId)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun YshUnmatchedTitlesScreen(
    onBack: () -> Unit = {},
    vm: YshUnmatchedTitlesVm = hiltViewModel(),
) {
    val rows by vm.rows.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Unmatched YSH titles") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("ysh-unmatched-back")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true },
        ) {
            Text(
                "Episodes the daily check pulled from oneplace.com that " +
                    "didn't match any track in the yourstoryhour.org " +
                    "catalog. They were skipped to keep the album view " +
                    "clean. Re-check after a catalog refresh — most " +
                    "misses self-resolve when the catalog adds a track.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
            if (rows.isEmpty()) {
                YshUnmatchedEmptyState(modifier = Modifier.padding(24.dp))
                return@Scaffold
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("ysh-unmatched-list"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.oneplaceEpisodeId }) { row ->
                    YshUnmatchedRow(row, onDismiss = { vm.dismiss(row.oneplaceEpisodeId) })
                }
            }
        }
    }
}

@Composable
internal fun YshUnmatchedRow(
    row: YshUnmatchedTitleEntity,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ysh-unmatched-row-${row.oneplaceEpisodeId}"),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    yshUnmatchedSubtitle(row),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("ysh-unmatched-dismiss-${row.oneplaceEpisodeId}"),
            ) { Text("Dismiss") }
        }
    }
}

@Composable
private fun YshUnmatchedEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.testTag("ysh-unmatched-empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Nothing here — every oneplace YSH episode matched a catalog track.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Pure helper for the row subtitle. Visible for tests so we can lock
 * the date formatting + attempt-count pluralization without rendering
 * Compose.
 */
internal fun yshUnmatchedSubtitle(row: YshUnmatchedTitleEntity): String {
    val fmt = SimpleDateFormat("MMM d, yyyy", Locale.US)
    val seenAt = fmt.format(Date(row.firstSeenAt))
    val attempts = if (row.attemptCount == 1) "1 attempt" else "${row.attemptCount} attempts"
    return "$attempts · first seen $seenAt"
}
