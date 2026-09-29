package com.odyssey.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.odyssey.player.formatResumeSubtitle

/**
 * Full play history for the active show — everything you've played,
 * newest first, uncapped. Reached via "See all" on the Recent tab's
 * recently-played strip, which is capped at
 * [RecentVm.Companion.MAX_RECENTLY_PLAYED].
 *
 * Scoped to the selected show on purpose (Steven, 2026-09-29): flipping
 * the show switcher changes what this lists, matching the strip it
 * expands rather than becoming a cross-provider view.
 *
 * Tapping a row resumes that episode at its saved position, which is
 * the whole point — after a full app close the alternative was
 * remembering the album and navigating to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentHistoryScreen(
    onBack: () -> Unit = {},
    vm: RecentVm = hiltViewModel(),
) {
    val history by vm.playHistory.collectAsState()
    val completedIds by vm.completedIds.collectAsState()
    val completed = remember(completedIds) { completedIds.toSet() }

    // Sampled once per recomposition of the list rather than per row, so
    // every "2h ago" in one render agrees with the others.
    val now = remember(history) { System.currentTimeMillis() }
    val grouped = remember(history, now) {
        groupPlaysByDay(history, playedAt = { it.second.updatedAt }, nowMs = now)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recent history") },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("history-back")) {
                        Text("Back")
                    }
                },
            )
        },
    ) { padding ->
        if (grouped.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp, vertical = 48.dp)
                    .testTag("history-empty-state"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Nothing played yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Episodes you play in this show will show up here, " +
                        "newest first, so you can pick up where you left off.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("history-list"),
        ) {
            for ((bucket, rows) in grouped) {
                item(key = "hdr-${bucket.name}") {
                    Text(
                        text = bucket.label,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(rows, key = { "h-${it.first.providerId}-${it.first.externalId}" }) { (ep, pos) ->
                    val isDone = ep.episodeId in completed || pos.completedAt != null
                    ElevatedCard(
                        onClick = { vm.play(ep) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .testTag("history-row-${ep.externalId}"),
                    ) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(ep.title) },
                            supportingContent = {
                                Text(
                                    text = buildString {
                                        append(formatRelativePlayedAt(pos.updatedAt, now))
                                        append(" · ")
                                        append(
                                            if (isDone) {
                                                "played"
                                            } else {
                                                formatResumeSubtitle(pos.positionMs, pos.durationMs)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                        // Only meaningful mid-episode; a finished row's bar
                        // pinned at 100% is noise.
                        if (!isDone && pos.durationMs > 0L) {
                            LinearProgressIndicator(
                                progress = {
                                    (pos.positionMs.toFloat() / pos.durationMs.toFloat())
                                        .coerceIn(0f, 1f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .height(3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
