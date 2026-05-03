package com.odyssey.ui.screens

import androidx.compose.foundation.layout.*
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
import com.odyssey.app.SettingsRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsVm @Inject constructor(private val settings: SettingsRepo) : ViewModel() {
    val state = settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun saveNas(url: String, token: String) = viewModelScope.launch { settings.setNas(url, token) }
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

    var nasUrl by remember(current.nasUrl) { mutableStateOf(current.nasUrl) }
    var nasToken by remember(current.nasToken) { mutableStateOf(current.nasToken) }
    var retention by remember(current.retentionCount) { mutableStateOf(current.retentionCount.toString()) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
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
            Button(onClick = { vm.saveNas(nasUrl, nasToken) }) { Text("Save NAS settings") }

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
        }
    }
}
