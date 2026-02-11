package com.sonicmusic.app.presentation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sonicmusic.app.presentation.ui.home.HomeScreen
import com.sonicmusic.app.presentation.ui.library.LibraryScreen
import com.sonicmusic.app.presentation.ui.library.LikedSongsScreen
import com.sonicmusic.app.presentation.ui.library.LocalSongsScreen
import com.sonicmusic.app.presentation.ui.library.PlaylistsScreen
import com.sonicmusic.app.presentation.ui.library.PlaylistDetailScreen
import com.sonicmusic.app.presentation.ui.library.RecentlyPlayedScreen
import com.sonicmusic.app.presentation.ui.player.FullPlayerScreen
import com.sonicmusic.app.presentation.ui.player.MiniPlayer
import com.sonicmusic.app.presentation.ui.search.SearchScreen
import com.sonicmusic.app.presentation.ui.settings.SettingsScreen
import com.sonicmusic.app.presentation.ui.theme.SonicMusicTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    // Notification permission launcher for Android 13+
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Permission result - notification will work if granted
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Request notification permission for Android 13+ (API 33+)
        requestNotificationPermission()
        
        setContent {
            SonicMusicTheme {
                SonicMusicApp()
            }
        }
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            when {
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                    // Already granted
                }
                else -> {
                    // Request permission
                    notificationPermissionLauncher.launch(permission)
                }
            }
        }
    }
}

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Home : Screen("home", "Home", Icons.Filled.Home, Icons.Outlined.Home)
    object Search : Screen("search", "Search", Icons.Filled.Search, Icons.Outlined.Search)
    object Library : Screen("library", "Library", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic)
    object Settings : Screen("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

// Sub-screens (no bottom nav)
object SubScreens {
    const val LIKED_SONGS = "library/liked_songs"
    const val PLAYLISTS = "library/playlists"
    const val PLAYLIST_DETAIL = "library/playlist/{playlistId}"
    const val RECENTLY_PLAYED = "library/recently_played"
    const val LOCAL_SONGS = "library/local_songs"
    
    fun playlistDetail(playlistId: Long) = "library/playlist/$playlistId"
}

val bottomNavItems = listOf(
    Screen.Home,
    Screen.Search,
    Screen.Library,
    Screen.Settings
)

@Composable
fun SonicMusicApp() {
    val navController = rememberNavController()
    var showFullPlayer by remember { mutableStateOf(false) }
    
    // Get current route
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    
    // Show bottom nav on main screens only
    val showBottomNav = bottomNavItems.any { it.route == currentDestination?.route }
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            // Stack MiniPlayer + BottomNavigation together
            AnimatedVisibility(visible = showBottomNav) {
                Column {
                    // MiniPlayer sits above the navigation bar
                    MiniPlayer(
                        onExpand = { showFullPlayer = true }
                    )
                    
                    // ViTune-style Bottom Navigation Bar
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 0.dp,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        bottomNavItems.forEach { screen ->
                            val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                            NavigationBarItem(
                                icon = {
                                    Icon(
                                        imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                        contentDescription = screen.title
                                    )
                                },
                                label = { Text(screen.title) },
                                selected = selected,
                                alwaysShowLabel = true,
                                onClick = {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route
            ) {
                // Main screens
                composable(Screen.Home.route) {
                    HomeScreen(
                        onNavigateToSearch = { 
                            navController.navigate(Screen.Search.route) 
                        },
                        onNavigateToSettings = { 
                            navController.navigate(Screen.Settings.route) 
                        },
                        onShowFullPlayer = { showFullPlayer = true }
                    )
                }
                composable(Screen.Search.route) {
                    SearchScreen(
                        onShowFullPlayer = { showFullPlayer = true }
                    )
                }
                composable(Screen.Library.route) {
                    LibraryScreen(
                        onNavigateToLikedSongs = { 
                            navController.navigate(SubScreens.LIKED_SONGS)
                        },
                        onNavigateToPlaylists = {
                            navController.navigate(SubScreens.PLAYLISTS)
                        },
                        onNavigateToRecentlyPlayed = {
                            navController.navigate(SubScreens.RECENTLY_PLAYED)
                        },
                        onNavigateToLocalSongs = {
                            navController.navigate(SubScreens.LOCAL_SONGS)
                        }
                    )
                }
                composable(Screen.Settings.route) {
                    SettingsScreen()
                }
                
                // Library sub-screens
                composable(SubScreens.LIKED_SONGS) {
                    LikedSongsScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onShowFullPlayer = { showFullPlayer = true }
                    )
                }
                composable(SubScreens.PLAYLISTS) {
                    PlaylistsScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onPlaylistClick = { playlistId ->
                            navController.navigate(SubScreens.playlistDetail(playlistId))
                        }
                    )
                }
                composable(SubScreens.RECENTLY_PLAYED) {
                    RecentlyPlayedScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onShowFullPlayer = { showFullPlayer = true }
                    )
                }
                composable(SubScreens.LOCAL_SONGS) {
                    LocalSongsScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onShowFullPlayer = { showFullPlayer = true }
                    )
                }
                composable(SubScreens.PLAYLIST_DETAIL) { backStackEntry ->
                    val playlistId = backStackEntry.arguments?.getString("playlistId")?.toLongOrNull()
                    if (playlistId != null) {
                        PlaylistDetailScreen(
                            playlistId = playlistId,
                            onNavigateBack = { navController.popBackStack() },
                            onShowFullPlayer = { showFullPlayer = true }
                        )
                    }
                }
            }
        }
    }
    
    // Full Player - modal overlay
    if (showFullPlayer) {
        FullPlayerScreen(
            onDismiss = { showFullPlayer = false }
        )
    }
}