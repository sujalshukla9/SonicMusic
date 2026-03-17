package com.sonicmusic.app.presentation.ui.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MicExternalOn
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.presentation.ui.components.SongThumbnail
import com.sonicmusic.app.presentation.state.PaginationState
import com.sonicmusic.app.presentation.state.SearchAction
import com.sonicmusic.app.presentation.state.SearchEffect
import com.sonicmusic.app.presentation.state.SearchState
import com.sonicmusic.app.presentation.viewmodel.SearchViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun SearchScreen(
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onShowFullPlayer: () -> Unit = {},
    onNavigateToArtist: (String, String?) -> Unit = { _, _ -> },
    onNavigateToAlbum: (com.sonicmusic.app.domain.model.Song) -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val trendingSearches by viewModel.trendingSearches.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // Handle side effects
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is SearchEffect.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is SearchEffect.ClearFocus -> {
                    focusManager.clearFocus()
                }
                is SearchEffect.NavigateToPlayer -> {
                    onShowFullPlayer()
                }
                is SearchEffect.ScrollToTop -> {
                    listState.scrollToItem(0)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            // Search Bar
            SearchBarSection(
                query = searchQuery,
                onQueryChange = { viewModel.onAction(SearchAction.QueryChanged(it)) },
                onClear = { viewModel.onAction(SearchAction.ClearSearch) },
                onSearch = {
                    viewModel.onAction(SearchAction.SubmitSearch(searchQuery))
                    focusManager.clearFocus()
                },
            )

            // Content
            val isOnline = com.sonicmusic.app.presentation.ui.components.LocalIsOnline.current
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    com.sonicmusic.app.presentation.ui.components.NoInternetBanner(isVisible = !isOnline)
                AnimatedContent(
                    targetState = searchState,
                    contentKey = { it::class },
                    transitionSpec = {
                        fadeIn(animationSpec = tween(300)) togetherWith
                        fadeOut(animationSpec = tween(300))
                    },
                    label = "search_state"
                ) { state ->
                    when (state) {
                        is SearchState.Initial -> {
                            InitialContent(
                                recentSearches = recentSearches,
                                trendingSearches = trendingSearches,
                                onRecentSearchClick = {
                                    viewModel.onAction(SearchAction.RecentSearchClicked(it))
                                },
                                onDeleteSearch = {
                                    viewModel.onAction(SearchAction.DeleteRecentSearch(it))
                                },
                                onClearAll = {
                                    viewModel.onAction(SearchAction.ClearAllRecentSearches)
                                },
                                onQuickSuggestionClick = {
                                    viewModel.onAction(SearchAction.SuggestionClicked(it))
                                },
                                onBrowseArtists = {
                                    viewModel.onAction(SearchAction.BrowseArtists)
                                },
                                onBrowseAlbums = {
                                    viewModel.onAction(SearchAction.BrowseAlbums)
                                },
                                bottomPadding = bottomPadding
                            )
                        }

                        is SearchState.LoadingSuggestions -> {
                            LoadingState(message = "Getting suggestions...")
                        }

                        is SearchState.Suggestions -> {
                            SuggestionsContent(
                                query = state.query,
                                suggestions = state.suggestions,
                                recentSearches = state.recentSearches,
                                onSuggestionClick = {
                                    viewModel.onAction(SearchAction.SuggestionClicked(it))
                                },
                                onRecentSearchClick = {
                                    viewModel.onAction(SearchAction.RecentSearchClicked(it))
                                }
                            )
                        }

                        is SearchState.LoadingResults -> {
                            ShimmerLoadingState()
                        }

                        is SearchState.Results -> {
                            SearchResultsContent(
                                query = state.query,
                                songs = state.songs,
                                videos = state.videos,
                                totalCount = state.totalCount,
                                paginationState = state.paginationState,
                                videoPaginationState = state.videoPaginationState,
                                onSongClick = {
                                    viewModel.onAction(SearchAction.SongClicked(it))
                                },
                                onArtistClick = { name, browseId ->
                                    onNavigateToArtist(name, browseId)
                                },
                                onLoadMore = {
                                    viewModel.onAction(SearchAction.LoadMore)
                                },
                                onLoadMoreVideos = {
                                    viewModel.onAction(SearchAction.LoadMoreVideos)
                                },
                                listState = listState,
                                bottomPadding = bottomPadding
                            )
                        }

                        is SearchState.Empty -> {
                            EmptyResultsContent(
                                query = state.query,
                                suggestions = state.suggestions,
                                onSuggestionClick = {
                                    viewModel.onAction(SearchAction.SuggestionClicked(it))
                                }
                            )
                        }

                        is SearchState.Error -> {
                            ErrorContent(
                                message = state.message,
                                onRetry = { viewModel.onAction(SearchAction.RetrySearch) },
                                isRecoverable = state.isRecoverable
                            )
                        }

                        is SearchState.BrowseArtists -> {
                            BrowseArtistsContent(
                                state = state,
                                onArtistClick = { name, browseId ->
                                    onNavigateToArtist(name, browseId)
                                },
                                onBack = {
                                    viewModel.onAction(SearchAction.BackFromBrowse)
                                },
                                onLoadMore = {
                                    viewModel.onAction(SearchAction.LoadMoreArtists)
                                },
                                bottomPadding = bottomPadding
                            )
                        }

                        is SearchState.BrowseAlbums -> {
                            BrowseAlbumsContent(
                                state = state,
                                onAlbumClick = onNavigateToAlbum,
                                onLoadMore = {
                                    viewModel.onAction(SearchAction.LoadMoreAlbums)
                                },
                                onBack = {
                                    viewModel.onAction(SearchAction.BackFromBrowse)
                                },
                                bottomPadding = bottomPadding
                            )
                        }
                    }
                }
                } // Column
            } // Box
        } // Outer Column
    } // Scaffold
}

