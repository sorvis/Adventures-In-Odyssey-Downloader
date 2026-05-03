package com.odyssey.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.odyssey.ui.screens.BrowseNasScreen
import com.odyssey.ui.screens.DebugScreen
import com.odyssey.ui.screens.NowPlayingScreen
import com.odyssey.ui.screens.RecentScreen
import com.odyssey.ui.screens.SettingsScreen

private sealed class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data object Recent   : Tab("recent",   "Recent",   Icons.Default.Home)
    data object Browse   : Tab("browse",   "NAS",      Icons.Default.Storage)
    data object Now      : Tab("now",      "Player",   Icons.Default.PlayArrow)
    data object Settings : Tab("settings", "Settings", Icons.Default.Settings)
}

private val tabs = listOf(Tab.Recent, Tab.Browse, Tab.Now, Tab.Settings)

@Composable
fun OdysseyNav() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: Tab.Recent.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(nav, startDestination = Tab.Recent.route) {
                composable(Tab.Recent.route)   {
                    RecentScreen(onNavigateToSettings = {
                        nav.navigate(Tab.Settings.route) {
                            popUpTo(nav.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    })
                }
                composable(Tab.Browse.route)   { BrowseNasScreen() }
                composable(Tab.Now.route)      { NowPlayingScreen() }
                composable(Tab.Settings.route) {
                    SettingsScreen(onOpenDebug = { nav.navigate("debug") })
                }
                composable("debug") {
                    DebugScreen(onBack = { nav.popBackStack() })
                }
            }
        }
    }
}
