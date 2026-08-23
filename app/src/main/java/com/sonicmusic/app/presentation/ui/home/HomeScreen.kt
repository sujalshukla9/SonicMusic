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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.presentation.ui.theme.Dimens
import com.sonicmusic.app.presentation.ui.theme.LocalThumbnailShape
import com.sonicmusic.app.presentation.ui.theme.toComposeShape
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.presentation.ui.components.SongThumbnail
import com.sonicmusic.app.presentation.ui.components.ShimmerLoadingSection
import com.sonicmusic.app.presentation.ui.components.ShimmerCardStyle
import com.sonicmusic.app.presentation.viewmodel.HomeViewModel
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    onNavigateToArtist: (String, String?) -> Unit = { _, _ -> },
    onShowFullPlayer: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val homeContent by viewModel.homeContent.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val followedArtistIds by viewModel.followedArtistIds.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshResult by viewModel.refreshResult.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val countryName by viewModel.countryName.collectAsStateWithLifecycle()
    val selectedMoodGenre by viewModel.selectedMoodGenre.collectAsStateWithLifecycle()
    val isMoodFilterLoading by viewModel.isMoodFilterLoading.collectAsStateWithLifecycle()
    val moodFilteredSongs by viewModel.moodFilteredSongs.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val visibleKeys by remember {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
        }
    }

    val isScrollInProgress = listState.isScrollInProgress
    LaunchedEffect(visibleKeys, isScrollInProgress) {
        viewModel.onVisibleSectionsChanged(visibleKeys, isScrollInProgress)
    }

    // Infinite scroll continuation trigger when scrolled near bottom
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1
            totalItems > 0 && lastVisibleItemIndex >= totalItems - 3
        }.collect { shouldLoadMore ->
            if (shouldLoadMore) {
                viewModel.loadMoreHomeContent()
            }
        }
    }

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
        val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
        val downloadedSongIds by viewModel.downloadedSongIds.collectAsStateWithLifecycle()
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

        if (!isOnline && homeContent.listenAgain.isEmpty() && homeContent.quickPicks.isEmpty() && homeContent.trending.isEmpty()) {
            // No internet and no cached content
            com.sonicmusic.app.presentation.ui.components.NoInternetView(
                onRetry = { viewModel.loadHomeContent() },
                modifier = Modifier.padding(paddingValues)
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
                val listenAgainFiltered = homeContent.listenAgain
                val recentlyPlayedFiltered = homeContent.recentlyPlayed
                val quickPicksFiltered = homeContent.quickPicks
                val trendingFiltered = homeContent.trending
                val newReleasesFiltered = homeContent.newReleases
                val personalizedFiltered = homeContent.personalizedForYou
                val chartsFilteredSongs = homeContent.charts?.songs.orEmpty()
                val trendingAlbumsFiltered = homeContent.trendingAlbums
                val trendingArtistsFiltered = homeContent.trendingArtists
                val englishHitsFiltered = homeContent.englishHits
                val forgottenFavoritesFiltered = homeContent.forgottenFavorites
                val mixedForYouFiltered = homeContent.mixedForYou
                val artistsFiltered = homeContent.artists
                val continuationShelves = homeContent.continuationShelves

                LazyColumn(
                    state = listState,
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

                    // ═══════════════════════════════════════════
                    // MOODS & GENRES CHIPS (YTM Dynamic Filter Bar)
                    // ═══════════════════════════════════════════
                    if (homeContent.moodGenres.isNotEmpty()) {
                        item(key = "mood_genres_chips") {
                            MoodGenreChips(
                                moodGenres = homeContent.moodGenres,
                                selectedMoodGenre = selectedMoodGenre,
                                onMoodGenreClick = { moodGenre ->
                                    viewModel.onMoodGenreSelected(moodGenre)
                                }
                            )
                        }
                    }

                    // Active Mood Filter Songs Section (In-line YTM Mood filtering)
                    selectedMoodGenre?.let { mood ->
                        if (isMoodFilterLoading) {
                            item(key = "mood_shimmer") {
                                ShimmerLoadingSection(cardStyle = ShimmerCardStyle.MEDIUM_CARD)
                            }
                        } else if (moodFilteredSongs.isNotEmpty()) {
                            item(key = "mood_filter_section") {
                                SongSection(
                                    title = mood.title,
                                    subtitle = "Curated ${mood.title} tracks",
                                    songs = moodFilteredSongs,
                                    onSongClick = { song ->
                                        onHomeSectionSongClick("mood_filter", song)
                                    },
                                    onPlayAll = {
                                        onHomeSectionPlay("mood_filter", false)
                                    },
                                    onShuffle = {
                                        onHomeSectionPlay("mood_filter", true)
                                    },
                                    onSeeAllClick = {
                                        onHomeSectionSeeAll("mood_genre:${mood.title}|${mood.params}")
                                    },
                                    cardStyle = CardStyle.MEDIUM_CARD,
                                    isOnline = isOnline,
                                    downloadedSongIds = downloadedSongIds
                                )
                            }
                        }
                    }
                    
                    // ═══════════════════════════════════════════
                    // 2️⃣ CONTINUE LISTENING
                    // Shows single last-played song only (ViTune style)
                    // ═══════════════════════════════════════════
                    if (homeContent.listenAgain.isNotEmpty()) {
                        item(key = "continue_listening") {
                            val lastSong = homeContent.listenAgain.first()
                            val isSongEnabled = isOnline || lastSong.id in downloadedSongIds
                            ContinueListeningSection(
                                lastSong = lastSong,
                                isEnabled = isSongEnabled,
                                onSongClick = { song ->
                                    if (isSongEnabled) {
                                        onHomeSectionSongClick(HomeViewModel.SECTION_LISTEN_AGAIN, song)
                                    }
                                }
                            )
                        }
                    }

                    // ═══════════════════════════════════════════
                    // 3️⃣ LISTEN AGAIN - 3x3 Grid
                    // Shows history for existing users
                    // ═══════════════════════════════════════════
                    if (homeContent.listenAgain.isNotEmpty()) {
                        item(key = "listen_again_grid") {
                            ListenAgainGrid(
                                songs = listenAgainFiltered.take(27),
                                onSongClick = { song ->
                                    onHomeSectionSongClick(HomeViewModel.SECTION_LISTEN_AGAIN, song)
                                },
                                onPlayAll = { onHomeSectionPlay(HomeViewModel.SECTION_LISTEN_AGAIN, false) },
                                onShuffle = { onHomeSectionPlay(HomeViewModel.SECTION_LISTEN_AGAIN, true) },
                                title = "Listen Again",
                                onSeeAllClick = {
                                    onHomeSectionSeeAll(HomeViewModel.SECTION_LISTEN_AGAIN)
                                },
                                isOnline = isOnline,
                                downloadedSongIds = downloadedSongIds
                            )
                        }
                    }

                    // ═══════════════════════════════════════════
                    // 3.5️⃣ RECENTLY PLAYED
                    // ═══════════════════════════════════════════
                    if (recentlyPlayedFiltered.isNotEmpty()) {
                        item(key = "recently_played") {
                            SongSection(
                                title = "Recently Played",
                                subtitle = "Your recent listening history",
                                songs = recentlyPlayedFiltered,
                                onSongClick = { song ->
                                    onHomeSectionSongClick(HomeViewModel.SECTION_RECENTLY_PLAYED, song)
                                },
                                onPlayAll = {
                                    onHomeSectionPlay(HomeViewModel.SECTION_RECENTLY_PLAYED, false)
                                },
                                onShuffle = {
                                    onHomeSectionPlay(HomeViewModel.SECTION_RECENTLY_PLAYED, true)
                                },
                                onSeeAllClick = {
                                    onHomeSectionSeeAll(HomeViewModel.SECTION_RECENTLY_PLAYED)
                                },
                                cardStyle = CardStyle.MEDIUM_CARD,
                                isOnline = isOnline,
                                downloadedSongIds = downloadedSongIds
                            )
                        }
                    }

                    // Skeleton shimmer loader while initial content loads
                    if (quickPicksFiltered.isEmpty() && trendingFiltered.isEmpty() && newReleasesFiltered.isEmpty()) {
                        item(key = "shimmer_1") {
                            ShimmerLoadingSection(cardStyle = ShimmerCardStyle.MEDIUM_CARD)
                        }
                        item(key = "shimmer_2") {
                            ShimmerLoadingSection(cardStyle = ShimmerCardStyle.COMPACT_ROW)
                        }
                        item(key = "shimmer_3") {
                            ShimmerLoadingSection(cardStyle = ShimmerCardStyle.LARGE_SQUARE)
                        }
                    }

                    // ═══════════════════════════════════════════
                    // 4️⃣ QUICK PICKS - Horizontal Cards
                    // ═══════════════════════════════════════════
                    if (quickPicksFiltered.isNotEmpty()) {
                        item(key = "quick_picks") {
                            QuickPicksSection(
                                songs = quickPicksFiltered,
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
                                },
                                isOnline = isOnline,
                                downloadedSongIds = downloadedSongIds
                            )
                        }
                    }

                    // ═══════════════════════════════════════════
                    // 4.1️⃣ MADE FOR YOU
                    // ═══════════════════════════════════════════
                    if (personalizedFiltered.isNotEmpty()) {
                        item(key = "personalized_for_you") {
                            SongSection(
                                title = "Made for You",
                                subtitle = "Based on your listening habits",
                                songs = personalizedFiltered,
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
                                cardStyle = CardStyle.MEDIUM_CARD,
                                isOnline = isOnline,
                                downloadedSongIds = downloadedSongIds
                            )
                        }
                    }

                    // ═══════════════════════════════════════════
                    // 4.8️⃣ CHARTS
                    // ═══════════════════════════════════════════
                    homeContent.charts?.let { charts ->
                        if (chartsFilteredSongs.isNotEmpty()) {
                            item(key = "charts_section") {
                                SongSection(
                                    title = "Charts",
                                    subtitle = charts.country?.let { "Top hits in $it" } ?: "Top hits worldwide",
                                    songs = chartsFilteredSongs,
                                    onSongClick = { song ->
                                        onHomeSectionSongClick(HomeViewModel.SECTION_CHARTS, song)
                                    },
                                    onPlayAll = {
                                        onHomeSectionPlay(HomeViewModel.SECTION_CHARTS, false)
                                    },
                                    onShuffle = {
                                        onHomeSectionPlay(HomeViewModel.SECTION_CHARTS, true)
                                    },
                                    onSeeAllClick = {
                                        onHomeSectionSeeAll(HomeViewModel.SECTION_CHARTS)
                                    },
                                    cardStyle = CardStyle.COMPACT_ROW,
                                    isOnline = isOnline,
                                    downloadedSongIds = downloadedSongIds
                                )
                            }
                        }
                    }
      
                    // ═══════════════════════════════════════════
                    // 5️⃣ TRENDING NOW
                    // ═══════════════════════════════════════════
                    if (trendingFiltered.isNotEmpty()) {
                        item(key = "trending") {
                            SongSection(
                                title = "Trending Now",
                                subtitle = if (!countryName.isNullOrBlank()) {
                                    "What's hot in $countryName"
                                } else {
                                    "What's hot near you"
                                },
                                songs = trendingFiltered,
                                onSongClick = { song ->
                                    onHomeSectionSongClick(HomeViewModel.SECTION_TRENDING, song)
                                },
                                onPlayAll = { onHomeSectionPlay(HomeViewModel.SECTION_TRENDING, false) },
                                onShuffle = { onHomeSectionPlay(HomeViewModel.SECTION_TRENDING, true) },
                                onSeeAllClick = {
                                    onHomeSectionSeeAll(HomeViewModel.SECTION_TRENDING)
                                },
                                cardStyle = CardStyle.COMPACT_ROW,
                                isOnline = isOnline,
                                downloadedSongIds = downloadedSongIds
                            )
                        }
                    }
      
                    // ═══════════════════════════════════════════
                    // 6️⃣ NEW RELEASES
                    // ═══════════════════════════════════════════
                    if (newReleasesFiltered.isNotEmpty()) {
                        item(key = "new_releases") {
                            SongSection(
                                title = "New Releases",
                                subtitle = "Fresh tracks just dropped",
                                songs = newReleasesFiltered,
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
                                cardStyle = CardStyle.MEDIUM_CARD,
                                isOnline = isOnline,
                                downloadedSongIds = downloadedSongIds
                            )
                        }
                    }

                    // ═══════════════════════════════════════════
                    // 6.5️⃣ TRENDING ALBUMS
                    // ═══════════════════════════════════════════
                    if (trendingAlbumsFiltered.isNotEmpty()) {
                        item(key = "trending_albums") {
                            SongSection(
                                title = "Trending Albums",
                                subtitle = "Most popular albums right now",
                                songs = trendingAlbumsFiltered,
                                onSongClick = { song ->
                                    onHomeSectionSongClick(HomeViewModel.SECTION_TRENDING_ALBUMS, song)
                                },
                                onPlayAll = {
                                    onHomeSectionPlay(HomeViewModel.SECTION_TRENDING_ALBUMS, false)
                                },
                                onShuffle = {
                                    onHomeSectionPlay(HomeViewModel.SECTION_TRENDING_ALBUMS, true)
                                },
                                onSeeAllClick = {
                                    onHomeSectionSeeAll(HomeViewModel.SECTION_TRENDING_ALBUMS)
                                },
                                cardStyle = CardStyle.LARGE_SQUARE,
                                isOnline = isOnline,
                                downloadedSongIds = downloadedSongIds
                            )
                        }
                    }

                    // ═══════════════════════════════════════════
                    // 6.6️⃣ TRENDING ARTISTS (YTM Circular Avatars)
                    // ═══════════════════════════════════════════
                    if (trendingArtistsFiltered.isNotEmpty()) {
                        item(key = "trending_artists") {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Column(
                                    modifier = Modifier.widthIn(max = Dimens.maxContentWidth).fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = Dimens.screenPadding, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "Trending Artists",
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                            Text(
                                                text = "Artists topping the charts",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        TextButton(onClick = { onHomeSectionSeeAll(HomeViewModel.SECTION_TRENDING_ARTISTS) }) {
                                            Text(
                                                text = "See all",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    TrendingArtistRow(
                                        artists = trendingArtistsFiltered,
                                        followedArtistIds = followedArtistIds,
                                        onArtistClick = { artistSong ->
                                            val artistId = artistSong.id.takeIf { it.isNotBlank() } ?: artistSong.artistId
                                            val artistName = artistSong.artist.ifBlank { artistSong.title }
                                            onNavigateToArtist(artistName, artistId)
                                        },
                                        onFollowToggle = { artistSong ->
                                            viewModel.toggleFollowArtist(artistSong)
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(20.dp))
                                }
                            }
                        }
                    }
                    
                    // ═══════════════════════════════════════════
                    // 7️⃣ ENGLISH HITS
                    // ═══════════════════════════════════════════
                    if (englishHitsFiltered.isNotEmpty()) {
                        item(key = "english_hits") {
                            SongSection(
                                title = "English Hits",
                                subtitle = "Top international tracks",
                                songs = englishHitsFiltered,
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
                                cardStyle = CardStyle.LARGE_SQUARE,
                                isOnline = isOnline,
                                downloadedSongIds = downloadedSongIds
                            )
                        }
                    }

                    // ═══════════════════════════════════════════
                    // 7.5️⃣ FORGOTTEN FAVORITES
                    // ═══════════════════════════════════════════
                    if (forgottenFavoritesFiltered.isNotEmpty()) {
                        item(key = "forgotten_favorites") {
                            SongSection(
                                title = "Forgotten Favorites",
                                subtitle = "Rediscover songs you used to love",
                                songs = forgottenFavoritesFiltered,
                                onSongClick = { song ->
                                    onHomeSectionSongClick(HomeViewModel.SECTION_FORGOTTEN_FAVORITES, song)
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
                                cardStyle = CardStyle.MEDIUM_CARD,
                                isOnline = isOnline,
                                downloadedSongIds = downloadedSongIds
                            )
                        }
                    }

                    // ═══════════════════════════════════════════
                    // 8️⃣ MIXED FOR YOU
                    // ═══════════════════════════════════════════
                    if (mixedForYouFiltered.isNotEmpty()) {
                        item(key = "mixed_for_you") {
                            SongSection(
                                title = "Mixed For You",
                                subtitle = "Custom playlists built for you",
                                songs = mixedForYouFiltered,
                                onSongClick = { song ->
                                    onHomeSectionSongClick(HomeViewModel.SECTION_MIXED_FOR_YOU, song)
                                },
                                onPlayAll = {
                                    onHomeSectionPlay(HomeViewModel.SECTION_MIXED_FOR_YOU, false)
                                },
                                onShuffle = {
                                    onHomeSectionPlay(HomeViewModel.SECTION_MIXED_FOR_YOU, true)
                                },
                                onSeeAllClick = {
                                    onHomeSectionSeeAll(HomeViewModel.SECTION_MIXED_FOR_YOU)
                                },
                                cardStyle = CardStyle.MEDIUM_CARD,
                                isOnline = isOnline,
                                downloadedSongIds = downloadedSongIds
                            )
                        }
                    }
    
                    // ═══════════════════════════════════════════
                    // ARTIST SECTIONS (Based on history)
                    // ═══════════════════════════════════════════
                    items(
                        items = artistsFiltered,
                        key = { artistSection -> artistSection.artist.id },
                        contentType = { "artist_section" }
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
                            cardStyle = CardStyle.MEDIUM_CARD,
                            isOnline = isOnline,
                            downloadedSongIds = downloadedSongIds
                        )
                    }

                    // ═══════════════════════════════════════════
                    // CONTINUATION SHELVES (Infinite Home Feed)
                    // ═══════════════════════════════════════════
                    items(
                        items = continuationShelves,
                        key = { shelf -> "cont_shelf_${shelf.id}" },
                        contentType = { "continuation_shelf" }
                    ) { shelf ->
                        val shelfSectionKey = "continuation_${shelf.id}"
                        SongSection(
                            title = shelf.title,
                            subtitle = shelf.subtitle ?: "Recommended for you",
                            songs = shelf.songs,
                            onSongClick = { song ->
                                onHomeSectionSongClick(shelfSectionKey, song)
                            },
                            onPlayAll = { onHomeSectionPlay(shelfSectionKey, false) },
                            onShuffle = { onHomeSectionPlay(shelfSectionKey, true) },
                            onSeeAllClick = {
                                onHomeSectionSeeAll(shelfSectionKey)
                            },
                            cardStyle = CardStyle.MEDIUM_CARD,
                            isOnline = isOnline,
                            downloadedSongIds = downloadedSongIds
                        )
                    }

                    // Shimmer loader at bottom when loading continuation items
                    if (isLoadingMore) {
                        item(key = "continuation_shimmer") {
                            ShimmerLoadingSection(cardStyle = ShimmerCardStyle.MEDIUM_CARD)
                        }
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
    
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = Dimens.maxContentWidth)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Dimens.screenPadding)
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

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        LazyRow(
            modifier = Modifier.widthIn(max = Dimens.maxContentWidth).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Dimens.screenPadding, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(actions, key = { it.title }, contentType = { "action" }) { action ->
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
    isEnabled: Boolean = true,
    onSongClick: (Song) -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = Dimens.maxContentWidth).fillMaxWidth()
        ) {
            Text(
                text = "Continue Listening",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = Dimens.screenPadding, vertical = 12.dp)
            )

            // Show only the single last played song
            ContinueListeningCard(
                song = lastSong,
                onClick = { onSongClick(lastSong) },
                modifier = Modifier.padding(horizontal = Dimens.screenPadding),
                isEnabled = isEnabled
            )

            Spacer(modifier = Modifier.height(20.dp))
        }
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
            items(songs.take(10), key = { it.id }, contentType = { "song" }) { song ->
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
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true
) {
    val cardHeight = Dimens.continueListeningCardHeight
    val thumbSize = Dimens.continueListeningThumbSize
    Card(
        onClick = onClick,
        enabled = isEnabled,
        modifier = modifier
            .fillMaxWidth()
            .height(cardHeight)
            .alpha(if (isEnabled) 1f else 0.4f),
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
                    .size(thumbSize)
                    .clip(LocalThumbnailShape.current.toComposeShape())
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
                enabled = isEnabled,
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
    onSeeAllClick: () -> Unit,
    isOnline: Boolean = true,
    downloadedSongIds: Set<String> = emptySet()
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

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = Dimens.maxContentWidth).fillMaxWidth()
        ) {
            // Header with title, play buttons, and page indicators
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.screenPadding, vertical = 12.dp),
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
                contentPadding = PaddingValues(horizontal = Dimens.screenPadding),
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
                                val isSongEnabled = isOnline || song.id in downloadedSongIds
                                val gridItemClick: () -> Unit = {
                                    if (isSongEnabled) {
                                        onSongClick(song)
                                    }
                                }
                                ListenAgainGridItem(
                                    song = song,
                                    onClick = gridItemClick,
                                    modifier = Modifier.weight(1f),
                                    isEnabled = isSongEnabled
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
}

@Composable
private fun ListenAgainGridItem(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true
) {
    val currentShape = LocalThumbnailShape.current.toComposeShape()
    Card(
        onClick = onClick,
        enabled = isEnabled,
        modifier = modifier.aspectRatio(1f).alpha(if (isEnabled) 1f else 0.4f),
        shape = currentShape,
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
    onSeeAllClick: () -> Unit,
    isOnline: Boolean = true,
    downloadedSongIds: Set<String> = emptySet()
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = Dimens.maxContentWidth).fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.screenPadding, vertical = 12.dp),
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
                contentPadding = PaddingValues(horizontal = Dimens.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(songs.take(15), key = { it.id }, contentType = { "song" }) { song ->
                    val isSongEnabled = isOnline || song.id in downloadedSongIds
                    QuickPickCard(
                        song = song,
                        onClick = { if (isSongEnabled) onSongClick(song) },
                        isEnabled = isSongEnabled
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun QuickPickCard(
    song: Song,
    onClick: () -> Unit,
    isEnabled: Boolean = true
) {
    val cw = Dimens.cardWidth
    val currentShape = LocalThumbnailShape.current.toComposeShape()
    Card(
        onClick = onClick,
        enabled = isEnabled,
        modifier = Modifier.width(cw).alpha(if (isEnabled) 1f else 0.4f),
        shape = currentShape,
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
                    .size(cw)
                    .clip(currentShape),
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
    cardStyle: CardStyle,
    isOnline: Boolean = true,
    downloadedSongIds: Set<String> = emptySet()
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = Dimens.maxContentWidth).fillMaxWidth()
        ) {
            // Section Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.screenPadding, vertical = 8.dp),
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
                        contentPadding = PaddingValues(horizontal = Dimens.screenPadding),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(songs.take(10), key = { it.id }, contentType = { "song" }) { song ->
                            val isSongEnabled = isOnline || song.id in downloadedSongIds
                            LargeSongCard(song = song, onClick = { onSongClick(song) }, isEnabled = isSongEnabled)
                        }
                    }
                }
                
                CardStyle.MEDIUM_CARD -> {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Dimens.screenPadding),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(songs.take(12), key = { it.id }, contentType = { "song" }) { song ->
                            val isSongEnabled = isOnline || song.id in downloadedSongIds
                            MediumSongCard(song = song, onClick = { onSongClick(song) }, isEnabled = isSongEnabled)
                        }
                    }
                }
                
                CardStyle.COMPACT_ROW -> {
                    Column(
                        modifier = Modifier.padding(horizontal = Dimens.screenPadding),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        songs.take(8).forEach { song ->
                            val isSongEnabled = isOnline || song.id in downloadedSongIds
                            CompactSongRow(song = song, onClick = { onSongClick(song) }, isEnabled = isSongEnabled)
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
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
    onClick: () -> Unit,
    isEnabled: Boolean = true
) {
    val lcw = Dimens.largeCardWidth
    Column(
        modifier = Modifier
            .width(lcw)
            .alpha(if (isEnabled) 1f else 0.4f)
            .clickable(enabled = isEnabled, onClick = onClick)
    ) {
        // Album art with play button overlay
        Box(
            modifier = Modifier
                .size(lcw)
                .clip(LocalThumbnailShape.current.toComposeShape())
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
                enabled = isEnabled,
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
    onClick: () -> Unit,
    isEnabled: Boolean = true
) {
    val mcw = Dimens.cardWidth
    Column(
        modifier = Modifier
            .width(mcw)
            .alpha(if (isEnabled) 1f else 0.4f)
            .clickable(enabled = isEnabled, onClick = onClick)
    ) {
        // Album art
        val currentShape = LocalThumbnailShape.current.toComposeShape()
        ElevatedCard(
            modifier = Modifier.size(mcw),
            shape = currentShape,
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
    onClick: () -> Unit,
    isEnabled: Boolean = true
) {
    Card(
        onClick = onClick,
        enabled = isEnabled,
        modifier = Modifier.fillMaxWidth().alpha(if (isEnabled) 1f else 0.4f),
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
                    .clip(LocalThumbnailShape.current.toComposeShape()),
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
            IconButton(onClick = onClick, enabled = isEnabled) {
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
            items(5, contentType = { "shimmer" }) {
                ShimmerSongCard(brush)
            }
        }
    }
}

@Composable
private fun ShimmerSongCard(brush: Brush) {
    val cw = Dimens.cardWidth
    Column(
        modifier = Modifier.width(cw)
    ) {
        // Thumbnail placeholder
        Box(
            modifier = Modifier
                .size(cw)
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
