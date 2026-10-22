package com.sonicmusic.app.features.home.presentation

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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import com.sonicmusic.app.shared.components.pullrefresh.M3ExpressivePullToRefresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.sonicmusic.app.theme.MotionTokens
import com.sonicmusic.app.theme.pressScale
import com.sonicmusic.app.theme.pressable
import com.sonicmusic.app.theme.shouldReduceMotion
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.theme.Dimens
import com.sonicmusic.app.theme.LocalThumbnailShape
import com.sonicmusic.app.theme.toComposeShape
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.domain.model.CommunityPlaylist
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.ThumbnailShape
import com.sonicmusic.app.shared.components.SongThumbnail
import com.sonicmusic.app.features.player.presentation.sheets.SongActionSource
import com.sonicmusic.app.shared.components.songPressable
import com.sonicmusic.app.features.home.presentation.HomeViewModel
import com.sonicmusic.app.shared.components.SongCarousel
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
    onNavigateToPlaylist: (browseId: String, title: String?, creator: String?, thumb: String?) -> Unit = { _, _, _, _ -> },
    onShowFullPlayer: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val homeContent by viewModel.homeContent.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshResult by viewModel.refreshResult.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
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
            if (shouldLoadMore && !isRefreshing) {
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

    val settingsViewModel: com.sonicmusic.app.features.settings.presentation.SettingsViewModel = hiltViewModel()
    val appUpdateState by settingsViewModel.appUpdateState.collectAsStateWithLifecycle()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0)
    ) { paddingValues ->
        val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
        val downloadedSongIds by viewModel.downloadedSongIds.collectAsStateWithLifecycle()
        val savedPlaylistRemoteIds by viewModel.savedPlaylistRemoteIds.collectAsStateWithLifecycle()
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

        var hasLoadedInitially by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

        val hasOnlineHomeContent = homeContent.quickPicks.isNotEmpty() || 
            (homeContent.charts?.songs?.isNotEmpty() == true) ||
            homeContent.listenAgain.isNotEmpty() ||
            homeContent.englishHits.isNotEmpty() ||
            homeContent.trendingAlbums.isNotEmpty() ||
            homeContent.mixedForYou.isNotEmpty() ||
            homeContent.forgottenFavorites.isNotEmpty() ||
            homeContent.communityPlaylists.isNotEmpty() ||
            homeContent.continuationShelves.isNotEmpty()

        if (hasOnlineHomeContent) {
            hasLoadedInitially = true
        }

        val isInitialLoading = !hasLoadedInitially && !isRefreshing && isOnline && (isLoading && !hasOnlineHomeContent) && error == null

        Crossfade(
            targetState = isInitialLoading,
            animationSpec = MotionTokens.standardTween(),
            label = "home_loading_crossfade"
        ) { loading ->
            if (loading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (!isOnline && homeContent.listenAgain.isEmpty() && homeContent.quickPicks.isEmpty() && homeContent.englishHits.isEmpty()) {
                // No internet and no cached content
                com.sonicmusic.app.shared.components.NoInternetView(
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
                    sheetBackgroundColor = MaterialTheme.colorScheme.background,
                    backdropColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                val listenAgainFiltered = remember(homeContent.listenAgain) {
                    homeContent.listenAgain.filter { it.title.isNotBlank() }
                }
                val recentlyPlayedFiltered = remember(homeContent.recentlyPlayed) {
                    homeContent.recentlyPlayed.filter { it.title.isNotBlank() }
                }
                val quickPicksFiltered = remember(homeContent.quickPicks) {
                    homeContent.quickPicks.filter { com.sonicmusic.app.data.remote.source.InnertubeConfig.isAuthenticSong(it.title, it.artist, it.duration) }
                }
                val chartsFilteredSongs = remember(homeContent.charts) {
                    homeContent.charts?.songs.orEmpty().filter { com.sonicmusic.app.data.remote.source.InnertubeConfig.isAuthenticSong(it.title, it.artist, it.duration) }
                }
                val trendingAlbumsFiltered = remember(homeContent.trendingAlbums) {
                    homeContent.trendingAlbums.filter { it.title.isNotBlank() }
                }
                val englishHitsFiltered = remember(homeContent.englishHits) {
                    homeContent.englishHits
                        .filter { com.sonicmusic.app.data.remote.source.InnertubeConfig.isAuthenticSong(it.title, it.artist, it.duration) }
                        .distinctBy { it.id }
                }
                val forgottenFavoritesFiltered = remember(homeContent.forgottenFavorites) {
                    homeContent.forgottenFavorites.filter { com.sonicmusic.app.data.remote.source.InnertubeConfig.isAuthenticSong(it.title, it.artist, it.duration) }
                }
                val mixedForYouFiltered = remember(homeContent.mixedForYou) {
                    homeContent.mixedForYou.filter { com.sonicmusic.app.data.remote.source.InnertubeConfig.isAuthenticSong(it.title, it.artist, it.duration) }
                }
                val artistsFiltered = remember(homeContent.artists) {
                    homeContent.artists.map { section ->
                        section.copy(songs = section.songs.filter { com.sonicmusic.app.data.remote.source.InnertubeConfig.isAuthenticSong(it.title, it.artist, it.duration) })
                    }.filter { it.songs.isNotEmpty() }
                }
                val continuationShelves = remember(homeContent.continuationShelves) {
                    homeContent.continuationShelves.map { shelf ->
                        shelf.copy(songs = shelf.songs.filter { com.sonicmusic.app.data.remote.source.InnertubeConfig.isAuthenticSong(it.title, it.artist, it.duration) })
                    }.filter { it.songs.isNotEmpty() }
                }
                val communityPlaylistsFiltered = remember(homeContent.communityPlaylists) {
                    homeContent.communityPlaylists.filter { it.title.isNotBlank() }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomPadding)
                ) {
                    // ═══════════════════════════════════════════
                    // 1. HEADER - Greeting + Search + Settings
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
                    // 2. CONTINUE LISTENING
                    // Shows single last-played song only (ViTune style)
                    // ═══════════════════════════════════════════
                    if (listenAgainFiltered.isNotEmpty()) {
                        item(key = "continue_listening") {
                            val lastSong = listenAgainFiltered.first()
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
                    // 3. LISTEN AGAIN - 3x3 Grid
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
                    // 3.5. RECENTLY PLAYED
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

                    // ═══════════════════════════════════════════
                    // 4. QUICK PICKS - Horizontal Cards
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
                    // 4.8. CHARTS
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
                    // 6.6. TRENDING ALBUMS
                    // ═══════════════════════════════════════════
                    if (trendingAlbumsFiltered.isNotEmpty()) {
                        item(key = "trending_albums") {
                            SongSection(
                                title = "Trending Albums",
                                subtitle = "Top albums right now",
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
                                cardStyle = CardStyle.MEDIUM_CARD,
                                isOnline = isOnline,
                                downloadedSongIds = downloadedSongIds
                            )
                        }
                    }


                    // ═══════════════════════════════════════════
                    // 7. ENGLISH HITS
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
                                cardStyle = CardStyle.MEDIUM_CARD,
                                isOnline = isOnline,
                                downloadedSongIds = downloadedSongIds
                            )
                        }
                    }

                    // ═══════════════════════════════════════════
                    // 7.5. FORGOTTEN FAVORITES
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
                    // 8. MIXED FOR YOU
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
                    // 9. ARTIST SONG SECTIONS ("More from {Artist}")
                    // ═══════════════════════════════════════════
                    items(
                        items = artistsFiltered,
                        key = { artistSection -> artistSection.artist.id },
                        contentType = { "artist_section" }
                    ) { artistSection ->
                        val artistSectionKey = HomeViewModel.artistSectionKey(artistSection.artist.id)
                        val canonicalBrowseId = artistSection.artist.id.takeIf { it.startsWith("UC") }
                        SongSection(
                            title = "More from ${artistSection.artist.name}",
                            subtitle = "Because you listened",
                            songs = artistSection.songs,
                            onTitleClick = {
                                onNavigateToArtist(artistSection.artist.name, canonicalBrowseId)
                            },
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

                    // ═══════════════════════════════════════════
                    // 10. TRENDING COMMUNITY PLAYLISTS
                    // ═══════════════════════════════════════════
                    if (communityPlaylistsFiltered.isNotEmpty()) {
                        item(key = "trending_community_playlists") {
                            TrendingCommunityPlaylistsSection(
                                playlists = communityPlaylistsFiltered,
                                savedPlaylistRemoteIds = savedPlaylistRemoteIds,
                                onPlaylistClick = { playlist ->
                                    onNavigateToPlaylist(
                                        playlist.browseId,
                                        playlist.title,
                                        playlist.creator,
                                        playlist.thumbnailUrl
                                    )
                                },
                                onPlayPlaylist = { playlist, shuffle ->
                                    viewModel.playCommunityPlaylist(playlist, shuffle = shuffle)
                                    onShowFullPlayer()
                                },
                                onPlayPlaylistNext = { playlist ->
                                    viewModel.playCommunityPlaylistNext(playlist)
                                },
                                onAddPlaylistToQueue = { playlist ->
                                    viewModel.addCommunityPlaylistToQueue(playlist)
                                },
                                onToggleSavePlaylist = { playlist ->
                                    viewModel.toggleSaveCommunityPlaylist(playlist)
                                },
                                onTrackClick = { song ->
                                    viewModel.onSongClick(song)
                                    onShowFullPlayer()
                                },
                                onTrackPlayNext = { song ->
                                    viewModel.playNext(song)
                                },
                                onTrackAddToQueue = { song ->
                                    viewModel.addToQueue(song)
                                }
                            )
                        }
                    }

                    // Material 3 Expressive loading indicator at bottom when loading continuation items
                    if (isLoadingMore) {
                        item(key = "continuation_loader") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LoadingIndicator(
                                    modifier = Modifier.size(36.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
}

// ═══════════════════════════════════════════════════════════════
// 1. HEADER COMPONENT - Greeting + Profile + Search
// ═══════════════════════════════════════════════════════════════

@Composable
private fun HomeHeader(
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val greeting = remember { getGreeting() }
    val pullOffset = com.sonicmusic.app.shared.components.pullrefresh.LocalPullToRefreshOffset.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val statusBarTopPx = with(density) { statusBarTop.toPx() }
    val effectiveTopInset = with(density) {
        ((statusBarTopPx - pullOffset).coerceAtLeast(0f)).toDp()
    }
    
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = Dimens.maxContentWidth)
                .fillMaxWidth()
                .padding(horizontal = Dimens.screenPadding)
                .padding(top = effectiveTopInset + 12.dp, bottom = 8.dp)
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
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "What do you want to listen to?",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
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
                    modifier = Modifier.pressable(onClick = action.onClick, targetScale = 0.95f),
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
// 2. CONTINUE LISTENING - Horizontal Row (like YT Music)
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

        SongCarousel(
            songs = songs.take(10),
            onSongClick = onSongClick,
            contentPadding = PaddingValues(horizontal = Dimens.screenPadding),
            itemWidth = Dimens.cardWidth
        )

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
        modifier = modifier
            .fillMaxWidth()
            .height(cardHeight)
            .alpha(if (isEnabled) 1f else 0.4f)
            .songPressable(
                song = song,
                source = SongActionSource.HOME,
                enabled = isEnabled,
                onClick = onClick
            ),
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
// 3. LISTEN AGAIN - 3x3 Grid with 3 Swipeable Pages (YT Music Style)
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
        modifier = modifier
            .aspectRatio(1f)
            .alpha(if (isEnabled) 1f else 0.4f)
            .songPressable(
                song = song,
                source = SongActionSource.HOME,
                enabled = isEnabled,
                onClick = onClick
            ),
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
// 4. QUICK PICKS - Horizontal Cards
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

            SongCarousel(
                songs = songs.take(15),
                onSongClick = onSongClick,
                isOnline = isOnline,
                downloadedSongIds = downloadedSongIds,
                itemWidth = Dimens.cardWidth
            )

            Spacer(modifier = Modifier.height(20.dp))
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
    downloadedSongIds: Set<String> = emptySet(),
    onTitleClick: (() -> Unit)? = null
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
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier)
                ) {
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
                CardStyle.LARGE_SQUARE,
                CardStyle.MEDIUM_CARD -> {
                    SongCarousel(
                        songs = songs.take(15),
                        onSongClick = onSongClick,
                        isOnline = isOnline,
                        downloadedSongIds = downloadedSongIds,
                        itemWidth = Dimens.cardWidth
                    )
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
 * Compact row style for Trending
 */
@Composable
private fun CompactSongRow(
    song: Song,
    onClick: () -> Unit,
    isEnabled: Boolean = true
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isEnabled) 1f else 0.4f)
            .songPressable(
                song = song,
                source = SongActionSource.HOME,
                enabled = isEnabled,
                onClick = onClick
            ),
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
// 10. TRENDING COMMUNITY PLAYLISTS (Material 3 Expressive Design)
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrendingCommunityPlaylistsSection(
    playlists: List<CommunityPlaylist>,
    savedPlaylistRemoteIds: Set<String>,
    onPlaylistClick: (CommunityPlaylist) -> Unit,
    onPlayPlaylist: (CommunityPlaylist, Boolean) -> Unit,
    onPlayPlaylistNext: (CommunityPlaylist) -> Unit,
    onAddPlaylistToQueue: (CommunityPlaylist) -> Unit,
    onToggleSavePlaylist: (CommunityPlaylist) -> Unit,
    onTrackClick: (Song) -> Unit,
    onTrackPlayNext: (Song) -> Unit,
    onTrackAddToQueue: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    if (playlists.isEmpty()) return

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = Dimens.maxContentWidth)
                .fillMaxWidth()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.screenPadding, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Trending Community Playlists",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Curated collections by music lovers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Material 3 Expressive Uncontained Carousel
            val carouselState = rememberCarouselState { playlists.size }
            val cardShape = RoundedCornerShape(24.dp)

            HorizontalUncontainedCarousel(
                state = carouselState,
                itemWidth = 340.dp,
                itemSpacing = 16.dp,
                contentPadding = PaddingValues(horizontal = Dimens.screenPadding),
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
            ) { index ->
                val playlist = playlists[index]
                val isSaved = remember(savedPlaylistRemoteIds, playlist) {
                    playlist.id in savedPlaylistRemoteIds ||
                    playlist.browseId in savedPlaylistRemoteIds ||
                    playlist.id.removePrefix("VL") in savedPlaylistRemoteIds
                }
                key("${playlist.id}_$index") {
                    TrendingCommunityPlaylistCard(
                        playlist = playlist,
                        isSaved = isSaved,
                        onCardClick = { onPlaylistClick(playlist) },
                        onPlayClick = { onPlayPlaylist(playlist, false) },
                        onShuffleClick = { onPlayPlaylist(playlist, true) },
                        onSaveClick = { onToggleSavePlaylist(playlist) },
                        onPlayNextClick = { onPlayPlaylistNext(playlist) },
                        onAddToQueueClick = { onAddPlaylistToQueue(playlist) },
                        onTrackClick = onTrackClick,
                        onTrackPlayNext = onTrackPlayNext,
                        onTrackAddToQueue = onTrackAddToQueue,
                        shape = cardShape,
                        modifier = Modifier
                            .maskClip(cardShape)
                            .fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrendingCommunityPlaylistCard(
    playlist: CommunityPlaylist,
    isSaved: Boolean,
    onCardClick: () -> Unit,
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onSaveClick: () -> Unit,
    onPlayNextClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onTrackClick: (Song) -> Unit,
    onTrackPlayNext: (Song) -> Unit,
    onTrackAddToQueue: (Song) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp)
) {
    val resolvedPlaylistCover = if (!playlist.thumbnailUrl.isNullOrBlank()) {
        playlist.thumbnailUrl
    } else {
        playlist.previewTracks.firstOrNull()?.thumbnailUrl
            ?: playlist.previewTracks.firstOrNull()?.id?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
    }
    val interactionSource = remember { MutableInteractionSource() }
    val colorScheme = MaterialTheme.colorScheme
    var showOverflowMenu by remember { mutableStateOf(false) }

    ElevatedCard(
        onClick = onCardClick,
        interactionSource = interactionSource,
        modifier = modifier
            .widthIn(max = 340.dp)
            .pressScale(interactionSource),
        shape = shape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 4.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row: Prominent square cover artwork on the left, details & overflow on the right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Square cover / collage artwork on the left
                Box(
                    modifier = Modifier.size(108.dp)
                ) {
                    SongThumbnail(
                        artworkUrl = resolvedPlaylistCover,
                        shape = ThumbnailShape.ROUNDED_SQUARE,
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = playlist.title,
                        targetSizePx = 320
                    )

                    // Playlist badge (top-start)
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = "Playlist",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Title, Creator, Metadata, Overflow Menu
                Column(
                    modifier = Modifier
                        .weight(1f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        // Playlist Title
                        Text(
                            text = playlist.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        // Trailing overflow menu
                        Box {
                            IconButton(
                                onClick = { showOverflowMenu = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreVert,
                                    contentDescription = "Actions for ${playlist.title}",
                                    tint = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Play next") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.PlaylistPlay,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onPlayNextClick()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Add to queue") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.QueueMusic,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onAddToQueueClick()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (isSaved) "Saved to library" else "Save playlist") },
                                    leadingIcon = {
                                        Icon(
                                            if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onSaveClick()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Open playlist") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.QueueMusic,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onCardClick()
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Creator Name
                    Text(
                        text = playlist.creator,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Track count and View count metadata (ONLY when actual metadata is available!)
                    val trackLabel = playlist.trackCountText
                        ?: playlist.trackCount?.let { "$it tracks" }
                    val viewLabel = playlist.viewCountText

                    if (!trackLabel.isNullOrBlank() || !viewLabel.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (!trackLabel.isNullOrBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
                                    contentColor = colorScheme.onSurfaceVariant
                                ) {
                                    Text(
                                        text = trackLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            if (!viewLabel.isNullOrBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
                                    contentColor = colorScheme.onSurfaceVariant
                                ) {
                                    Text(
                                        text = viewLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Compact preview of up to three tracks (if available)
            if (playlist.previewTracks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colorScheme.surfaceContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        playlist.previewTracks.take(3).forEach { track ->
                            TrackPreviewRow(
                                song = track,
                                onTrackClick = { onTrackClick(track) },
                                onPlayNext = { onTrackPlayNext(track) },
                                onAddToQueue = { onTrackAddToQueue(track) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Play, shuffle, and save/library actions along the bottom
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play and Shuffle on the left
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Play Button
                    Button(
                        onClick = onPlayClick,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorScheme.primary,
                            contentColor = colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Play",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Shuffle Button
                    FilledTonalIconButton(
                        onClick = onShuffleClick,
                        shape = RoundedCornerShape(12.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = colorScheme.secondaryContainer,
                            contentColor = colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = "Shuffle playlist ${playlist.title}",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Save / Library Action Button on the right
                FilledTonalIconButton(
                    onClick = onSaveClick,
                    shape = RoundedCornerShape(12.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isSaved) colorScheme.primaryContainer else colorScheme.surfaceContainerHighest.copy(alpha = 0.8f),
                        contentColor = if (isSaved) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        contentDescription = if (isSaved) "Remove from library" else "Save playlist to library",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackPreviewRow(
    song: Song,
    onTrackClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var showTrackMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onTrackClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Small square thumbnail
        val resolvedTrackThumb = if (!song.thumbnailUrl.isNullOrBlank()) {
            song.thumbnailUrl
        } else if (song.id.isNotBlank()) {
            "https://i.ytimg.com/vi/${song.id}/mqdefault.jpg"
        } else null

        SongThumbnail(
            artworkUrl = resolvedTrackThumb,
            shape = ThumbnailShape.ROUNDED_SQUARE,
            modifier = Modifier.size(38.dp),
            contentDescription = song.title,
            targetSizePx = 120
        )

        Spacer(modifier = Modifier.width(10.dp))

        // Track title & Artist name
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Track Overflow Menu
        Box {
            IconButton(
                onClick = { showTrackMenu = true },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Actions for ${song.title}",
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            DropdownMenu(
                expanded = showTrackMenu,
                onDismissRequest = { showTrackMenu = false },
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                DropdownMenuItem(
                    text = { Text("Play next") },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Rounded.PlaylistPlay,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        showTrackMenu = false
                        onPlayNext()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Add to queue") },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        showTrackMenu = false
                        onAddToQueue()
                    }
                )
            }
        }
    }
}

