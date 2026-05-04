package com.odyssey.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.odyssey.ui.screens.AlbumDetailScreen
import com.odyssey.ui.screens.AlbumListScreen
import com.odyssey.ui.screens.BrowseNasScreen
import com.odyssey.ui.screens.DebugScreen
import com.odyssey.ui.screens.DownloadedScreen
import com.odyssey.ui.screens.MiniPlayerBar
import com.odyssey.ui.screens.NowPlayingScreen
import com.odyssey.ui.screens.RecentScreen
import com.odyssey.ui.screens.SettingsScreen

private const val ROUTE_NOW_PLAYING = "now-playing"
private const val ROUTE_DEBUG = "debug"

private sealed class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data object Recent     : Tab("recent",     "Recent",   Icons.Default.Home)
    data object Albums     : Tab("albums",     "Albums",   Icons.Default.Album)
    data object Downloaded : Tab("downloaded", "Library",  Icons.Default.Download)
    // "Backup" = self-hosted backup server (formerly NAS tab).
    data object Backup     : Tab("backup",     "Backup",   Icons.Default.CloudDone)
    data object Settings   : Tab("settings",   "Settings", Icons.Default.Settings)
}

private val tabs = listOf(Tab.Recent, Tab.Albums, Tab.Downloaded, Tab.Backup, Tab.Settings)

@Composable
fun OdysseyNav() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: Tab.Recent.route
    // Sub-routes (album/{key}, now-playing, debug) shouldn't deselect the parent tab.
    val tabRoute = tabs.firstOrNull { currentRoute.startsWith(it.route) }?.route ?: currentRoute

    // The full Now-Playing screen IS the player surface — when the user
    // is already there, hiding the mini-bar AND the bottom tabs gives
    // the player the full screen for transport controls. The down-arrow
    // in its TopAppBar is the way back.
    val isPlayerRoute = currentRoute == ROUTE_NOW_PLAYING

    Scaffold(
        bottomBar = {
            if (isPlayerRoute) return@Scaffold
            Column {
                MiniPlayerBar(onExpand = {
                    nav.navigate(ROUTE_NOW_PLAYING) {
                        launchSingleTop = true
                    }
                })
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = tabRoute == tab.route,
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
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(nav, startDestination = Tab.Recent.route) {
                composable(Tab.Recent.route) {
                    RecentScreen(onNavigateToSettings = {
                        nav.navigate(Tab.Settings.route) {
                            popUpTo(nav.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    })
                }
                composable(Tab.Albums.route) {
                    AlbumListScreen(onOpenAlbum = { key -> nav.navigate("album/$key") })
                }
                composable(
                    route = "album/{albumKey}",
                    arguments = listOf(navArgument("albumKey") { type = NavType.StringType }),
                ) {
                    AlbumDetailScreen(onBack = { nav.popBackStack() })
                }
                composable(Tab.Downloaded.route) { DownloadedScreen() }
                composable(Tab.Backup.route)     { BrowseNasScreen() }
                composable(Tab.Settings.route) {
                    SettingsScreen(onOpenDebug = { nav.navigate(ROUTE_DEBUG) })
                }
                composable(ROUTE_NOW_PLAYING) {
                    NowPlayingScreen(onBack = { nav.popBackStack() })
                }
                composable(ROUTE_DEBUG) {
                    DebugScreen(onBack = { nav.popBackStack() })
                }
            }
        }
    }
}
