package com.sonicmusic.app.presentation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.data.repository.SettingsRepository
import com.sonicmusic.app.domain.model.ThemeMode
import com.sonicmusic.app.presentation.ui.home.HomeSectionDetailScreen
import com.sonicmusic.app.presentation.ui.home.HomeScreen
import com.sonicmusic.app.presentation.ui.library.DownloadedSongsScreen
import com.sonicmusic.app.presentation.ui.library.LibraryScreen
import com.sonicmusic.app.presentation.ui.library.LikedSongsScreen
import com.sonicmusic.app.presentation.ui.library.ArtistDetailScreen
import com.sonicmusic.app.presentation.ui.library.LocalSongsScreen
import com.sonicmusic.app.presentation.ui.library.PlaylistsScreen
import com.sonicmusic.app.presentation.ui.library.PlaylistDetailScreen
import com.sonicmusic.app.presentation.ui.library.RecentlyPlayedScreen
import com.sonicmusic.app.presentation.ui.player.FullPlayerScreen
import com.sonicmusic.app.presentation.ui.player.MiniPlayer
import com.sonicmusic.app.presentation.ui.theme.LocalDynamicThemeState
import com.sonicmusic.app.presentation.ui.search.SearchScreen
import com.sonicmusic.app.presentation.ui.settings.SettingsScreen
import com.sonicmusic.app.presentation.ui.theme.SonicMusicTheme
import com.sonicmusic.app.presentation.viewmodel.HomeViewModel
import dagger.hilt.android.AndroidEntryPoint

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @javax.inject.Inject
    lateinit var regionRepository: com.sonicmusic.app.data.repository.RegionRepository

    @javax.inject.Inject
    lateinit var settingsRepository: SettingsRepository

    @javax.inject.Inject
    lateinit var networkStatusMonitor: com.sonicmusic.app.core.network.NetworkStatusMonitor
    
    // Notification permission launcher for Android 13+
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Permission result - notification will work if granted
    }
    
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        
        // Initialize region detection
        lifecycleScope.launch {
            regionRepository.initializeRegion()
        }

        // enableEdgeToEdge() // Replaced by WindowCompat for explicit control as requested
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        
        // Request notification permission for Android 13+ (API 33+)
        requestNotificationPermission()
        
        setContent {
            val playerViewModel: com.sonicmusic.app.presentation.viewmodel.PlayerViewModel =
                androidx.hilt.navigation.compose.hiltViewModel()
            val currentSong by playerViewModel.currentSong.collectAsStateWithLifecycle(initialValue = null)

            val isOnline by networkStatusMonitor.isOnline.collectAsStateWithLifecycle(initialValue = true)
            val windowSizeClass = calculateWindowSizeClass(this)

            SonicMusicTheme(
                artworkUrl = currentSong?.thumbnailUrl
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.sonicmusic.app.presentation.ui.components.LocalIsOnline provides isOnline
                ) {
                    SonicMusicApp(windowSizeClass)
                }
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
    object Home : Screen("home", "Home", Icons.Rounded.Home, Icons.Rounded.Home)
    object Search : Screen("search", "Search", Icons.Rounded.Search, Icons.Rounded.Search)
    object Library : Screen("library", "Library", Icons.Rounded.LibraryMusic, Icons.Rounded.LibraryMusic)
    object Settings : Screen("settings", "Settings", Icons.Rounded.Settings, Icons.Rounded.Settings)
}

// Sub-screens (no bottom nav)
object SubScreens {
    const val HOME_SECTION = "home/section/{sectionKey}"
    const val LIKED_SONGS = "library/liked_songs"
    const val PLAYLISTS = "library/playlists"
    const val PLAYLIST_DETAIL = "library/playlist/{playlistId}"
    const val RECENTLY_PLAYED = "library/recently_played"
    const val LOCAL_SONGS = "library/local_songs"
    const val DOWNLOADS = "library/downloads"
    const val ARTISTS = "library/artists"
    const val ARTIST_DETAIL = "library/artist/{artistName}?browseId={browseId}"
    
