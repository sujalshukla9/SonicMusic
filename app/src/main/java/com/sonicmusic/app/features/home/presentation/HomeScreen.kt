package com.sonicmusic.app.features.home.presentation

import com.sonicmusic.app.theme.pressable
import com.sonicmusic.app.shared.components.animatedSection
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import com.sonicmusic.app.shared.components.pullrefresh.M3ExpressivePullToRefresh
import com.sonicmusic.app.shared.components.pullrefresh.LocalPullToRefreshOffset
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.sonicmusic.app.theme.pressScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.offset
import kotlin.math.roundToInt
import com.sonicmusic.app.theme.Dimens
import com.sonicmusic.app.theme.LocalThumbnailShape
import com.sonicmusic.app.theme.toComposeShape
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.sonicmusic.app.R
import com.sonicmusic.app.domain.model.CommunityPlaylist
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.HomeShelf
import com.sonicmusic.app.domain.model.HomeShelfItem
import com.sonicmusic.app.shared.components.SongCarouselCard
import com.sonicmusic.app.shared.components.ArtworkCardContent
import com.sonicmusic.app.shared.components.ExpressiveAlertDialog
import com.sonicmusic.app.shared.components.ExpressiveNotice
import com.sonicmusic.app.shared.components.ExpressiveSnackbarHost
import com.sonicmusic.app.shared.components.ReleaseAlbumCard
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.deviceLocalSongId
import com.sonicmusic.app.domain.model.ThumbnailShape
import com.sonicmusic.app.shared.components.SongThumbnail
import com.sonicmusic.app.features.player.presentation.sheets.SongActionSource
import com.sonicmusic.app.shared.components.songPressable
import com.sonicmusic.app.features.home.presentation.HomeViewModel
import com.sonicmusic.app.shared.components.SongCarousel
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    settingsViewModel: com.sonicmusic.app.features.settings.presentation.SettingsViewModel,
    onNavigateToSearch: () -> Unit = {},
    onNavigateToHomeSection: (String) -> Unit = {},
    onNavigateToLikedSongs: () -> Unit = {},
    onNavigateToDownloads: () -> Unit = {},
    onNavigateToPlaylists: () -> Unit = {},
    onNavigateToRecentlyPlayed: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToArtist: (String, String?) -> Unit = { _, _ -> },
    onNavigateToAlbum: (Song) -> Unit = {},
    onNavigateToPlaylist: (browseId: String, title: String?, creator: String?, thumb: String?) -> Unit = { _, _, _, _ -> },
    onShowFullPlayer: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val home by viewModel.homeUiState.collectAsStateWithLifecycle()
    val homeContent = home.content
    val recentlyPlayed = home.recentlyPlayed
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val refreshResult by viewModel.refreshResult.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val manualPagination by viewModel.manualPagination.collectAsStateWithLifecycle()
    val feedUnavailable by viewModel.feedUnavailable.collectAsStateWithLifecycle()
    val accountNotice by viewModel.accountNotice.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbarHostState = remember { SnackbarHostState() }
    val actionScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    LaunchedEffect(lifecycle, viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.loadHomeContent()
            viewModel.playbackCompleted.collect { viewModel.loadHomeContent() }
        }
    }
    LaunchedEffect(listState, viewModel) {
        try {
            snapshotFlow {
                listState.isScrollInProgress to (listState.firstVisibleItemIndex <= 1)
            }.collect { (scrolling, atTop) ->
                viewModel.onFeedScrollChanged(scrolling, atTop)
            }
        } finally {
            viewModel.onFeedScrollChanged(false, isAtTop = false)
        }
    }

    LaunchedEffect(listState, homeContent.continuation?.token, isRefreshing, isOnline, manualPagination, isLoadingMore) {
        if (homeContent.continuation == null || isRefreshing || !isOnline || manualPagination || isLoadingMore) {
            return@LaunchedEffect
        }
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: return@snapshotFlow false
            val scrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
            val fitsOnScreen = !listState.canScrollForward && !listState.canScrollBackward
            (scrolled || fitsOnScreen) && lastVisible >= layout.totalItemsCount - 3
        }.collect { nearEnd ->
            if (nearEnd) viewModel.loadMoreHomeContent()
        }
    }

    val onHomeSectionSongClick: (Song) -> Unit = { song ->
        actionScope.launch {
            if (viewModel.onSectionSongClick(song)) onShowFullPlayer()
        }
    }
    val onHomeSectionPlay: (String, Boolean) -> Unit = { sectionKey, shuffle ->
        actionScope.launch {
            if (viewModel.playSection(sectionKey, shuffle = shuffle)) onShowFullPlayer()
        }
    }
    val onHomeSectionSeeAll: (String) -> Unit = { sectionKey ->
        viewModel.onSectionSeeAll(sectionKey)?.let(onNavigateToHomeSection)
    }


    ShowHomeErrors(viewModel, snackbarHostState, offerRetry = true)

    val appUpdateState by settingsViewModel.appUpdateState.collectAsStateWithLifecycle()

    Scaffold(
        snackbarHost = { ExpressiveSnackbarHost(snackbarHostState, Modifier.padding(bottom = bottomPadding)) },
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0)
    ) { paddingValues ->
        var showUpdateDialog by androidx.compose.runtime.saveable.rememberSaveable(appUpdateState.latestVersion) {
            androidx.compose.runtime.mutableStateOf(true)
        }

        val latestVersion = appUpdateState.latestVersion
        if (appUpdateState.isUpdateAvailable && !appUpdateState.isDownloading && showUpdateDialog && latestVersion != null) {
            ExpressiveAlertDialog(
                icon = Icons.Rounded.CloudDownload,
                title = stringResource(R.string.update_alert_title),
                confirmLabel = stringResource(R.string.update_alert_action),
                dismissLabel = stringResource(R.string.update_alert_later),
                onDismiss = { showUpdateDialog = false },
                onConfirm = {
                    settingsViewModel.downloadUpdate()
                    showUpdateDialog = false
                }
            ) {
                Text(stringResource(R.string.update_alert_message, latestVersion))
            }
        }

        if (home.isLoading) {
            HomeFeedLoading(Modifier.fillMaxSize().padding(paddingValues).padding(bottom = bottomPadding))
            return@Scaffold
        }

        val hasRemoteFeed = homeContent.shelves.isNotEmpty()
            val downloadedSongIds = if (isOnline) emptySet() else {
                viewModel.downloadedSongIds.collectAsStateWithLifecycle().value
            }
            val savedPlaylistRemoteIds = viewModel.savedPlaylistRemoteIds.collectAsStateWithLifecycle()

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
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomPadding)
                ) {
                    animatedSection(key = "home_header", contentType = "header") {
                        HomeHeader(
                            onSearchClick = onNavigateToSearch,
                            onSettingsClick = onNavigateToSettings
                        )
                    }
                    animatedSection(key = "quick_actions", contentType = "quick_actions") {
                        HomeQuickActions(
                            onLikedSongsClick = onNavigateToLikedSongs,
                            onDownloadsClick = onNavigateToDownloads,
                            onPlaylistsClick = onNavigateToPlaylists,
                            onRecentlyPlayedClick = onNavigateToRecentlyPlayed
                        )
                    }

                    if (!isOnline && !hasRemoteFeed && recentlyPlayed.isEmpty()) {
                        animatedSection(key = "offline_notice", contentType = "offline_notice") {
                            com.sonicmusic.app.shared.components.NoInternetView(
                                onRetry = { viewModel.loadHomeContent() }
                            )
                        }
                    } else if (accountNotice != null || (feedUnavailable && !hasRemoteFeed)) {
                        item(key = "home_notice", contentType = "notice") {
                            val notice = accountNotice
                            val message = when {
                                notice == null -> R.string.home_feed_retry_message
                                notice.reconnect && notice.showingPublicFeed -> R.string.home_public_reconnect
                                notice.showingPublicFeed -> R.string.home_public_fallback
                                notice.reconnect -> R.string.home_account_reconnect
                                else -> R.string.home_account_unavailable
                            }
                            val reconnect = notice?.reconnect == true
                            val title = when {
                                reconnect -> R.string.home_session_notice_title
                                notice?.showingPublicFeed == true -> R.string.home_public_notice_title
                                else -> R.string.home_feed_unavailable
                            }
                            Box(
                                Modifier.fillMaxWidth().padding(horizontal = Dimens.screenPadding, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                ExpressiveNotice(
                                    icon = if (reconnect) Icons.Rounded.LinkOff else Icons.Rounded.SyncProblem,
                                    title = stringResource(title),
                                    message = stringResource(message),
                                    actionLabel = stringResource(if (reconnect) R.string.home_account_settings else R.string.home_retry),
                                    onAction = if (reconnect) onNavigateToSettings else viewModel::refreshHomeContent,
                                    modifier = Modifier.widthIn(max = Dimens.maxContentWidth)
                                )
                            }
                        }
                    }

                    val historySection = HomeViewModel.SECTION_RECENTLY_PLAYED
                    val hasListenAgain = homeContent.shelves.any { it.kind == HomeShelf.Kind.ListenAgain && it.items.any { item -> item.playableSong != null } }
                    val lastSong = recentlyPlayed.firstOrNull()
                    if (lastSong != null) {
                        animatedSection(key = "continue_listening", contentType = "continue_listening") {
                            ContinueListeningSection(
                                lastSong = lastSong,
                                isEnabled = isOnline || lastSong.id.deviceLocalSongId() != null || lastSong.id in downloadedSongIds,
                                onSongClick = onHomeSectionSongClick
                            )
                        }
                        animatedSection(key = historySection, contentType = "song_section") {
                            if (hasListenAgain) {
                                SongSection(
                                    title = stringResource(R.string.home_recently_played),
                                    subtitle = "Your recent listening history",
                                    songs = recentlyPlayed,
                                    onSongClick = onHomeSectionSongClick,
                                    onPlayAll = { onHomeSectionPlay(historySection, false) },
                                    onShuffle = { onHomeSectionPlay(historySection, true) },
                                    onSeeAllClick = { onHomeSectionSeeAll(historySection) },
                                    isOnline = isOnline,
                                    downloadedSongIds = downloadedSongIds
                                )
                            } else {
                                ListenAgainGrid(
                                    songs = recentlyPlayed,
                                    title = stringResource(R.string.home_listen_again),
                                    onSongClick = onHomeSectionSongClick,
                                    onPlayAll = { onHomeSectionPlay(historySection, false) },
                                    onShuffle = { onHomeSectionPlay(historySection, true) },
                                    onSeeAllClick = { onHomeSectionSeeAll(historySection) },
                                    isOnline = isOnline,
                                    downloadedSongIds = downloadedSongIds
                                )
                            }
                        }
                    }

                    for (shelf in homeContent.shelves) {
                        if (shelf.layout == HomeShelf.Layout.List && shelf.kind != HomeShelf.Kind.ForgottenFavorites &&
                            shelf.items.isNotEmpty() && shelf.items.all { it.playableSong != null }
                        ) {
                            item(key = shelf.id, contentType = "compact_shelf_header") {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Box(Modifier.widthIn(max = Dimens.maxContentWidth).fillMaxWidth()) {
                                        HomeShelfHeader(
                                            title = shelf.title,
                                            subtitle = shelf.subtitle,
                                            onPlayAll = { onHomeSectionPlay(shelf.id, false) },
                                            onShuffle = { onHomeSectionPlay(shelf.id, true) },
                                            onSeeAllClick = { onHomeSectionSeeAll(shelf.id) }
                                        )
                                    }
                                }
                            }
                            val previews = shelf.items.take(8)
                            items(previews, key = { "${shelf.id}/${it.key}" }, contentType = { "compact_song_row" }) { entry ->
                                val song = checkNotNull(entry.playableSong)
                                val available = isOnline || song.id.deviceLocalSongId() != null || song.id in downloadedSongIds
                                Box(
                                    Modifier.fillMaxWidth().padding(bottom = if (entry == previews.last()) 24.dp else 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CompactSongRow(
                                        song = song,
                                        onClick = { onHomeSectionSongClick(song) },
                                        modifier = Modifier.widthIn(max = Dimens.maxContentWidth)
                                            .fillMaxWidth().padding(horizontal = Dimens.screenPadding),
                                        isEnabled = available
                                    )
                                }
                            }
                            continue
                        }
                        item(key = shelf.id, contentType = shelf.layout) {
                            val songs = remember(shelf) { shelf.songs() }
                            val onSongClick = onHomeSectionSongClick
                            val onSeeAll = { onHomeSectionSeeAll(shelf.id) }
                            val onPlayAll: (() -> Unit)? = if (songs.isEmpty()) null else {
                                { onHomeSectionPlay(shelf.id, false) }
                            }
                            val onShuffle: (() -> Unit)? = if (songs.isEmpty()) null else {
                                { onHomeSectionPlay(shelf.id, true) }
                            }
                            val openPlaylist: (CommunityPlaylist) -> Unit = { playlist ->
                                onNavigateToPlaylist(playlist.browseId, playlist.title, playlist.creator, playlist.thumbnailUrl)
                            }

                            Box(Modifier.fillMaxWidth()) {
                                when {
                                    shelf.items.all { it is HomeShelfItem.Playlist } -> {
                                        val playlists = remember(shelf) {
                                            shelf.items.map { (it as HomeShelfItem.Playlist).playlist }
                                        }
                                        TrendingCommunityPlaylistsSection(
                                            title = shelf.title,
                                            subtitle = shelf.subtitle,
                                            playlists = playlists,
                                            onSeeAllClick = onSeeAll,
                                            savedPlaylistRemoteIds = savedPlaylistRemoteIds.value,
                                            onPlaylistClick = openPlaylist,
                                            onPlayPlaylist = { playlist, shuffle ->
                                                viewModel.playCommunityPlaylist(playlist, shuffle = shuffle)
                                                onShowFullPlayer()
                                            },
                                            onPlayPlaylistNext = viewModel::playCommunityPlaylistNext,
                                            onAddPlaylistToQueue = viewModel::addCommunityPlaylistToQueue,
                                            onToggleSavePlaylist = viewModel::toggleSaveCommunityPlaylist,
                                            onTrackClick = onSongClick,
                                            onTrackPlayNext = viewModel::playNext,
                                            onTrackAddToQueue = viewModel::addToQueue
                                        )
                                    }
                                    songs.isNotEmpty() && (shelf.kind == HomeShelf.Kind.ListenAgain ||
                                        (songs.size == shelf.items.size && shelf.layout == HomeShelf.Layout.Grid &&
                                            shelf.kind != HomeShelf.Kind.ForgottenFavorites)) -> {
                                        ListenAgainGrid(
                                            songs = remember(songs) { songs.take(27) },
                                            title = shelf.title,
                                            onSongClick = onSongClick,
                                            onPlayAll = { onHomeSectionPlay(shelf.id, false) },
                                            onShuffle = { onHomeSectionPlay(shelf.id, true) },
                                            onSeeAllClick = onSeeAll,
                                            isOnline = isOnline,
                                            downloadedSongIds = downloadedSongIds
                                        )
                                    }
                                    songs.size == shelf.items.size && shelf.kind != HomeShelf.Kind.ForgottenFavorites -> {
                                        SongSection(
                                            title = shelf.title,
                                            subtitle = shelf.subtitle,
                                            songs = songs,
                                            onSongClick = onSongClick,
                                            onPlayAll = onPlayAll,
                                            onShuffle = onShuffle,
                                            onSeeAllClick = onSeeAll,
                                            isOnline = isOnline,
                                            downloadedSongIds = downloadedSongIds
                                        )
                                    }
                                    else -> {
                                        HomeMediaShelf(
                                            shelf = shelf,
                                            onClick = { item ->
                                                when (item) {
                                                    is HomeShelfItem.Media -> when (item.song.contentType) {
                                                        ContentType.ALBUM -> onNavigateToAlbum(item.song)
                                                        ContentType.ARTIST -> onNavigateToArtist(item.song.title, item.song.artistId)
                                                        else -> onSongClick(item.song)
                                                    }
                                                    is HomeShelfItem.Playlist -> openPlaylist(item.playlist)
                                                }
                                            },
                                            onSeeAll = onSeeAll,
                                            onPlayAll = onPlayAll,
                                            onShuffle = onShuffle,
                                            isOnline = isOnline,
                                            downloadedSongIds = downloadedSongIds
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (isLoadingMore) {
                        item(key = "continuation_loader", contentType = "loader") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LoadingIndicator(modifier = Modifier.size(36.dp))
                            }
                        }
                    } else if (isOnline && homeContent.continuation != null) {
                        item(key = "continuation_action", contentType = "loader") {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                TextButton(onClick = { viewModel.loadMoreHomeContent(userRequested = true) }) {
                                    Text(stringResource(R.string.home_load_more))
                                }
                            }
                        }
                    }
                }
        }
    }
}

@Composable
internal fun ShowHomeErrors(
    viewModel: HomeViewModel,
    snackbarHostState: SnackbarHostState,
    offerRetry: Boolean = false
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val retryLabel = if (offerRetry) stringResource(R.string.home_retry) else null
    LaunchedEffect(lifecycle, viewModel, snackbarHostState, retryLabel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.error.filterNotNull().collectLatest { errorMessage ->
                val result = snackbarHostState.showSnackbar(errorMessage, retryLabel, duration = SnackbarDuration.Long)
                viewModel.clearError(errorMessage)
                if (result == SnackbarResult.ActionPerformed) viewModel.retryHomeError(errorMessage)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun HomeFeedLoading(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.home_loading)
    Box(modifier, contentAlignment = Alignment.Center) {
        LoadingIndicator(Modifier.semantics { contentDescription = description })
    }
}

// ═══════════════════════════════════════════════════════════════
// 1. DISCOVER HEADER AND SHORTCUTS
// ═══════════════════════════════════════════════════════════════

@Composable
private fun HomeHeader(
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val hour by produceState(initialValue = LocalTime.now().hour, key1 = lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = LocalTime.now().hour
                delay(60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
    }
    val greeting = stringResource(
        when (hour) {
            in 5..11 -> R.string.home_good_morning
            in 12..16 -> R.string.home_good_afternoon
            in 17..20 -> R.string.home_good_evening
            else -> R.string.home_good_night
        }
    )
    val pullOffset = LocalPullToRefreshOffset.current
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val colors = MaterialTheme.colorScheme
    val searchInteraction = remember { MutableInteractionSource() }
    val settingsInteraction = remember { MutableInteractionSource() }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = Dimens.maxContentWidth)
                .fillMaxWidth()
                .padding(horizontal = Dimens.screenPadding)
                .layout { measurable, constraints ->
                    val top = (statusBarTop.toPx() - pullOffset.value).coerceAtLeast(0f) + 16.dp.toPx()
                    val topPx = top.roundToInt().coerceAtMost(constraints.maxHeight)
                    val placeable = measurable.measure(constraints.offset(vertical = -topPx))
                    layout(placeable.width, constraints.constrainHeight(placeable.height + topPx)) {
                        placeable.placeRelative(0, topPx)
                    }
                }
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = greeting,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                FilledTonalIconButton(
                    onClick = onSettingsClick,
                    interactionSource = settingsInteraction,
                    modifier = Modifier.pressScale(settingsInteraction)
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                }
            }

            Surface(
                onClick = onSearchClick,
                interactionSource = searchInteraction,
                modifier = Modifier.fillMaxWidth().pressScale(searchInteraction, targetScale = 0.98f),
                shape = CircleShape,
                color = colors.surfaceContainerHigh,
                contentColor = colors.onSurfaceVariant
            ) {
                Row(
                    modifier = Modifier.heightIn(min = 54.dp).padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = "Search")
                    Text("Songs, artists, and albums", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun HomeQuickActions(
    onLikedSongsClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onPlaylistsClick: () -> Unit,
    onRecentlyPlayedClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = Dimens.maxContentWidth)
                .fillMaxWidth()
                .padding(horizontal = Dimens.screenPadding, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HomeShortcut(
                    title = "Liked songs",
                    icon = Icons.Rounded.Favorite,
                    onClick = onLikedSongsClick,
                    containerColor = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                HomeShortcut(
                    title = "Recents",
                    icon = Icons.Rounded.History,
                    onClick = onRecentlyPlayedClick,
                    containerColor = colors.secondaryContainer,
                    contentColor = colors.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HomeShortcut(
                    title = "Downloads",
                    icon = Icons.Rounded.Download,
                    onClick = onDownloadsClick,
                    containerColor = colors.surfaceContainerHigh,
                    contentColor = colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
                HomeShortcut(
                    title = "Playlists",
                    icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                    onClick = onPlaylistsClick,
                    containerColor = colors.surfaceContainerHigh,
                    contentColor = colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HomeShortcut(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier.pressScale(interaction),
        interactionSource = interaction,
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 52.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text = title, style = MaterialTheme.typography.labelLarge)
        }
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
                    contentDescription = song.title,
                    crossfade = false,
                    targetSizePx = with(LocalDensity.current) { thumbSize.roundToPx() }
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
    title: String,
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
    val maxRowsPerPage = if (screenWidth >= 600) 2 else 3
    val rowsCount = ((songs.size + columns - 1) / columns).coerceIn(1, maxRowsPerPage)
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
                    modifier = Modifier.weight(1f),
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
                    if (pageCount > 1) Row(
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
                val rows = remember(songs, page, songsPerPage, columns) {
                    val startIndex = (page * songsPerPage).coerceAtMost(songs.size)
                    songs.subList(startIndex, minOf(startIndex + songsPerPage, songs.size)).chunked(columns)
                }
                
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
                                val isSongEnabled = isOnline || song.id.deviceLocalSongId() != null || song.id in downloadedSongIds
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
    val thumbnailShape = LocalThumbnailShape.current
    val currentShape = remember(thumbnailShape) { thumbnailShape.toComposeShape() }
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
                contentDescription = song.title,
                crossfade = false
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
private fun HomeShelfHeader(
    title: String,
    subtitle: String?,
    onPlayAll: (() -> Unit)?,
    onShuffle: (() -> Unit)?,
    onSeeAllClick: () -> Unit,
    stackActions: Boolean = false
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.screenPadding, vertical = 8.dp)
    ) {
        val narrow = maxWidth < 320.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.25f
        val labels: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { heading() }
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val actions: @Composable () -> Unit = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shuffle button
                if (onShuffle != null) IconButton(
                    onClick = onShuffle,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Play All button
                if (onPlayAll != null) IconButton(
                    onClick = onPlayAll,
                    modifier = Modifier.size(44.dp)
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
        if ((narrow || stackActions) && (onPlayAll != null || onShuffle != null)) {
            Column(Modifier.fillMaxWidth()) {
                labels()
                Box(Modifier.align(Alignment.End)) { actions() }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.weight(1f)) { labels() }
                actions()
            }
        }
    }
}

@Composable
private fun HomeMediaShelf(
    shelf: HomeShelf,
    onClick: (HomeShelfItem) -> Unit,
    onSeeAll: () -> Unit,
    onPlayAll: (() -> Unit)?,
    onShuffle: (() -> Unit)?,
    isOnline: Boolean,
    downloadedSongIds: Set<String>
) {
    val cardWidth = Dimens.cardWidth
    val artworkSize = with(LocalDensity.current) { cardWidth.roundToPx() }
    val showTrackDetails = shelf.kind == HomeShelf.Kind.ForgottenFavorites
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = Dimens.maxContentWidth).fillMaxWidth()) {
            HomeShelfHeader(shelf.title, shelf.subtitle, onPlayAll, onShuffle, onSeeAll, stackActions = showTrackDetails)
            LazyRow(
                contentPadding = PaddingValues(horizontal = Dimens.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(shelf.items, key = { it.key }, contentType = { (it as? HomeShelfItem.Media)?.song?.contentType }) { item ->
                    val song = item.playableSong
                    val enabled = song == null || isOnline || song.id.deviceLocalSongId() != null || song.id in downloadedSongIds
                    if (song != null && showTrackDetails) {
                        Card(
                            modifier = Modifier.width(cardWidth)
                                .alpha(if (enabled) 1f else 0.4f)
                                .songPressable(song, SongActionSource.HOME, enabled = enabled, onClick = { onClick(item) }),
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            ArtworkCardContent(item.artwork, item.title, item.subtitle, artworkCrossfade = false, targetSizePx = artworkSize)
                        }
                    } else if (song != null) {
                        SongCarouselCard(
                            song = song,
                            onClick = { onClick(item) },
                            modifier = Modifier.width(cardWidth),
                            isEnabled = enabled,
                            artworkSizePx = artworkSize
                        )
                    } else if (item is HomeShelfItem.Media && item.song.contentType == ContentType.ALBUM) {
                        ReleaseAlbumCard(
                            album = item.song,
                            onClick = { onClick(item) },
                            modifier = Modifier.width(cardWidth),
                            artworkCrossfade = false
                        )
                    } else {
                        Card(
                            modifier = Modifier.width(cardWidth).clickable { onClick(item) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            SongThumbnail(
                                artworkUrl = item.artwork,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                crossfade = false,
                                targetSizePx = artworkSize
                            )
                            Column(Modifier.padding(8.dp)) {
                                Text(item.title, style = MaterialTheme.typography.labelLarge, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                                if (item.subtitle.isNotBlank()) {
                                    Text(item.subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}


@Composable
private fun SongSection(
    title: String,
    subtitle: String?,
    songs: List<Song>,
    onSongClick: (Song) -> Unit,
    onPlayAll: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    onSeeAllClick: () -> Unit,
    isOnline: Boolean = true,
    downloadedSongIds: Set<String> = emptySet()
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = Dimens.maxContentWidth).fillMaxWidth()
        ) {
            HomeShelfHeader(title, subtitle, onPlayAll, onShuffle, onSeeAllClick)
            SongCarousel(
                songs = songs,
                onSongClick = onSongClick,
                isOnline = isOnline,
                downloadedSongIds = downloadedSongIds,
                itemWidth = Dimens.cardWidth
            )

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
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true
) {
    Card(
        modifier = modifier
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
                contentDescription = song.title,
                crossfade = false,
                targetSizePx = with(LocalDensity.current) { 56.dp.roundToPx() }
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
    title: String,
    subtitle: String?,
    playlists: List<CommunityPlaylist>,
    onSeeAllClick: () -> Unit,
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

    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val cardWidth = minOf(320.dp, maxWidth - Dimens.screenPadding * 2 - 12.dp).coerceAtLeast(220.dp)
        Column(
            modifier = Modifier
                .widthIn(max = Dimens.maxContentWidth)
                .fillMaxWidth()
        ) {
            HomeShelfHeader(title, subtitle, onPlayAll = null, onShuffle = null, onSeeAllClick = onSeeAllClick)

            // Material 3 Expressive Uncontained Carousel
            val carouselState = rememberCarouselState { playlists.size }
            val cardShape = RoundedCornerShape(24.dp)

            HorizontalUncontainedCarousel(
                state = carouselState,
                itemWidth = cardWidth,
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
                    modifier = Modifier.size(82.dp)
                ) {
                    SongThumbnail(
                        artworkUrl = resolvedPlaylistCover,
                        shape = ThumbnailShape.ROUNDED_SQUARE,
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = playlist.title,
                        crossfade = false,
                        targetSizePx = with(LocalDensity.current) { 82.dp.roundToPx() }
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
            .pressable(onClick = onTrackClick)
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
            crossfade = false,
            targetSizePx = with(LocalDensity.current) { 38.dp.roundToPx() }
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