@Composable
private fun SearchBarSection(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )

            Spacer(modifier = Modifier.width(12.dp))

            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        "Search songs, artists...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )

            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically(),
            ) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun InitialContent(
    recentSearches: List<String>,
    trendingSearches: List<String>,
    onRecentSearchClick: (String) -> Unit,
    onDeleteSearch: (String) -> Unit,
    onClearAll: () -> Unit,
    onQuickSuggestionClick: (String) -> Unit,
    onBrowseArtists: () -> Unit,
    onBrowseAlbums: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)
    ) {
        // Trending searches
        item {
            TrendingSearchesSection(
                trendingSearches = trendingSearches,
                onSuggestionClick = onQuickSuggestionClick
            )
        }

        // Recent searches
        if (recentSearches.isNotEmpty()) {
            item {
                RecentSearchesHeader(onClearAll = onClearAll)
            }

            items(
                items = recentSearches,
                key = { it }
            ) { query ->
                RecentSearchItem(
                    query = query,
                    onClick = { onRecentSearchClick(query) },
                    onDelete = { onDeleteSearch(query) },
                )
            }
        }

        // Browse categories
        item { 
            BrowseCategoriesSection(
                onCategoryClick = onQuickSuggestionClick,
                onBrowseArtists = onBrowseArtists,
                onBrowseAlbums = onBrowseAlbums
            ) 
        }
    }
}

