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
 * Compact top-bar affordance that lets the user flip activeShow with
 * a single tap from the album list. Renders only when at least two
 * providers are enabled — otherwise it's a single-item menu, which is
 * a footgun that wastes screen real estate. Discovery for a brand-new
 * YSH user happens in Settings → Shows; the dropdown exists for fast
 * switching once both shows are on.
 *
 * The "Manage shows…" footer entry deep-links to Settings → Shows so
 * users can disable a show without rummaging through the nav.
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
    if (visibleProviders.size < 2) return  // see kdoc
    ShowSwitcherUi(
        providers = visibleProviders,
        activeId = active,
        onPickActive = vm::setActiveShow,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
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
