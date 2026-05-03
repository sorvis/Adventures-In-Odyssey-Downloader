package com.odyssey.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import com.odyssey.debug.DebugLogEntry
import com.odyssey.debug.DebugLogger
import com.odyssey.debug.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the in-process DebugLogger ring buffer. Newest entries first.
 *
 * Reachable from Settings → "Debug logs" so we can diagnose problems
 * (like the v0.1.6 play-button regression) without adb access.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun DebugScreen(onBack: () -> Unit = {}) {
    val entries by DebugLogger.entries.collectAsState()
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug logs (${entries.size})") },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("debug-back")) {
                        Text("Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { copyToClipboard(ctx, entries) },
                        modifier = Modifier.testTag("debug-copy"),
                    ) { Text("Copy") }
                    TextButton(
                        onClick = { DebugLogger.clear() },
                        modifier = Modifier.testTag("debug-clear"),
                    ) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("No log entries yet — go tap Play, then come back.")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .semantics { testTagsAsResourceId = true }
                .testTag("debug-log-list"),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            reverseLayout = true,    // newest at top of visible viewport
        ) {
            items(entries, key = { it.timestampMs.toString() + it.tag + it.message.hashCode() }) { e ->
                LogRow(e)
            }
        }
    }
}

@Composable
private fun LogRow(e: DebugLogEntry) {
    val color = when (e.level) {
        LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
        LogLevel.WARN -> Color(0xFFB07000)
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${formatTs(e.timestampMs)}  ${e.level.name.first()}/${e.tag}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = e.message,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontFamily = FontFamily.Monospace,
        )
        e.throwable?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontFamily = FontFamily.Monospace,
            )
        }
        HorizontalDivider()
    }
}

private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
private fun formatTs(ms: Long): String = tsFmt.format(Date(ms))

private fun copyToClipboard(ctx: Context, entries: List<DebugLogEntry>) {
    val text = buildString {
        for (e in entries) {
            append(formatTs(e.timestampMs)).append("  ")
            append(e.level.name.first()).append('/').append(e.tag).append("  ")
            append(e.message).append('\n')
            e.throwable?.let { append(it).append('\n') }
        }
    }
    ctx.getSystemService<ClipboardManager>()
        ?.setPrimaryClip(ClipData.newPlainText("Odyssey debug log", text))
}
