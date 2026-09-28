package com.sonicmusic.app.app

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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.rounded.Person
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
import com.sonicmusic.app.features.home.presentation.HomeSectionDetailScreen
import com.sonicmusic.app.features.home.presentation.HomeScreen
import com.sonicmusic.app.features.library.presentation.DownloadedSongsScreen
import com.sonicmusic.app.features.library.presentation.LibraryScreen
import com.sonicmusic.app.features.library.presentation.LikedSongsScreen
import com.sonicmusic.app.features.albums.presentation.AlbumDetailScreen
import com.sonicmusic.app.features.artists.presentation.ArtistDetailScreen
import com.sonicmusic.app.features.library.presentation.LocalSongsScreen
import com.sonicmusic.app.features.library.presentation.PlaylistsScreen
import com.sonicmusic.app.features.library.presentation.PlaylistDetailScreen
import com.sonicmusic.app.features.library.presentation.RecentlyPlayedScreen
import com.sonicmusic.app.features.player.presentation.FullPlayerScreen
import com.sonicmusic.app.features.player.presentation.MiniPlayer
import com.sonicmusic.app.features.settings.presentation.SettingsScreen
import com.sonicmusic.app.features.search.presentation.SearchScreen
import com.sonicmusic.app.features.charts.presentation.TopSongsScreen
import com.sonicmusic.app.features.charts.presentation.TopArtistsScreen
import com.sonicmusic.app.features.charts.presentation.TopAlbumsScreen
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.sonicmusic.app.theme.LocalDynamicThemeState
import com.sonicmusic.app.theme.SonicMusicTheme
import com.sonicmusic.app.features.home.presentation.HomeViewModel
import com.sonicmusic.app.shared.components.NoInternetBanner
import com.sonicmusic.app.shared.components.LocalIsOnline
import dagger.hilt.android.AndroidEntryPoint

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val settingsViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[com.sonicmusic.app.features.settings.presentation.SettingsViewModel::class.java]
    }

    @javax.inject.Inject
    lateinit var regionRepository: com.sonicmusic.app.data.repository.RegionRepository

    @javax.inject.Inject
    lateinit var settingsRepository: SettingsRepository

    @javax.inject.Inject
    lateinit var networkStatusMonitor: com.sonicmusic.app.core.network.NetworkStatusMonitor
    
    // Notification permission launcher for Android 13+
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission result - notification will work if granted
    }
    
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize region detection
        lifecycleScope.launch {
            regionRepository.initializeRegion()
        }

        // enableEdgeToEdge() // Replaced by WindowCompat for explicit control as requested
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // statusBarColor/navigationBarColor deprecated on API 35+ but needed for API 26-34
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        
        // Request notification permission for Android 13+ (API 33+)
        requestNotificationPermission()
        
        setContent {
            val playerViewModel: com.sonicmusic.app.features.player.presentation.PlayerViewModel =
                androidx.hilt.navigation.compose.hiltViewModel()
            val currentSong by playerViewModel.currentSong.collectAsStateWithLifecycle(initialValue = null)

            val isOnline by networkStatusMonitor.isOnline.collectAsStateWithLifecycle(initialValue = true)
            val windowSizeClass = calculateWindowSizeClass(this)

            SonicMusicTheme(
                artworkUrl = currentSong?.thumbnailUrl
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.sonicmusic.app.shared.components.LocalIsOnline provides isOnline,
                    com.sonicmusic.app.app.LocalWindowSizeClass provides windowSizeClass
                ) {
                    SonicMusicApp(windowSizeClass, settingsViewModel)
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
    const val ALBUM_DETAIL = "library/album/{albumId}?title={title}&artist={artist}&thumbnail={thumbnail}"
    const val RECENTLY_PLAYED = "library/recently_played"
    const val LOCAL_SONGS = "library/local_songs"
    const val DOWNLOADS = "library/downloads"
    const val ARTISTS = "library/artists"
    const val ARTIST_DETAIL = "library/artist/{artistName}?browseId={browseId}"
    const val SETTINGS = "settings"
    const val TOP_SONGS = "charts/songs"
    const val TOP_ARTISTS = "charts/artists"
    const val TOP_ALBUMS = "charts/albums"
    
    fun homeSection(sectionKey: String): String =
        "home/section/${java.net.URLEncoder.encode(sectionKey, "UTF-8")}"
    fun playlistDetail(playlistId: Long) = "library/playlist/$playlistId"
    fun albumDetail(
        albumId: String,
        title: String,
        artist: String,
        thumbnailUrl: String? = null
    ): String {
        val encodedAlbumId = java.net.URLEncoder.encode(albumId, "UTF-8")
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val encodedArtist = java.net.URLEncoder.encode(artist, "UTF-8")
        val encodedThumbnail = thumbnailUrl
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { java.net.URLEncoder.encode(it, "UTF-8") }
            .orEmpty()
        return "library/album/$encodedAlbumId?title=$encodedTitle&artist=$encodedArtist&thumbnail=$encodedThumbnail"
    }
    fun artistDetail(artistName: String, browseId: String? = null): String {
        val encodedName = java.net.URLEncoder.encode(artistName, "UTF-8")
        val encodedBrowseId = browseId?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let {
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

// ─────────────────────────────────────────────────────────────────────────────
// Material 3 Expressive navigation transitions.
// Sub-screens use a shared-axis X pattern (slide + fade) with M3 emphasized
// easing; top-level tabs use fade-through so switching tabs feels instant.
// Allocated once at file scope to avoid per-recomposition lambda churn.
// ─────────────────────────────────────────────────────────────────────────────
private val EmphasizedDecelerateEasing =
    androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerateEasing =
    androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

private val sharedAxisEnter: androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> androidx.compose.animation.EnterTransition = {
    slideIntoContainer(
        towards = androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = tween(durationMillis = 400, easing = EmphasizedDecelerateEasing),
        initialOffset = { fullWidth -> fullWidth / 5 }
    ) + androidx.compose.animation.fadeIn(
        animationSpec = tween(durationMillis = 210, delayMillis = 90)
    )
}

private val sharedAxisExit: androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> androidx.compose.animation.ExitTransition = {
    slideOutOfContainer(
        towards = androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = tween(durationMillis = 400, easing = EmphasizedDecelerateEasing),
        targetOffset = { fullWidth -> fullWidth / 5 }
    ) + androidx.compose.animation.fadeOut(
        animationSpec = tween(durationMillis = 90, easing = EmphasizedAccelerateEasing)
    )
}

private val sharedAxisPopEnter: androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> androidx.compose.animation.EnterTransition = {
    slideIntoContainer(
        towards = androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.End,
        animationSpec = tween(durationMillis = 400, easing = EmphasizedDecelerateEasing),
        initialOffset = { fullWidth -> fullWidth / 5 }
    ) + androidx.compose.animation.fadeIn(
        animationSpec = tween(durationMillis = 210, delayMillis = 90)
    )
}

private val sharedAxisPopExit: androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> androidx.compose.animation.ExitTransition = {
    slideOutOfContainer(
        towards = androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.End,
        animationSpec = tween(durationMillis = 400, easing = EmphasizedDecelerateEasing),
        targetOffset = { fullWidth -> fullWidth / 5 }
    ) + androidx.compose.animation.fadeOut(
        animationSpec = tween(durationMillis = 90, easing = EmphasizedAccelerateEasing)
    )
}

private val fadeThroughEnter: androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> androidx.compose.animation.EnterTransition = {
    androidx.compose.animation.fadeIn(
        animationSpec = tween(durationMillis = 210, delayMillis = 90)
    ) + androidx.compose.animation.scaleIn(
        initialScale = 0.92f,
        animationSpec = tween(durationMillis = 210, delayMillis = 90, easing = EmphasizedDecelerateEasing)
    )
}

private val fadeThroughExit: androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> androidx.compose.animation.ExitTransition = {
    androidx.compose.animation.fadeOut(
        animationSpec = tween(durationMillis = 90, easing = EmphasizedAccelerateEasing)
    )
}

@Composable
fun SonicMusicApp(
    windowSizeClass: WindowSizeClass,
    settingsViewModel: com.sonicmusic.app.features.settings.presentation.SettingsViewModel
) {
    val onboardingCompleted by settingsViewModel.onboardingCompleted.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(onboardingCompleted) {
        if (onboardingCompleted == false) {
            settingsViewModel.setOnboardingCompleted(true)
        }
    }



    Box(modifier = Modifier.fillMaxSize()) {
        if (onboardingCompleted != null && onboardingCompleted != false) {
            val navController = rememberNavController()
            val songActionSheetController = remember { com.sonicmusic.app.features.player.presentation.sheets.SongActionSheetController() }
            val context = androidx.compose.ui.platform.LocalContext.current
            var showFullPlayer by remember { mutableStateOf(false) }
            val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            val openFullPlayer = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                showFullPlayer = true
            }

            androidx.compose.runtime.CompositionLocalProvider(
                com.sonicmusic.app.features.player.presentation.sheets.LocalSongActionSheetController provides songActionSheetController
            ) {
                // Get current route
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                // Global Error Handling (Player)
                val playerViewModel: com.sonicmusic.app.features.player.presentation.PlayerViewModel = androidx.hilt.navigation.compose.hiltViewModel()
                val currentSong by playerViewModel.currentSong.collectAsStateWithLifecycle(initialValue = null)
                val playbackError by playerViewModel.playbackError.collectAsStateWithLifecycle(initialValue = null)
                val playerError by playerViewModel.error.collectAsStateWithLifecycle(initialValue = null)
                val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
                val dynamicState = LocalDynamicThemeState.current
                val colorScheme = MaterialTheme.colorScheme
                
                val backgroundTop = remember(colorScheme.surface, dynamicState.seedColor) {
                    Color(androidx.core.graphics.ColorUtils.blendARGB(colorScheme.surface.toArgb(), dynamicState.seedColor.toArgb(), 0.15f))
                }
                val backgroundMid = remember(colorScheme.surface, dynamicState.seedColor) {
                    Color(androidx.core.graphics.ColorUtils.blendARGB(colorScheme.surface.toArgb(), dynamicState.seedColor.toArgb(), 0.05f))
                }
                val navBarContainer = colorScheme.surfaceContainerHigh
                val navBarIndicator = colorScheme.secondaryContainer

                androidx.compose.runtime.LaunchedEffect(playbackError, playerError) {
                    val errorMsg = playbackError ?: playerError
                    errorMsg?.let {
                        snackbarHostState.showSnackbar(it)
                        playerViewModel.clearError()
                    }
                }
                
                // Show bottom nav on main screens only
                val showBottomNav = bottomNavItems.any { it.route == currentDestination?.route }
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
                            showFullPlayer = openFullPlayer,
                            isFullPlayerVisible = showFullPlayer,
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
                            showFullPlayer = openFullPlayer,
                            isFullPlayerVisible = showFullPlayer,
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
                            if (!showFullPlayer && (showBottomNav || currentSong != null)) {
                                Column {
                                    // MiniPlayer sits above the navigation bar
                                    MiniPlayer(
                                        backgroundColorOverride = navBarContainer,
                                        onExpand = openFullPlayer
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
                                    
                                    // If bottom nav is hidden, we only draw system nav bar spacer if a song is playing
                                    if (!showBottomNav && currentSong != null) {
                                        Spacer(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .windowInsetsBottomHeight(WindowInsets.navigationBars)
                                                .background(navBarContainer)
                                        )
                                    }
                                }
                            }
                        },
                        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) }
                    ) { paddingValues ->
                        MainScaffoldContent(
                            navController = navController,
                            showFullPlayer = openFullPlayer,
                            isFullPlayerVisible = showFullPlayer,
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
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        onDispose { }
                    }
                    FullPlayerScreen(
                        onDismiss = { showFullPlayer = false },
                        onOpenArtist = { artistName, browseId ->
                            showFullPlayer = false
                            navController.navigate(SubScreens.artistDetail(artistName, browseId))
                        },
                        onOpenAlbum = { albumId, title, artist, thumbnailUrl ->
                            showFullPlayer = false
                            navigateToAlbum(
                                navController = navController,
                                context = context,
                                browseId = albumId,
                                title = title,
                                artist = artist,
                                thumbnailUrl = thumbnailUrl
                            )
                        }
                    )
                }

                // Global Reusable Song Action Sheet Host
                com.sonicmusic.app.features.player.presentation.sheets.GlobalSongActionSheetHost(
                    controller = songActionSheetController,
                    navController = navController,
                    onShowFullPlayer = openFullPlayer
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
            )
        }


    }
}

@Composable
fun MainScaffoldContent(
    navController: androidx.navigation.NavHostController,
    showFullPlayer: () -> Unit,
    isFullPlayerVisible: Boolean,
    backgroundTop: Color,
    backgroundMid: Color,
    navBarContainer: Color,
    isCompact: Boolean,
    paddingValues: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp)
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        backgroundTop,
                        backgroundMid,
                        navBarContainer // Use a fallback or appropriate color here depending on theme
                    )
                )
                drawRect(brush = brush)
            }
            .padding(if (!isCompact) androidx.compose.foundation.layout.WindowInsets.safeDrawing.asPaddingValues() else androidx.compose.foundation.layout.PaddingValues(0.dp))
    ) {
        val bottomPadding = paddingValues.calculateBottomPadding()
        val isOnline = LocalIsOnline.current
        val context = androidx.compose.ui.platform.LocalContext.current
        
        val playerViewModel: com.sonicmusic.app.features.player.presentation.PlayerViewModel = hiltViewModel()
        val currentSong by playerViewModel.currentSong.collectAsStateWithLifecycle(initialValue = null)
        
        Column(modifier = Modifier.fillMaxSize()) {
            // Offline banner at top of all screens
            NoInternetBanner(isVisible = !isOnline)
            
            Box(modifier = Modifier.weight(1f)) {
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            enterTransition = sharedAxisEnter,
            exitTransition = sharedAxisExit,
            popEnterTransition = sharedAxisPopEnter,
            popExitTransition = sharedAxisPopExit
        ) {
            // Main screens
            composable(
                route = Screen.Home.route,
                enterTransition = fadeThroughEnter,
                exitTransition = fadeThroughExit,
                popEnterTransition = fadeThroughEnter,
                popExitTransition = fadeThroughExit
            ) {
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
                    onNavigateToArtist = { name, browseId ->
                        navController.navigate(SubScreens.artistDetail(name, browseId))
                    },
                    onShowFullPlayer = showFullPlayer
                )
            }
            composable(
                route = Screen.Search.route,
                enterTransition = fadeThroughEnter,
                exitTransition = fadeThroughExit,
                popEnterTransition = fadeThroughEnter,
                popExitTransition = fadeThroughExit
            ) {
                SearchScreen(
                    bottomPadding = bottomPadding,
                    onShowFullPlayer = showFullPlayer,
                    onNavigateToArtist = { artistName, browseId ->
                        navController.navigate(SubScreens.artistDetail(artistName, browseId))
                    },
                    onNavigateToAlbum = { album ->
                        val albumId = album.albumId?.takeIf { it.isNotBlank() } ?: album.id
                        navigateToAlbum(
                            navController = navController,
                            context = context,
                            browseId = albumId,
                            title = album.title,
                            artist = album.artist,
                            thumbnailUrl = album.thumbnailUrl
                        )
                    },
                    onNavigateToTopSongs = {
                        navController.navigate(SubScreens.TOP_SONGS)
                    },
                    onNavigateToTopArtists = {
                        navController.navigate(SubScreens.TOP_ARTISTS)
                    },
                    onNavigateToTopAlbums = {
                        navController.navigate(SubScreens.TOP_ALBUMS)
                    }
                )
            }
            composable(
                route = Screen.Library.route,
                enterTransition = fadeThroughEnter,
                exitTransition = fadeThroughExit,
                popEnterTransition = fadeThroughEnter,
                popExitTransition = fadeThroughExit
            ) {
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
            composable(
                route = SubScreens.SETTINGS,
                enterTransition = fadeThroughEnter,
                exitTransition = fadeThroughExit,
                popEnterTransition = fadeThroughEnter,
                popExitTransition = fadeThroughExit
            ) {
                SettingsScreen(
                    bottomPadding = bottomPadding,
                    onNavigateBack = { navController.popBackStack() }
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
                route = SubScreens.ALBUM_DETAIL,
                arguments = listOf(
                    navArgument("albumId") { type = NavType.StringType },
                    navArgument("title") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("artist") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("thumbnail") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val albumId = backStackEntry.arguments?.getString("albumId")?.let {
                    java.net.URLDecoder.decode(it, "UTF-8")
                }.orEmpty()
                val title = backStackEntry.arguments?.getString("title")?.let {
                    java.net.URLDecoder.decode(it, "UTF-8")
                }.orEmpty()
                val artist = backStackEntry.arguments?.getString("artist")?.let {
                    java.net.URLDecoder.decode(it, "UTF-8")
                }.orEmpty()
                val thumbnailUrl = backStackEntry.arguments?.getString("thumbnail")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }

                AlbumDetailScreen(
                    albumId = albumId,
                    albumTitle = title,
                    artistName = artist,
                    thumbnailUrl = thumbnailUrl,
                    onNavigateBack = { navController.popBackStack() },
                    onShowFullPlayer = showFullPlayer,
                    onNavigateToArtist = { name, browseId ->
                        navController.navigate(SubScreens.artistDetail(name, browseId))
                    },
                    bottomPadding = bottomPadding
                )
            }
            composable(
                route = "album/{browseId}",
                arguments = listOf(
                    navArgument("browseId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val browseId = backStackEntry.arguments?.getString("browseId")?.let {
                    java.net.URLDecoder.decode(it, "UTF-8")
                }.orEmpty()
                AlbumDetailScreen(
                    albumId = browseId,
                    albumTitle = "",
                    artistName = "",
                    thumbnailUrl = null,
                    onNavigateBack = { navController.popBackStack() },
                    onShowFullPlayer = showFullPlayer,
                    onNavigateToArtist = { name, artistBrowseId ->
                        navController.navigate(SubScreens.artistDetail(name, artistBrowseId))
                    },
                    bottomPadding = bottomPadding
                )
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
                val rawName = backStackEntry.arguments?.getString("artistName").orEmpty()
                val artistName = runCatching { java.net.URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
                val rawBrowseId = backStackEntry.arguments?.getString("browseId")
                val browseId = rawBrowseId
                    ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it != "null" }
                ArtistDetailScreen(
                    artistName = artistName,
                    browseId = browseId,
                    onNavigateBack = { navController.popBackStack() },
                    onShowFullPlayer = showFullPlayer,
                    onNavigateToArtist = { relatedArtist, relatedBrowseId ->
                        navController.navigate(SubScreens.artistDetail(relatedArtist, relatedBrowseId))
                    },
                    onNavigateToAlbum = { albumId, title, artist, thumb ->
                        navigateToAlbum(navController, context, albumId, title, artist, thumb)
                    },
                    bottomPadding = bottomPadding
                )
            }
            
            composable(SubScreens.ARTISTS) {
                com.sonicmusic.app.features.artists.presentation.ArtistsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onArtistClick = { artistName, browseId ->
                        navController.navigate(SubScreens.artistDetail(artistName, browseId))
                    },
                    bottomPadding = bottomPadding
                )
            }
            
            composable(SubScreens.TOP_SONGS) {
                TopSongsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    bottomPadding = bottomPadding
                )
            }

            composable(SubScreens.TOP_ARTISTS) {
                TopArtistsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onArtistClick = { artistName, browseId ->
                        navController.navigate(SubScreens.artistDetail(artistName, browseId))
                    },
                    bottomPadding = bottomPadding
                )
            }

            composable(SubScreens.TOP_ALBUMS) {
                TopAlbumsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onAlbumClick = { album ->
                        navigateToAlbum(
                            navController = navController,
                            context = context,
                            browseId = album.browseId,
                            title = album.title,
                            artist = album.artist,
                            thumbnailUrl = album.thumbnailUrl
                        )
                    },
                    bottomPadding = bottomPadding
                )
            }
        } // NavHost
        } // Box(weight)
        
        if (!isCompact && currentSong != null && !isFullPlayerVisible) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(navBarContainer),
                contentAlignment = Alignment.Center
            ) {
                MiniPlayer(
                    modifier = Modifier
                        .widthIn(max = com.sonicmusic.app.theme.Dimens.maxContentWidth)
                        .fillMaxWidth(),
                    backgroundColorOverride = navBarContainer,
                    onExpand = showFullPlayer
                )
            }
        }
        } // Column
    } // Box(drawBehind)
}