    fun homeSection(sectionKey: String): String =
        "home/section/${java.net.URLEncoder.encode(sectionKey, "UTF-8")}"
    fun playlistDetail(playlistId: Long) = "library/playlist/$playlistId"
    fun artistDetail(artistName: String, browseId: String? = null): String {
        val encodedName = java.net.URLEncoder.encode(artistName, "UTF-8")
        val encodedBrowseId = browseId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            java.net.URLEncoder.encode(it, "UTF-8")
        }
        return if (encodedBrowseId.isNullOrBlank()) {
            "library/artist/$encodedName"
        } else {
            "library/artist/$encodedName?browseId=$encodedBrowseId"
        }
    }
}

val bottomNavItems = listOf(
    Screen.Home,
    Screen.Search,
    Screen.Library,
    Screen.Settings
)

@Composable
fun SonicMusicApp(windowSizeClass: WindowSizeClass) {
    val navController = rememberNavController()
    var showFullPlayer by remember { mutableStateOf(false) }
    
    // Get current route
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Global Error Handling (Player)
    val playerViewModel: com.sonicmusic.app.presentation.viewmodel.PlayerViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val playbackError by playerViewModel.playbackError.collectAsStateWithLifecycle(initialValue = null)
    val playerError by playerViewModel.error.collectAsStateWithLifecycle(initialValue = null)
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val dynamicState = LocalDynamicThemeState.current
    val colorScheme = MaterialTheme.colorScheme
    
    val backgroundTop by animateColorAsState(
        targetValue = Color(androidx.core.graphics.ColorUtils.blendARGB(colorScheme.surface.toArgb(), dynamicState.seedColor.toArgb(), 0.15f)),
        animationSpec = tween(durationMillis = 1000),
        label = "app_background_top"
    )
    val backgroundMid by animateColorAsState(
        targetValue = Color(androidx.core.graphics.ColorUtils.blendARGB(colorScheme.surface.toArgb(), dynamicState.seedColor.toArgb(), 0.05f)),
        animationSpec = tween(durationMillis = 1000),
        label = "app_background_mid"
    )
    val navBarContainer by animateColorAsState(
        targetValue = colorScheme.surfaceContainerHigh,
        animationSpec = tween(durationMillis = 1000),
        label = "app_nav_bar_container"
    )
    val navBarIndicator by animateColorAsState(
        targetValue = colorScheme.secondaryContainer,
        animationSpec = tween(durationMillis = 1000),
        label = "app_nav_bar_indicator"
    )

    androidx.compose.runtime.LaunchedEffect(playbackError, playerError) {
        val errorMsg = playbackError ?: playerError
        errorMsg?.let {
            snackbarHostState.showSnackbar(it)
            playerViewModel.clearError()
        }
    }
    
    // Show bottom nav on main screens only
    val showBottomNav = bottomNavItems.any { it.route == currentDestination?.route }
    val isCompact = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact
    val isMedium = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Medium
    val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

    if (isExpanded) {
        // Tablet / Foldable open landscape
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(
                    drawerContainerColor = navBarContainer,
                    drawerContentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Spacer(Modifier.height(12.dp))
                    bottomNavItems.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                        androidx.compose.material3.NavigationDrawerItem(
                            icon = { Icon(if (selected) screen.selectedIcon else screen.unselectedIcon, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            colors = androidx.compose.material3.NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = navBarIndicator,
                                unselectedContainerColor = Color.Transparent,
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    // Could place MiniPlayer here or outside
                }
            }
        ) {
            MainScaffoldContent(
                navController = navController,
                showFullPlayer = { showFullPlayer = true },
                snackbarHostState = snackbarHostState,
                backgroundTop = backgroundTop,
                backgroundMid = backgroundMid,
                navBarContainer = navBarContainer,
                isCompact = false
            )
        }
    } else if (isMedium) {
        // Landscape phone / Foldable half open
        Row(Modifier.fillMaxSize()) {
            NavigationRail(
                containerColor = navBarContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                header = {
                    val fallbackTitle = bottomNavItems.firstOrNull { it.route == currentDestination?.route }?.title
                    if (fallbackTitle != null) Spacer(Modifier.height(8.dp))
                }
            ) {
                Spacer(Modifier.height(8.dp))
                bottomNavItems.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationRailItem(
                        icon = { Icon(if (selected) screen.selectedIcon else screen.unselectedIcon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = navBarIndicator,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
            MainScaffoldContent(
                navController = navController,
                showFullPlayer = { showFullPlayer = true },
                snackbarHostState = snackbarHostState,
                backgroundTop = backgroundTop,
                backgroundMid = backgroundMid,
                navBarContainer = navBarContainer,
                isCompact = false
            )
        }
    } else {
        // Compact: Traditional Phone Layout
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                // Stack MiniPlayer + BottomNavigation together
                Column {
                    // MiniPlayer sits above the navigation bar
                    MiniPlayer(
                        backgroundColorOverride = navBarContainer,
                        onExpand = { showFullPlayer = true }
                    )
                    
                AnimatedVisibility(visible = showBottomNav) {
                    // ViTune-style Bottom Navigation Bar
                    NavigationBar(
                        containerColor = navBarContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 3.dp,
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
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    indicatorColor = navBarIndicator,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
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
                
                // If bottom nav is hidden, we need to handle system nav bar insets
                if (!showBottomNav) {
                    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        MainScaffoldContent(
            navController = navController,
            showFullPlayer = { showFullPlayer = true },
            snackbarHostState = snackbarHostState,
            backgroundTop = backgroundTop,
            backgroundMid = backgroundMid,
            navBarContainer = navBarContainer,
            isCompact = true,
            paddingValues = paddingValues
        )
    }
    }
    
    // Full Player - modal overlay
    if (showFullPlayer) {
        FullPlayerScreen(
            onDismiss = { showFullPlayer = false },
            onOpenArtist = { artistName, browseId ->
                showFullPlayer = false
                navController.navigate(SubScreens.artistDetail(artistName, browseId))
            }
        )
    }
}

@Composable
fun MainScaffoldContent(
    navController: androidx.navigation.NavHostController,
    showFullPlayer: () -> Unit,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    backgroundTop: Color,
    backgroundMid: Color,
    navBarContainer: Color,
    isCompact: Boolean,
    paddingValues: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp)
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        backgroundTop,
                        backgroundMid,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .padding(if (!isCompact) androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues() else androidx.compose.foundation.layout.PaddingValues(0.dp))
    ) {
        val bottomPadding = paddingValues.calculateBottomPadding()
        
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route
        ) {
            // Main screens
            composable(Screen.Home.route) {
                HomeScreen(
                    bottomPadding = bottomPadding,
                    onNavigateToSearch = { 
                        navController.navigate(Screen.Search.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToHomeSection = { sectionKey ->
                        navController.navigate(SubScreens.homeSection(sectionKey))
                    },
                    onNavigateToLikedSongs = {
                        navController.navigate(SubScreens.LIKED_SONGS)
                    },
                    onNavigateToDownloads = {
                        navController.navigate(SubScreens.DOWNLOADS)
                    },
                    onNavigateToPlaylists = {
                        navController.navigate(SubScreens.PLAYLISTS)
                    },
                    onNavigateToRecentlyPlayed = {
                        navController.navigate(SubScreens.RECENTLY_PLAYED)
                    },
                    onNavigateToSettings = { 
                        navController.navigate(Screen.Settings.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onShowFullPlayer = showFullPlayer
                )
            }
            composable(Screen.Search.route) {
                SearchScreen(
                    bottomPadding = bottomPadding,
                    onShowFullPlayer = showFullPlayer,
                    onNavigateToArtist = { artistName, browseId ->
                        navController.navigate(SubScreens.artistDetail(artistName, browseId))
                    }
                )
            }
            composable(Screen.Library.route) {
                LibraryScreen(
                    bottomPadding = bottomPadding,
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
                    },
                    onNavigateToDownloads = {
                        navController.navigate(SubScreens.DOWNLOADS)
                    },
                    onNavigateToPlaylistDetail = { playlistId ->
                        navController.navigate(SubScreens.playlistDetail(playlistId))
                    },
                    onNavigateToArtistDetail = { artistName ->
                        navController.navigate(SubScreens.artistDetail(artistName))
                    },
                    onNavigateToArtists = {
                        navController.navigate(SubScreens.ARTISTS)
                    },
                    onShowFullPlayer = showFullPlayer
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    bottomPadding = bottomPadding
                )
            }
            
            // Library sub-screens
            composable(SubScreens.HOME_SECTION) { backStackEntry ->
                val sectionKey = backStackEntry.arguments
                    ?.getString("sectionKey")
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    .orEmpty()

                val homeEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(Screen.Home.route)
                }
                val homeViewModel: HomeViewModel = hiltViewModel(homeEntry)

                HomeSectionDetailScreen(
                    sectionKey = sectionKey,
                    onNavigateBack = { navController.popBackStack() },
                    onShowFullPlayer = showFullPlayer,
                    bottomPadding = bottomPadding,
                    viewModel = homeViewModel
                )
            }
            composable(SubScreens.LIKED_SONGS) {
                LikedSongsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onShowFullPlayer = showFullPlayer,
                    bottomPadding = bottomPadding
                )
            }
            composable(SubScreens.PLAYLISTS) {
                PlaylistsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onPlaylistClick = { playlistId ->
                        navController.navigate(SubScreens.playlistDetail(playlistId))
                    },
                    bottomPadding = bottomPadding
                )
            }
            composable(SubScreens.RECENTLY_PLAYED) {
                RecentlyPlayedScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onShowFullPlayer = showFullPlayer,
                    bottomPadding = bottomPadding
                )
            }
            composable(SubScreens.LOCAL_SONGS) {
                LocalSongsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onShowFullPlayer = showFullPlayer,
                    bottomPadding = bottomPadding
                )
            }
            composable(SubScreens.DOWNLOADS) {
                DownloadedSongsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onShowFullPlayer = showFullPlayer,
                    bottomPadding = bottomPadding
                )
            }
            composable(SubScreens.PLAYLIST_DETAIL) { backStackEntry ->
                val playlistId = backStackEntry.arguments?.getString("playlistId")?.toLongOrNull()
                if (playlistId != null) {
                    PlaylistDetailScreen(
                        playlistId = playlistId,
                        onNavigateBack = { navController.popBackStack() },
                        onShowFullPlayer = showFullPlayer,
                        bottomPadding = bottomPadding
                    )
                }
            }
            composable(
                route = SubScreens.ARTIST_DETAIL,
                arguments = listOf(
                    navArgument("artistName") { type = NavType.StringType },
                    navArgument("browseId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val artistName = backStackEntry.arguments?.getString("artistName")?.let {
                    java.net.URLDecoder.decode(it, "UTF-8")
                } ?: ""
                val browseId = backStackEntry.arguments?.getString("browseId")?.let {
                    java.net.URLDecoder.decode(it, "UTF-8")
                }
                ArtistDetailScreen(
                    artistName = artistName,
                    browseId = browseId,
                    onNavigateBack = { navController.popBackStack() },
                    onShowFullPlayer = showFullPlayer,
                    onNavigateToArtist = { relatedArtist, relatedBrowseId ->
                        navController.navigate(SubScreens.artistDetail(relatedArtist, relatedBrowseId))
                    },
                    bottomPadding = bottomPadding
                )
            }
            
            composable(SubScreens.ARTISTS) {
                com.sonicmusic.app.presentation.ui.library.ArtistsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onArtistClick = { artistName, browseId ->
                        navController.navigate(SubScreens.artistDetail(artistName, browseId))
                    },
                    bottomPadding = bottomPadding
                )
            }
        }
    }
}
