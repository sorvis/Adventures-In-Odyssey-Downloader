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
import com.odyssey.ui.screens.TransfersScreen
import com.odyssey.ui.screens.YSH_ALBUM_DETAIL_ARG
import com.odyssey.ui.screens.YshAlbumDetailScreen
import com.odyssey.ui.screens.YshAlbumListScreen
import com.odyssey.ui.screens.YshUnmatchedTitlesScreen
import androidx.hilt.navigation.compose.hiltViewModel
import com.odyssey.app.SettingsRepo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class OdysseyNavVm @Inject constructor(
    settings: SettingsRepo,
) : ViewModel() {
    val activeShow = settings.activeShow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "aio")
}

private const val ROUTE_NOW_PLAYING = "now-playing"
private const val ROUTE_DEBUG = "debug"
const val ROUTE_TRANSFERS = "transfers"
const val ROUTE_YSH_UNMATCHED = "ysh-unmatched"

private sealed class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data object Recent     : Tab("recent",     "Recent",   Icons.Default.Home)
    data object Albums     : Tab("albums",     "Albums",   Icons.Default.Album)
    data object Downloaded : Tab("downloaded", "Library",  Icons.Default.Download)
    // "Sync" = browse self-hosted backup server + watch in-flight
    // transfers in one surface (formerly two: "Backup" tab + a
    // standalone Transfers screen reachable from a top-bar button).
    data object Backup     : Tab("backup",     "Sync",     Icons.Default.CloudSync)
    data object Settings   : Tab("settings",   "Settings", Icons.Default.Settings)
}

private val tabs = listOf(Tab.Recent, Tab.Albums, Tab.Downloaded, Tab.Backup, Tab.Settings)

@Composable
fun OdysseyNav(
    navVm: OdysseyNavVm = hiltViewModel(),
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: Tab.Recent.route
    val activeShow by navVm.activeShow.collectAsState()
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
                    // Albums tab adapts to the active show. AIO uses
                    // its catalog-backed list + ownership join; YSH
                    // lists albums grouped from local_episodes rows.
                    // When the user flips active show in Settings the
                    // composable re-binds automatically.
                    val onOpenSettings = {
                        nav.navigate(Tab.Settings.route) {
                            popUpTo(nav.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                    if (activeShow == "ysh") {
                        YshAlbumListScreen(
                            onOpenAlbum = { name ->
                                val encoded = java.net.URLEncoder.encode(name, "UTF-8")
                                nav.navigate("ysh-album/$encoded")
                            },
                            onOpenSettings = onOpenSettings,
                        )
                    } else {
                        AlbumListScreen(
                            onOpenAlbum = { key -> nav.navigate("album/$key") },
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }
                composable(
                    route = "album/{albumKey}",
                    arguments = listOf(navArgument("albumKey") { type = NavType.StringType }),
                ) {
                    AlbumDetailScreen(onBack = { nav.popBackStack() })
                }
                composable(
                    route = "ysh-album/{$YSH_ALBUM_DETAIL_ARG}",
                    arguments = listOf(navArgument(YSH_ALBUM_DETAIL_ARG) { type = NavType.StringType }),
                ) {
                    YshAlbumDetailScreen(onBack = { nav.popBackStack() })
                }
                composable(Tab.Downloaded.route) { DownloadedScreen() }
                composable(Tab.Backup.route) {
                    BrowseNasScreen()
                }
                composable(ROUTE_TRANSFERS) {
                    TransfersScreen(onBack = { nav.popBackStack() })
                }
                composable(Tab.Settings.route) {
                    SettingsScreen(
                        onOpenDebug = { nav.navigate(ROUTE_DEBUG) },
                        onOpenTransfers = { nav.navigate(ROUTE_TRANSFERS) },
                        onOpenYshUnmatched = { nav.navigate(ROUTE_YSH_UNMATCHED) },
                    )
                }
                composable(ROUTE_YSH_UNMATCHED) {
                    YshUnmatchedTitlesScreen(onBack = { nav.popBackStack() })
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
