package com.sonicmusic.app.presentation.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import com.sonicmusic.app.presentation.ui.components.pullrefresh.M3ExpressivePullToRefresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.sonicmusic.app.presentation.ui.components.HomeScreenSkeleton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.presentation.ui.components.SongThumbnail
import com.sonicmusic.app.presentation.viewmodel.HomeViewModel
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onNavigateToSearch: () -> Unit = {},
    onNavigateToHomeSection: (String) -> Unit = {},
    onNavigateToLikedSongs: () -> Unit = {},
    onNavigateToDownloads: () -> Unit = {},
    onNavigateToPlaylists: () -> Unit = {},
    onNavigateToRecentlyPlayed: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onShowFullPlayer: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val homeContent by viewModel.homeContent.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshResult by viewModel.refreshResult.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val countryName by viewModel.countryName.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val onHomeSectionSongClick: (String, Song) -> Unit = { sectionKey, song ->
        if (viewModel.onSectionSongClick(sectionKey, song)) {
            onShowFullPlayer()
        }
    }
    val onHomeSectionPlay: (String, Boolean) -> Unit = { sectionKey, shuffle ->
        if (viewModel.playSection(sectionKey, shuffle = shuffle)) {
            onShowFullPlayer()
        }
    }
    val onHomeSectionSeeAll: (String) -> Unit = { sectionKey ->
        viewModel.onSectionSeeAll(sectionKey)?.let(onNavigateToHomeSection)
    }


    // Show error snackbar with retry action
    LaunchedEffect(error) {
        error?.let { errorMessage ->
            val result = snackbarHostState.showSnackbar(
                message = errorMessage,
                actionLabel = "Retry",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.loadHomeContent()
            }
            viewModel.clearError()
        }
    }

    val settingsViewModel: com.sonicmusic.app.presentation.viewmodel.SettingsViewModel = hiltViewModel()
    val appUpdateState by settingsViewModel.appUpdateState.collectAsStateWithLifecycle()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0)
    ) { paddingValues ->
        val isOnline = com.sonicmusic.app.presentation.ui.components.LocalIsOnline.current
        var showUpdateDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }

        if (appUpdateState.isUpdateAvailable && !appUpdateState.isDownloading && showUpdateDialog) {
            AlertDialog(
                onDismissRequest = { showUpdateDialog = false },
                title = { Text(text = "Update Available") },
                text = { Text(text = "A new version of SonicMusic (v${appUpdateState.latestVersion}) is available. Would you like to update now?") },
                confirmButton = {
                    TextButton(onClick = { 
                        settingsViewModel.downloadUpdate() 
                        showUpdateDialog = false
                    }) {
                        Text("Update")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text("Later")
                    }
                }
            )
        }

        if (!isOnline && homeContent.listenAgain.isEmpty() && homeContent.quickPicks.isEmpty()) {
            // No internet and no cached content
            com.sonicmusic.app.presentation.ui.components.NoInternetView(
                onRetry = { viewModel.loadHomeContent() },
                modifier = Modifier.padding(paddingValues)
            )
        } else if (isLoading && homeContent.listenAgain.isEmpty()) {
            HomeScreenSkeleton(
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = bottomPadding
                )
            )
        } else {
            M3ExpressivePullToRefresh(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refreshHomeContent() },
                refreshResult = refreshResult,
                onRefreshResultConsumed = { viewModel.clearRefreshResult() },
                indicatorPadding = WindowInsets.statusBars.asPaddingValues(),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomPadding)
                ) {
                    // ═══════════════════════════════════════════
                    // 1️⃣ HEADER - Greeting + Search + Settings
                    // ═══════════════════════════════════════════
                    item(key = "home_header") {
                        HomeHeader(
                            onSearchClick = onNavigateToSearch,
                            onSettingsClick = onNavigateToSettings
                        )
                    }
                    item(key = "quick_actions") {
                        HomeQuickActions(
                            onLikedSongsClick = onNavigateToLikedSongs,
                            onDownloadsClick = onNavigateToDownloads,
                            onPlaylistsClick = onNavigateToPlaylists,
                            onRecentlyPlayedClick = onNavigateToRecentlyPlayed
                        )
                    }

                    // ... (rest of the content) ...
                    
                    // ═══════════════════════════════════════════
                    // 2️⃣ CONTINUE LISTENING
                    // Shows single last-played song only (ViTune style)
                    // ═══════════════════════════════════════════
                    if (homeContent.listenAgain.isNotEmpty()) {
                        // User has history — show the most recent song with full section queue
                        item(key = "continue_listening") {
                            ContinueListeningSection(
                                lastSong = homeContent.listenAgain.first(),
                                onSongClick = { song ->
                                    onHomeSectionSongClick(HomeViewModel.SECTION_LISTEN_AGAIN, song)
                                }
                            )
                        }
                    } else if (homeContent.quickPicks.isNotEmpty()) {
                        // New user — show "For You" horizontal row
                        item(key = "for_you") {
                            ForYouSection(
                                songs = homeContent.quickPicks.take(10),
                                onSongClick = { song ->
                                    onHomeSectionSongClick(HomeViewModel.SECTION_QUICK_PICKS, song)
                                },
                                onPlayAll = {
                                    onHomeSectionPlay(HomeViewModel.SECTION_QUICK_PICKS, false)
                                },
                                onShuffle = {
                                    onHomeSectionPlay(HomeViewModel.SECTION_QUICK_PICKS, true)
                                },
                                onSeeAllClick = {
                                    onHomeSectionSeeAll(HomeViewModel.SECTION_QUICK_PICKS)
                                }
                            )
                        }
                    }
    
                    // ═══════════════════════════════════════════
                    // 3️⃣ LISTEN AGAIN / TRENDING - 3x3 Grid
                    // Shows history for existing users,
                    // shows trending with proper title for new users
                    // ═══════════════════════════════════════════
                    val hasHistory = homeContent.listenAgain.isNotEmpty() 
                    val listenAgainGridSongs = if (hasHistory) {
                        homeContent.listenAgain.take(27)
                    } else {
                        homeContent.trending.take(27)
                    }
                    val listenAgainSectionKey = if (hasHistory) {
                        HomeViewModel.SECTION_LISTEN_AGAIN
                    } else {
                        HomeViewModel.SECTION_TRENDING
                    }
                    
                    if (listenAgainGridSongs.isNotEmpty()) {
                        item(key = "listen_again_grid") {
                            ListenAgainGrid(
                                songs = listenAgainGridSongs,
                                onSongClick = { song ->
                                    onHomeSectionSongClick(listenAgainSectionKey, song)
                                },
                                onPlayAll = { onHomeSectionPlay(listenAgainSectionKey, false) },
                                onShuffle = { onHomeSectionPlay(listenAgainSectionKey, true) },
                                title = if (hasHistory) "Listen Again" else "Trending",
                                onSeeAllClick = {
                                    onHomeSectionSeeAll(listenAgainSectionKey)
                                }
                            )
                        }
                    }
    
                    // ═══════════════════════════════════════════
                    // 4️⃣ QUICK PICKS - Horizontal Cards
                    // ═══════════════════════════════════════════
                    if (homeContent.quickPicks.isNotEmpty()) {
                        item(key = "quick_picks") {
                            QuickPicksSection(
                                songs = homeContent.quickPicks,
                                onSongClick = { song ->
                                    onHomeSectionSongClick(HomeViewModel.SECTION_QUICK_PICKS, song)
                                },
                                onPlayAll = {
                                    onHomeSectionPlay(HomeViewModel.SECTION_QUICK_PICKS, false)
                                },
                                onShuffle = {
                                    onHomeSectionPlay(HomeViewModel.SECTION_QUICK_PICKS, true)
                                },
                                onSeeAllClick = {
                                    onHomeSectionSeeAll(HomeViewModel.SECTION_QUICK_PICKS)
                                }
                            )
                        }
                    }
                    
                    // ═══════════════════════════════════════════
                    // 4.5️⃣ FOR YOU - Personalized Based on User Taste
                    // ═══════════════════════════════════════════
                    if (homeContent.personalizedForYou.isNotEmpty()) {
                        item(key = "personalized_for_you") {
                            SongSection(
                                title = "Made for You",
                                subtitle = "Based on your listening habits",
                                songs = homeContent.personalizedForYou,
                                onSongClick = { song ->
                                    onHomeSectionSongClick(HomeViewModel.SECTION_PERSONALIZED, song)
                                },
                                onPlayAll = {
                                    onHomeSectionPlay(HomeViewModel.SECTION_PERSONALIZED, false)
                                },
                                onShuffle = {
                                    onHomeSectionPlay(HomeViewModel.SECTION_PERSONALIZED, true)
                                },
                                onSeeAllClick = {
                                    onHomeSectionSeeAll(HomeViewModel.SECTION_PERSONALIZED)
                                },
                                cardStyle = CardStyle.MEDIUM_CARD
                            )
                        }
                    }
    
                    // ═══════════════════════════════════════════
                    // 5️⃣ TRENDING NOW
                    // ═══════════════════════════════════════════
                    if (homeContent.trending.isNotEmpty()) {
                        item(key = "trending") {
                            SongSection(
                                title = "Trending Now",
                                subtitle = if (!countryName.isNullOrBlank()) {
                                    "What's hot in $countryName"
                                } else {
                                    "What's hot near you"
                                },
                                songs = homeContent.trending,
                                onSongClick = { song ->
                                    onHomeSectionSongClick(HomeViewModel.SECTION_TRENDING, song)
                                },
                                onPlayAll = { onHomeSectionPlay(HomeViewModel.SECTION_TRENDING, false) },
                                onShuffle = { onHomeSectionPlay(HomeViewModel.SECTION_TRENDING, true) },
                                onSeeAllClick = {
                                    onHomeSectionSeeAll(HomeViewModel.SECTION_TRENDING)
                                },
                                cardStyle = CardStyle.COMPACT_ROW
                            )
                        }
                    }
    
                    // ═══════════════════════════════════════════
                    // 6️⃣ NEW RELEASES
                    // ═══════════════════════════════════════════
                    if (homeContent.newReleases.isNotEmpty()) {
                        item(key = "new_releases") {
                            SongSection(
                                title = "New Releases",
                                subtitle = "Fresh tracks just dropped",
                                songs = homeContent.newReleases,
                                onSongClick = { song ->
                                    onHomeSectionSongClick(HomeViewModel.SECTION_NEW_RELEASES, song)
                                },
                                onPlayAll = {
                                    onHomeSectionPlay(HomeViewModel.SECTION_NEW_RELEASES, false)
                                },
                                onShuffle = {
                                    onHomeSectionPlay(HomeViewModel.SECTION_NEW_RELEASES, true)
                                },
                                onSeeAllClick = {
                                    onHomeSectionSeeAll(HomeViewModel.SECTION_NEW_RELEASES)
                                },
                                cardStyle = CardStyle.MEDIUM_CARD
                            )
                        }
                    }
                    
                    // ═══════════════════════════════════════════
                    // 7️⃣ ENGLISH HITS
                    // ═══════════════════════════════════════════
                    if (homeContent.englishHits.isNotEmpty()) {
                        item(key = "english_hits") {
                            SongSection(
                                title = "English Hits",
                                subtitle = "Top international tracks",
                                songs = homeContent.englishHits,
                                onSongClick = { song ->
                                    onHomeSectionSongClick(HomeViewModel.SECTION_ENGLISH_HITS, song)
                                },
                                onPlayAll = {
                                    onHomeSectionPlay(HomeViewModel.SECTION_ENGLISH_HITS, false)
                                },
                                onShuffle = {
                                    onHomeSectionPlay(HomeViewModel.SECTION_ENGLISH_HITS, true)
                                },
                                onSeeAllClick = {
                                    onHomeSectionSeeAll(HomeViewModel.SECTION_ENGLISH_HITS)
                                },
                                cardStyle = CardStyle.LARGE_SQUARE
                            )
                        }
                    }
    
                    // ═══════════════════════════════════════════
                    // 7.5️⃣ FORGOTTEN FAVORITES
                    // ═══════════════════════════════════════════
                    if (homeContent.forgottenFavorites.isNotEmpty()) {
                        item(key = "forgotten_favorites") {
                            SongSection(
                                title = "Forgotten Favorites",
                                subtitle = "Rediscover songs you loved",
                                songs = homeContent.forgottenFavorites,
                                onSongClick = { song ->
                                    onHomeSectionSongClick(
                                        HomeViewModel.SECTION_FORGOTTEN_FAVORITES,
                                        song
                                    )
                                },
                                onPlayAll = {
                                    onHomeSectionPlay(HomeViewModel.SECTION_FORGOTTEN_FAVORITES, false)
                                },
                                onShuffle = {
                                    onHomeSectionPlay(HomeViewModel.SECTION_FORGOTTEN_FAVORITES, true)
                                },
                                onSeeAllClick = {
                                    onHomeSectionSeeAll(HomeViewModel.SECTION_FORGOTTEN_FAVORITES)
                                },
                                cardStyle = CardStyle.MEDIUM_CARD
                            )
                        }
                    }
    
                    // ═══════════════════════════════════════════
                    // ARTIST SECTIONS (Based on history)
                    // ═══════════════════════════════════════════
                    items(
                        items = homeContent.artists,
                        key = { artistSection -> artistSection.artist.id }
                    ) { artistSection ->
                        val artistSectionKey = HomeViewModel.artistSectionKey(artistSection.artist.id)
                        SongSection(
                            title = "More from ${artistSection.artist.name}",
                            subtitle = "Because you listened",
                            songs = artistSection.songs,
                            onSongClick = { song ->
                                onHomeSectionSongClick(artistSectionKey, song)
                            },
                            onPlayAll = { onHomeSectionPlay(artistSectionKey, false) },
                            onShuffle = { onHomeSectionPlay(artistSectionKey, true) },
                            onSeeAllClick = {
                                onHomeSectionSeeAll(artistSectionKey)
                            },
                            cardStyle = CardStyle.MEDIUM_CARD
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 1️⃣ HEADER COMPONENT - Greeting + Profile + Search
// ═══════════════════════════════════════════════════════════════

@Composable
private fun HomeHeader(
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val greeting = remember { getGreeting() }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = greeting,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "What do you want to listen to?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = onSearchClick) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private data class QuickAction(
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
private fun HomeQuickActions(
    onLikedSongsClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onPlaylistsClick: () -> Unit,
    onRecentlyPlayedClick: () -> Unit
) {

    val actions = remember(
        onLikedSongsClick,
        onDownloadsClick,
        onPlaylistsClick,
        onRecentlyPlayedClick
    ) {
        listOf(
            QuickAction("Liked", Icons.Rounded.Favorite, onLikedSongsClick),
            QuickAction("Downloads", Icons.Rounded.Download, onDownloadsClick),
            QuickAction("Playlists", Icons.AutoMirrored.Rounded.PlaylistPlay, onPlaylistsClick),
            QuickAction("Recent", Icons.Rounded.History, onRecentlyPlayedClick)
        )
    }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(actions, key = { it.title }) { action ->
            Surface(
                modifier = Modifier.clickable(onClick = action.onClick),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.title,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = action.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun getGreeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good Morning"
        in 12..16 -> "Good Afternoon"
        in 17..20 -> "Good Evening"
        else -> "Good Night"
    }
}

// ═══════════════════════════════════════════════════════════════
// 2️⃣ CONTINUE LISTENING - Horizontal Row (like YT Music)
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ContinueListeningSection(
    lastSong: Song,
    onSongClick: (Song) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Continue Listening",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )

        // Show only the single last played song
        ContinueListeningCard(
            song = lastSong,
            onClick = { onSongClick(lastSong) },
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun ForYouSection(
    songs: List<Song>,
    onSongClick: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onSeeAllClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "For You",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            // Play All and Shuffle buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSeeAllClick) {
                    Text(
                        text = "See all",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onShuffle,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                FilledIconButton(
                    onClick = onPlayAll,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play All",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(songs.take(10), key = { it.id }) { song ->
                QuickPickCard(
                    song = song,
                    onClick = { onSongClick(song) }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun ContinueListeningCard(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Album art thumbnail
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                SongThumbnail(
                    artworkUrl = song.thumbnailUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = song.title
                )
            }
            
            // Song info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Play button
            FilledIconButton(
                onClick = onClick,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 3️⃣ LISTEN AGAIN - 3x3 Grid with 3 Swipeable Pages (YT Music Style)
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListenAgainGrid(
    songs: List<Song>,
    onSongClick: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    title: String = "Listen Again",
    onSeeAllClick: () -> Unit
) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp
    
    // Dynamically calculate columns based on screen width
    val columns = when {
        screenWidth >= 840 -> 5 // Expanded (Tablet Landscape)
        screenWidth >= 600 -> 4 // Medium (Tablet Portrait / Foldable)
        else -> 3               // Compact (Phone)
    }
    val rowsCount = if (screenWidth >= 600) 2 else 3
    val songsPerPage = columns * rowsCount
    val pageCount = ((songs.size + songsPerPage - 1) / songsPerPage).coerceAtLeast(1)
    val pagerState = rememberPagerState(pageCount = { pageCount })

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Header with title, play buttons, and page indicators
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSeeAllClick) {
                    Text(
                        text = "See all",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // Play All and Shuffle buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onShuffle,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = "Shuffle",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onPlayAll,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Play All",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Page indicators
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    repeat(pageCount) { index ->
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (pagerState.currentPage == index)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                        )
                    }
                }
            }
        }
        
        // Swipeable pages with grids
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 16.dp
        ) { page ->
            val startIndex = page * songsPerPage
            val pageSongs = songs.drop(startIndex).take(songsPerPage)
            
            // Dynamic Grid layout chunks
            val rows = pageSongs.chunked(columns)
            
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rows.forEach { rowSongs ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowSongs.forEach { song ->
                            ListenAgainGridItem(
                                song = song,
                                onClick = { onSongClick(song) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // Fill remaining space if row is incomplete
                        repeat(columns - rowSongs.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                
                // Fill empty rows if page has less than required rows
                repeat(rowsCount - rows.size) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        repeat(columns) {
                            Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun ListenAgainGridItem(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.aspectRatio(1f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            SongThumbnail(
                artworkUrl = song.thumbnailUrl,
                modifier = Modifier.fillMaxSize(),
                contentDescription = song.title
            )
            
            // Dark gradient overlay for text visibility
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.7f)
                            ),
                            startY = 50f
                        )
                    )
            )
            
            // Song title at bottom
            Text(
                text = song.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 4️⃣ QUICK PICKS - Horizontal Cards
// ═══════════════════════════════════════════════════════════════

@Composable
private fun QuickPicksSection(
    songs: List<Song>,
    onSongClick: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onSeeAllClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Quick Picks",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Based on your listening",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Play All and Shuffle buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSeeAllClick) {
                    Text(
                        text = "See all",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onShuffle,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                FilledIconButton(
                    onClick = onPlayAll,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play All",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(songs.take(15), key = { it.id }) { song ->
                QuickPickCard(
                    song = song,
                    onClick = { onSongClick(song) }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun QuickPickCard(
    song: Song,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(140.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            // Album art
            SongThumbnail(
                artworkUrl = song.thumbnailUrl,
                modifier = Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                contentDescription = song.title
            )
            
            // Song info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// SONG SECTIONS WITH MULTIPLE CARD STYLES
// ═══════════════════════════════════════════════════════════════

enum class CardStyle {
    LARGE_SQUARE,    // Big 160x160 cards for Continue Listening
    MEDIUM_CARD,     // Medium 140x180 cards with more info
    COMPACT_ROW      // Horizontal compact list style
}

@Composable
private fun SongSection(
    title: String,
    subtitle: String?,
    songs: List<Song>,
    onSongClick: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onSeeAllClick: () -> Unit,
    cardStyle: CardStyle
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Section Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shuffle button
                IconButton(
                    onClick = onShuffle,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Play All button
                IconButton(
                    onClick = onPlayAll,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play All",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // See all button
                TextButton(onClick = onSeeAllClick) {
                    Text(
                        text = "See all",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Song Cards based on style
        when (cardStyle) {
            CardStyle.LARGE_SQUARE -> {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(songs.take(10), key = { it.id }) { song ->
                        LargeSongCard(song = song, onClick = { onSongClick(song) })
                    }
                }
            }
            
            CardStyle.MEDIUM_CARD -> {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(songs.take(12), key = { it.id }) { song ->
                        MediumSongCard(song = song, onClick = { onSongClick(song) })
                    }
                }
            }
            
            CardStyle.COMPACT_ROW -> {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    songs.take(8).forEach { song ->
                        CompactSongRow(song = song, onClick = { onSongClick(song) })
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ═══════════════════════════════════════════════════════════════
// SONG CARD COMPONENTS
// ═══════════════════════════════════════════════════════════════

/**
 * Large square card (160x160) for Continue Listening
 */
@Composable
private fun LargeSongCard(
    song: Song,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onClick)
    ) {
        // Album art with play button overlay
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(16.dp))
        ) {
            SongThumbnail(
                artworkUrl = song.thumbnailUrl,
                modifier = Modifier.fillMaxSize(),
                contentDescription = song.title
            )
            
            // Gradient overlay at bottom
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.3f)
                            ),
                            startY = 100f
                        )
                    )
            )
            
            // Play button
            FilledIconButton(
                onClick = onClick,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        Text(
            text = song.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground
        )
        
        Text(
            text = song.artist,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Medium card (140x180) for Recommendations
 */
@Composable
private fun MediumSongCard(
    song: Song,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
    ) {
        // Album art
        ElevatedCard(
            modifier = Modifier.size(140.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
        ) {
            SongThumbnail(
                artworkUrl = song.thumbnailUrl,
                modifier = Modifier.fillMaxSize(),
                contentDescription = song.title
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = song.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground
        )
        
        Text(
            text = song.artist,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Compact row style for Trending
 */
@Composable
private fun CompactSongRow(
    song: Song,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            SongThumbnail(
                artworkUrl = song.thumbnailUrl,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentDescription = song.title
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Song info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Duration
            if (song.duration > 0) {
                Text(
                    text = formatDuration(song.duration),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Play button
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return "$minutes:${secs.toString().padStart(2, '0')}"
}

// ═══════════════════════════════════════════════════════════════
// SHIMMER LOADING PLACEHOLDERS
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ShimmerSongSection() {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    )
    
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )
    
    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnim, y = translateAnim)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        // Title placeholder
        Box(
            modifier = Modifier
                .width(160.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(brush)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Subtitle placeholder
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(brush)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Song cards row placeholder
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(5) {
                ShimmerSongCard(brush)
            }
        }
    }
}

@Composable
private fun ShimmerSongCard(brush: Brush) {
    Column(
        modifier = Modifier.width(140.dp)
    ) {
        // Thumbnail placeholder
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(brush)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Title placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(brush)
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Artist placeholder
        Box(
            modifier = Modifier
                .width(80.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(brush)
        )
    }
}