@Composable
private fun SuggestionsContent(
    query: String,
    suggestions: List<String>,
    recentSearches: List<String>,
    onSuggestionClick: (String) -> Unit,
    onRecentSearchClick: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            // Recent searches that match
            if (recentSearches.isNotEmpty()) {
                Text(
                    text = "Recent",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                
                recentSearches.forEach { search ->
                    SuggestionItem(
                        icon = Icons.Rounded.History,
                        text = search,
                        onClick = { onRecentSearchClick(search) }
                    )
                }
                
                if (suggestions.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
            
            // API suggestions
            suggestions.forEach { suggestion ->
                SuggestionItem(
                    icon = Icons.Rounded.Search,
                    text = suggestion,
                    onClick = { onSuggestionClick(suggestion) }
                )
            }

            if (recentSearches.isEmpty() && suggestions.isEmpty()) {
                Text(
                    text = "No suggestions for \"$query\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun SuggestionItem(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Icon(
            imageVector = Icons.Rounded.NorthWest,
            contentDescription = "Use suggestion",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SearchResultsContent(
    query: String,
    songs: List<com.sonicmusic.app.domain.model.Song>,
    videos: List<com.sonicmusic.app.domain.model.Song>,
    totalCount: Int,
    paginationState: PaginationState,
    videoPaginationState: PaginationState,
    onSongClick: (com.sonicmusic.app.domain.model.Song) -> Unit,
    onArtistClick: (String, String?) -> Unit,
    onLoadMore: () -> Unit,
    onLoadMoreVideos: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    bottomPadding: androidx.compose.ui.unit.Dp
) {
    LaunchedEffect(query, songs.size, paginationState) {
        if (songs.isEmpty() || paginationState !is PaginationState.Idle) return@LaunchedEffect

        snapshotFlow {
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleIndex >= totalItems - 4
        }
            .distinctUntilChanged()
            .filter { it }
            .collectLatest {
                onLoadMore()
            }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = bottomPadding + 16.dp),
    ) {
        // ── Videos Section ──────────────────────────────────
        if (videos.isNotEmpty()) {
            item(key = "videos_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.VideoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Videos",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    ) {
                        Text(
                            text = "${videos.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            item(key = "videos_row") {
                val videoRowState = rememberLazyListState()

                // Detect when user scrolls near the end of the video row
                LaunchedEffect(videos.size, videoPaginationState) {
                    if (videoPaginationState !is PaginationState.Idle) return@LaunchedEffect

                    snapshotFlow {
                        val totalItems = videoRowState.layoutInfo.totalItemsCount
                        val lastVisible = videoRowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        totalItems > 0 && lastVisible >= totalItems - 3
                    }
                        .distinctUntilChanged()
                        .filter { it }
                        .collectLatest {
                            onLoadMoreVideos()
                        }
                }

                LazyRow(
                    state = videoRowState,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = videos,
                        key = { "video_${it.id}" }
                    ) { video ->
                        SearchVideoCard(
                            song = video,
                            onClick = { onSongClick(video) }
                        )
                    }

                    // Loading indicator at the end of videos row
                    if (videoPaginationState is PaginationState.Loading) {
                        item(key = "video_loading") {
                            Box(
                                modifier = Modifier
                                    .width(200.dp)
                                    .aspectRatio(16f / 9f),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }

            item(key = "videos_divider") {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
            }
        }

        // ── Songs Section ──────────────────────────────────
        item {
            ResultsHeader(query = query, count = songs.size, totalCount = totalCount)
        }

        itemsIndexed(
            items = songs,
            key = { index, song -> "${song.id}_$index" },
            contentType = { _, song -> song.contentType }
        ) { index, song ->
            SearchResultCard(
                song = song,
                index = index + 1,
                onClick = {
                    if (song.contentType == com.sonicmusic.app.domain.model.ContentType.ARTIST) {
                        onArtistClick(song.title, song.id.takeIf { it.startsWith("UC") })
                    } else {
                        onSongClick(song)
                    }
                },
            )
        }

        // Pagination loading indicator
        when (paginationState) {
            is PaginationState.Loading -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
            is PaginationState.Error -> {
                item {
                    PaginationErrorItem(
                        message = paginationState.message,
                        onRetry = onLoadMore
                    )
                }
            }
            PaginationState.Idle -> Unit
            PaginationState.NoMoreData -> {
                item {
                    EndOfResultsItem()
                }
            }
        }
    }
}

/**
 * Video card for search results — 16:9 aspect ratio like YT Music
 */
@Composable
private fun SearchVideoCard(
    song: com.sonicmusic.app.domain.model.Song,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .width(200.dp)
            .clickable(onClick = onClick)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = RoundedCornerShape(12.dp),
            color = colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp
        ) {
            Box {
                if (song.thumbnailUrl.isNotBlank()) {
                    SongThumbnail(
                        artworkUrl = song.thumbnailUrl,
                        contentDescription = song.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VideoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }

                // Play overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.4f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.size(36.dp),
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.5f)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // Duration badge
                if (song.duration > 0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.7f)
                    ) {
                        Text(
                            text = formatDuration(song.duration),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = song.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = song.artist,
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (song.viewCount != null && song.viewCount > 0) {
            Text(
                text = formatSearchViewCount(song.viewCount),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

private fun formatSearchViewCount(count: Long): String {
    return when {
        count >= 1_000_000_000 -> String.format("%.1fB views", count / 1_000_000_000.0)
        count >= 1_000_000 -> String.format("%.1fM views", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK views", count / 1_000.0)
        else -> "$count views"
    }
}

@Composable
private fun ResultsHeader(query: String, count: Int, totalCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Results",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "\"$query\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
        ) {
            Text(
                text = if (count == totalCount) "$count songs" else "$count of $totalCount",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PaginationErrorItem(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clickable { onRetry() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = message.ifBlank { "Failed to load more. Tap to retry." },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun EndOfResultsItem() {
    Text(
        text = "You reached the end",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun SearchResultCard(
    song: com.sonicmusic.app.domain.model.Song,
    index: Int,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail
            val shape = if (song.contentType == com.sonicmusic.app.domain.model.ContentType.ARTIST) CircleShape else RoundedCornerShape(12.dp)
            
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(shape),
            ) {
                SongThumbnail(
                    artworkUrl = song.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

                // Overlay for Song/Video
                if (song.contentType == com.sonicmusic.app.domain.model.ContentType.SONG || 
                    song.contentType == com.sonicmusic.app.domain.model.ContentType.VIDEO) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(2.dp))

                val subtitle = when(song.contentType) {
                    com.sonicmusic.app.domain.model.ContentType.ARTIST -> "Artist"
                    com.sonicmusic.app.domain.model.ContentType.ALBUM -> "Album • ${song.artist}"
                    com.sonicmusic.app.domain.model.ContentType.PLAYLIST -> "Playlist • ${song.artist}"
                    com.sonicmusic.app.domain.model.ContentType.VIDEO -> "Video • ${song.artist}"
                    else -> song.artist
                }

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (song.duration > 0 && song.contentType == com.sonicmusic.app.domain.model.ContentType.SONG) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        text = formatDuration(song.duration),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyResultsContent(
    query: String,
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            modifier = Modifier.size(100.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.SearchOff,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "No results",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Couldn't find \"$query\"\nTry different keywords",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (suggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "Try these instead:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            suggestions.forEach { suggestion ->
                SuggestionChip(
                    onClick = { onSuggestionClick(suggestion) },
                    label = { Text(suggestion) },
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    isRecoverable: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            modifier = Modifier.size(100.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.SearchOff,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Search Failed",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (isRecoverable) {
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Try Again")
            }
        }
    }
}

@Composable
private fun LoadingState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShimmerLoadingState() {
    // Simple shimmer placeholder
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Header shimmer
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(24.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    RoundedCornerShape(12.dp)
                )
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Item shimmers
        repeat(6) {
            ShimmerSearchResultItem()
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ShimmerSearchResultItem() {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer_alpha"
    )
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = shimmerAlpha),
                    RoundedCornerShape(16.dp)
                )
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(16.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = shimmerAlpha),
                        RoundedCornerShape(12.dp)
                    )
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(12.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = shimmerAlpha),
                        RoundedCornerShape(12.dp)
                    )
            )
        }
    }
}

@Composable
private fun TrendingSearchesSection(
    trendingSearches: List<String>,
    onSuggestionClick: (String) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = "Trending",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(trendingSearches) { suggestion ->
                SuggestionChip(
                    onClick = { onSuggestionClick(suggestion) },
                    label = { Text(suggestion, style = MaterialTheme.typography.labelLarge) },
                    shape = RoundedCornerShape(50),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    ),
                )
            }
        }
    }
}

@Composable
private fun RecentSearchesHeader(onClearAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Recent",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(onClick = onClearAll) {
            Text("Clear All", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun RecentSearchItem(
    query: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = query,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun BrowseCategoriesSection(
    onCategoryClick: (String) -> Unit,
    onBrowseArtists: () -> Unit,
    onBrowseAlbums: () -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 16.dp)) {
        Text(
            text = "Browse",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrowseCategoryCard(
                title = "Songs",
                icon = Icons.Rounded.MusicNote,
                color = MaterialTheme.colorScheme.primaryContainer,
                onClick = { onCategoryClick("Songs") },
                modifier = Modifier.weight(1f),
            )
            BrowseCategoryCard(
                title = "Artists",
                icon = Icons.Rounded.MicExternalOn,
                color = MaterialTheme.colorScheme.secondaryContainer,
                onClick = onBrowseArtists,
                modifier = Modifier.weight(1f),
            )
            BrowseCategoryCard(
                title = "Albums",
                icon = Icons.Rounded.Album,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                onClick = onBrowseAlbums,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BrowseCategoryCard(
    title: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gradient = Brush.linearGradient(
        colors = listOf(
            color.copy(alpha = 0.9f),
            color.copy(alpha = 0.5f)
        ),
        start = Offset.Zero,
        end = Offset(150f, 150f)
    )
    
    Surface(
        modifier = modifier.height(100.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        onClick = onClick
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Browse top artists list
 */
@Composable
private fun BrowseArtistsContent(
    state: SearchState.BrowseArtists,
    onArtistClick: (String, String?) -> Unit,
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "Top Artists",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        when {
            state.isLoading && state.artists.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Loading top artists...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            state.error != null && state.artists.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.error,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onBack) {
                            Text("Go Back")
                        }
                    }
                }
            }
            else -> {
                val listState = rememberLazyListState()

                // infinite scroll trigger
                LaunchedEffect(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index) {
                    val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                    val totalItems = listState.layoutInfo.totalItemsCount
                    
                    if (lastVisibleItem != null && totalItems > 0 && lastVisibleItem >= totalItems - 2) {
                        if (!state.isPaginating && !state.continuationToken.isNullOrBlank()) {
                            onLoadMore()
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)
                ) {
                    item {
                        Text(
                            text = "${state.artists.size} artists in your region",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }
                    itemsIndexed(
                        items = state.artists,
                        key = { index, artist -> "${artist.id}_$index" }
                    ) { index, artist ->
                        BrowseArtistItem(
                            artist = artist,
                            rank = index + 1,
                            onClick = {
                                onArtistClick(
                                    artist.title,
                                    artist.artistId ?: artist.id.takeIf { it.startsWith("UC") }
                                )
                            }
                        )
                    }

                    if (state.isPaginating) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseArtistItem(
    artist: com.sonicmusic.app.domain.model.Song,
    rank: Int,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rank number
            Text(
                text = "$rank",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(32.dp),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Circular thumbnail
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
            ) {
                if (artist.thumbnailUrl.isNotBlank()) {
                    SongThumbnail(
                        artworkUrl = artist.thumbnailUrl,
                        contentDescription = artist.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.MicExternalOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artist.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (artist.artist.isNotBlank() && artist.artist != "Artist") {
                    Text(
                        text = artist.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Browse top albums list
 */
@Composable
private fun BrowseAlbumsContent(
    state: SearchState.BrowseAlbums,
    onAlbumClick: (com.sonicmusic.app.domain.model.Song) -> Unit,
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "Top Albums",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        when {
            state.isLoading && state.albums.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Loading top albums...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            state.error != null && state.albums.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.error,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onBack) {
                            Text("Go Back")
                        }
                    }
                }
            }
            else -> {
                val listState = rememberLazyListState()

                // infinite scroll trigger
                LaunchedEffect(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index) {
                    val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                    val totalItems = listState.layoutInfo.totalItemsCount
                    
                    if (lastVisibleItem != null && totalItems > 0 && lastVisibleItem >= totalItems - 2) {
                        if (!state.isPaginating && !state.continuationToken.isNullOrBlank()) {
                            onLoadMore()
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)
                ) {
                    item {
                        Text(
                            text = "${state.albums.size} albums in your region",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }
                    itemsIndexed(
                        items = state.albums,
                        key = { index, album -> "${album.id}_$index" }
                    ) { index, album ->
                        BrowseAlbumItem(
                            album = album,
                            rank = index + 1,
                            onClick = { onAlbumClick(album) }
                        )
                    }
                    
                    if (state.isPaginating) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseAlbumItem(
    album: com.sonicmusic.app.domain.model.Song,
    rank: Int,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rank number
            Text(
                text = "$rank",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.width(32.dp),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Square thumbnail
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                if (album.thumbnailUrl.isNotBlank()) {
                    SongThumbnail(
                        artworkUrl = album.thumbnailUrl,
                        contentDescription = album.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Album,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = album.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
