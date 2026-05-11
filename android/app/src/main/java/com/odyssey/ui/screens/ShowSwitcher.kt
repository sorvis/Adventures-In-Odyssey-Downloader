package com.odyssey.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odyssey.app.SettingsRepo
import com.odyssey.show.ProviderRegistry
import com.odyssey.show.ShowProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShowSwitcherVm @Inject constructor(
    private val settings: SettingsRepo,
    val providerRegistry: ProviderRegistry,
) : ViewModel() {
    val activeShow = settings.activeShow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "aio")
    val enabledProviders = settings.enabledProviders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), setOf("aio"))

    fun setActiveShow(providerId: String) = viewModelScope.launch {
        settings.setActiveShow(providerId)
    }
}

/**
 * Compact top-bar affordance with two render modes depending on how
 * many providers are enabled:
 *
 *   1. Two or more enabled → full dropdown. Tap to switch active
 *      show; "Manage shows…" footer deep-links to Settings.
 *   2. Exactly one enabled → a non-clickable label of the active
 *      show's displayName, so the user always has a visible
 *      indicator of which mode they're in. (Discovery for a brand-
 *      new YSH user happens in Settings → Shows; once two shows are
 *      on, this widget grows into the interactive dropdown.)
 *   3. Zero enabled → renders nothing.
 */
@Composable
fun ShowSwitcher(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    vm: ShowSwitcherVm = hiltViewModel(),
) {
    val active by vm.activeShow.collectAsState()
    val enabled by vm.enabledProviders.collectAsState()
    val visibleProviders = vm.providerRegistry.all.filter { it.id in enabled }
    when (visibleProviders.size) {
        0 -> return
        1 -> ActiveShowLabel(
            displayName = visibleProviders.first().displayName,
            modifier = modifier,
        )
        else -> ShowSwitcherUi(
            providers = visibleProviders,
            activeId = active,
            onPickActive = vm::setActiveShow,
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )
    }
}

/**
 * Static "current mode" label — used when only one show is enabled so
 * the user still has a visible cue of which show they're in. Public
 * for tests; production callers go through ShowSwitcher.
 */
@Composable
internal fun ActiveShowLabel(displayName: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(modifier = modifier.padding(end = 4.dp)) {
        AssistChip(
            onClick = {},
            enabled = false,
            label = { Text(displayName, modifier = Modifier.testTag("active-show-label")) },
        )
    }
}

/**
 * UI-only entry point — split out so Compose tests can drive the
 * dropdown without the full Hilt VM.
 */
@Composable
internal fun ShowSwitcherUi(
    providers: List<ShowProvider>,
    activeId: String,
    onPickActive: (providerId: String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val activeLabel = providers.firstOrNull { it.id == activeId }?.displayName ?: "—"
    Box(modifier = modifier) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("show-switcher-button"),
        ) {
            Text(activeLabel)
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch show")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag("show-switcher-menu"),
        ) {
            providers.sortedWith(compareBy({ it.id != "aio" }, { it.displayName })).forEach { p ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            if (p.id == activeId) {
                                Text("✓ ", style = MaterialTheme.typography.bodyLarge)
                            }
                            Text(p.displayName)
                        }
                    },
                    onClick = {
                        onPickActive(p.id)
                        expanded = false
                    },
                    modifier = Modifier.testTag("show-switcher-item-${p.id}"),
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Manage shows…") },
                onClick = {
                    expanded = false
                    onOpenSettings()
                },
                modifier = Modifier.testTag("show-switcher-manage"),
            )
        }
    }
}

// Layout helper because the file uses Modifier.padding(8.dp).
@Composable
private fun Box(modifier: Modifier, content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.Box(modifier = modifier.padding(end = 4.dp)) { content() }

@Composable
private fun Row(
    verticalAlignment: androidx.compose.ui.Alignment.Vertical,
    content: @Composable () -> Unit,
) = androidx.compose.foundation.layout.Row(verticalAlignment = verticalAlignment) { content() }
