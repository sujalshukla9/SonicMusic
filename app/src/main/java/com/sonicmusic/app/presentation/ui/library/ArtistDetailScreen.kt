package com.sonicmusic.app.presentation.ui.library

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import com.sonicmusic.app.presentation.ui.components.ArtistProfileSkeleton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.sonicmusic.app.domain.model.ArtistProfile
import com.sonicmusic.app.domain.model.ArtistProfileSectionType
import com.sonicmusic.app.domain.model.PlaybackHistory
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.presentation.ui.components.SongThumbnail
import com.sonicmusic.app.presentation.viewmodel.ArtistDetailViewModel

/**
 * Artist Detail Screen — YouTube Music Style
 *
 * Layout:
 * 1. Hero header with large artist image, name, subscriber count, follow button
 * 2. Play All + Shuffle action buttons
 * 3. "Songs" numbered list from YouTube
 * 4. "Albums" horizontal scrollable row
 * 5. "Singles" horizontal scrollable row
 * 6. "Videos" horizontal scrollable row
 * 7. "Related Artists" circular avatars row
 * 8. "Recently Played" from local history (collapsible)
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ArtistDetailScreen(
    artistName: String,
    browseId: String? = null,
    onNavigateBack: () -> Unit,
    onShowFullPlayer: () -> Unit = {},
    onNavigateToArtist: (String, String?) -> Unit = { _, _ -> },
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    viewModel: ArtistDetailViewModel = hiltViewModel()
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsStateWithLifecycle()
    val isFollowed by viewModel.isFollowed.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val loadingMoreSections by viewModel.loadingMoreSections.collectAsStateWithLifecycle()




    LaunchedEffect(artistName, browseId) {
        viewModel.loadArtist(artistName, browseId)
    }

    val colorScheme = MaterialTheme.colorScheme

    val tabs = listOf(
        "Overview",
        "Songs",
        "Albums",
        "Singles",
        "Videos",
        "Library"
    )
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            ArtistProfileSkeleton(
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding()
                )
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
            ) {
                // Fixed Hero Header
                ArtistHeroHeader(
                    artistName = profile?.name ?: artistName,
                    imageUrl = profile?.imageUrl,
                    bannerUrl = profile?.bannerUrl,
                    subscribersText = profile?.subscribersText,
                    songCount = profile?.topSongs?.size ?: 0,
                    onPlayAll = {
                        viewModel.playAll()
                        onShowFullPlayer()
                    },
                    onShuffle = {
                        viewModel.shufflePlay()
                        onShowFullPlayer()
                    },
                    isFollowed = isFollowed,
                    onToggleFollow = { viewModel.toggleFollow() },
                    hasSongs = (profile?.topSongs?.isNotEmpty() == true) || recentlyPlayed.isNotEmpty()
                )

                // Scrollable Tab Row
                androidx.compose.material3.ScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    edgePadding = 16.dp,
                    containerColor = Color.Transparent,
                    divider = {}
                ) {
                    tabs.forEachIndexed { index, title ->
                        androidx.compose.material3.Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            text = { 
                                Text(
                                    text = title, 
                                    fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Medium
                                ) 
                            }
                        )
                    }
                }
                
                HorizontalDivider(
                    color = colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                // Horizontal Pager for Content
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> { // Overview Tab
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)
                            ) {
                                // ── Songs Section ───────────────────────────────────
                                val topSongs = profile?.topSongs ?: emptyList()
                                if (topSongs.isNotEmpty()) {
                                    val isSongsLoadingMore = loadingMoreSections.contains(ArtistProfileSectionType.TopSongs)
                                    val hasSongLoadMoreSource = !profile?.songsMoreEndpoint.isNullOrBlank() ||
                                        !profile?.topSongsBrowseId.isNullOrBlank() ||
                                        !profile?.browseId.isNullOrBlank()
                                    item {
                                        SectionHeader(
                                            title = "Songs",
                                            count = topSongs.size,
                                            isLoadingMore = isSongsLoadingMore,
                                            onSeeAllClick = if (topSongs.size > 5 || hasSongLoadMoreSource) {
                                                {
                                                    coroutineScope.launch {
                                                        pagerState.animateScrollToPage(1)
                                                    }
                                                }
                                            } else null,
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                                        )
                                    }

                                    val displaySongs = topSongs.take(5)
                                    itemsIndexed(
                                        items = displaySongs,
                                        key = { _, song -> "top_${song.id}" }
                                    ) { index, song ->
                                        ArtistTopSongItem(
                                            song = song,
                                            trackNumber = index + 1,
                                            onClick = {
                                                viewModel.playSong(song)
                                                onShowFullPlayer()
                                            }
                                        )
                                    }
                                }

                                // ── Albums Section ──────────────────────────────────
                                val albums = profile?.albums ?: emptyList()
                                if (albums.isNotEmpty()) {
                                    val albumsMoreEndpoint = profile?.albumsMoreEndpoint
                                        ?: profile?.sections?.firstOrNull { it.type == ArtistProfileSectionType.Albums }?.moreEndpoint
                                    val isAlbumsLoadingMore = loadingMoreSections.contains(ArtistProfileSectionType.Albums)
                                    item {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        SectionHeader(
                                            title = "Albums",
                                            count = albums.size,
                                            isLoadingMore = isAlbumsLoadingMore,
                                            onSeeAllClick = if (!albumsMoreEndpoint.isNullOrBlank()) {
                                                { 
                                                    coroutineScope.launch { 
                                                        pagerState.animateScrollToPage(2) 
                                                    } 
                                                }
                                            } else null,
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                                        )
                                    }
                                    item {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            items(albums, key = { "album_${it.id}" }) { album ->
                                                AlbumCard(
                                                    song = album,
                                                    onClick = {
                                                        viewModel.playSong(album)
                                                        onShowFullPlayer()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // ── Singles Section ─────────────────────────────────
                                val singles = profile?.singles ?: emptyList()
                                if (singles.isNotEmpty()) {
                                    val singlesMoreEndpoint = profile?.singlesMoreEndpoint
                                        ?: profile?.sections?.firstOrNull { it.type == ArtistProfileSectionType.Singles }?.moreEndpoint
                                    val isSinglesLoadingMore = loadingMoreSections.contains(ArtistProfileSectionType.Singles)
                                    item {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        SectionHeader(
                                            title = "Singles",
                                            count = singles.size,
                                            isLoadingMore = isSinglesLoadingMore,
                                            onSeeAllClick = if (!singlesMoreEndpoint.isNullOrBlank()) {
                                                { 
                                                    coroutineScope.launch { 
                                                        pagerState.animateScrollToPage(3) 
                                                    } 
                                                }
                                            } else null,
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                                        )
                                    }
                                    item {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            items(singles, key = { "single_${it.id}" }) { single ->
                                                AlbumCard(
                                                    song = single,
                                                    onClick = {
                                                        viewModel.playSong(single)
                                                        onShowFullPlayer()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // ── Videos Section ──────────────────────────────────
                                val videos = profile?.videos ?: emptyList()
                                if (videos.isNotEmpty()) {
                                    val videosMoreEndpoint = profile?.sections?.firstOrNull {
                                        it.type == ArtistProfileSectionType.Videos
                                    }?.moreEndpoint
                                    val isVideosLoadingMore = loadingMoreSections.contains(ArtistProfileSectionType.Videos)
                                    item {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        SectionHeader(
                                            title = "Videos",
                                            count = videos.size,
                                            isLoadingMore = isVideosLoadingMore,
                                            onSeeAllClick = if (!videosMoreEndpoint.isNullOrBlank()) {
                                                { 
                                                    coroutineScope.launch { 
                                                        pagerState.animateScrollToPage(4) 
                                                    } 
                                                }
                                            } else null,
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                                        )
                                    }
                                    item {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            items(videos, key = { "video_${it.id}" }) { video ->
                                                VideoCard(
                                                    song = video,
                                                    onClick = {
                                                        viewModel.playSong(video)
                                                        onShowFullPlayer()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // ── Featured On Section ─────────────────────────────
                                val featuredOn = profile?.featuredOn ?: emptyList()
                                if (featuredOn.isNotEmpty()) {
                                    item {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        SectionHeader(
                                            title = "Featured On",
                                            count = featuredOn.size,
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                                        )
                                    }
                                    item {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            items(featuredOn, key = { "featured_${it.id}" }) { item ->
                                                AlbumCard(
                                                    song = item,
                                                    onClick = {
                                                        viewModel.playSong(item)
                                                        onShowFullPlayer()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // ── Related Artists Section ─────────────────────────
                                val relatedArtists = profile?.relatedArtists ?: emptyList()
                                if (relatedArtists.isNotEmpty()) {
                                    item {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        SectionHeader(
                                            title = "Fans might also like",
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                                        )
                                    }
                                    item {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            items(relatedArtists, key = { "related_${it.id}" }) { related ->
                                                RelatedArtistChip(
                                                    song = related,
                                                    onClick = {
                                                        // Navigate to related artist profile
                                                        val name = related.title.ifBlank { related.artist }
                                                        val relatedBrowseId = related.artistId?.takeIf { it.isNotBlank() }
                                                            ?: related.id.takeIf { it.isNotBlank() }
                                                        if (name.isNotBlank()) {
                                                            onNavigateToArtist(name, relatedBrowseId)
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // ── About Section ───────────────────────────────────
                                val description = profile?.description
                                if (!description.isNullOrBlank()) {
                                    item {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                            color = colorScheme.outlineVariant.copy(alpha = 0.3f)
                                        )
                                        ArtistBioSection(description = description)
                                    }
                                }
                                
                                // ── Empty State ─────────────────────────────────────
                                if ((profile?.topSongs?.isEmpty() != false) && recentlyPlayed.isEmpty() && error != null) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(48.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(
                                                    imageVector = Icons.Rounded.MusicNote,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(48.dp),
                                                    tint = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                )
                                                Spacer(modifier = Modifier.height(12.dp))
                                                Text(
                                                    text = error ?: "No songs found",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = colorScheme.onSurfaceVariant,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        1 -> { // Songs Tab
                            val topSongs = profile?.topSongs ?: emptyList()
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)
                            ) {
                                itemsIndexed(
                                    items = topSongs,
                                    key = { _, song -> "songs_tab_${song.id}" }
                                ) { index, song ->
                                    ArtistTopSongItem(
                                        song = song,
                                        trackNumber = index + 1,
                                        onClick = {
                                            viewModel.playSong(song)
                                            onShowFullPlayer()
                                        }
                                    )
                                    
                                    // Trigger load more when reaching the end
                                    if (index == topSongs.lastIndex) {
                                        LaunchedEffect(key1 = topSongs.size) {
                                            viewModel.loadMoreSongs()
                                        }
                                    }
                                }
                                
                                val isSongsLoadingMore = loadingMoreSections.contains(ArtistProfileSectionType.TopSongs)
                                if (isSongsLoadingMore) {
                                    item {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Loading more...",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        2 -> { // Albums Tab
                            val albums = profile?.albums ?: emptyList()
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomPadding + 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                itemsIndexed(albums.chunked(2)) { rowIndex, rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        rowItems.forEach { album ->
                                            Box(modifier = Modifier.weight(1f)) {
                                                AlbumCard(
                                                    song = album,
                                                    onClick = {
                                                        viewModel.playSong(album)
                                                        onShowFullPlayer()
                                                    }
                                                )
                                            }
                                        }
                                        // Fill empty space if row has only 1 item
                                        if (rowItems.size < 2) {
                                            Spacer(modifier = Modifier.weight((2 - rowItems.size).toFloat()))
                                        }
                                    }
                                    
                                    if (rowIndex == albums.chunked(2).lastIndex) {
                                        LaunchedEffect(key1 = albums.size) {
                                            viewModel.loadMoreSection(ArtistProfileSectionType.Albums)
                                        }
                                    }
                                }
                                
                                val isAlbumsLoadingMore = loadingMoreSections.contains(ArtistProfileSectionType.Albums)
                                if (isAlbumsLoadingMore) {
                                    item {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Loading more...",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        3 -> { // Singles Tab
                            val singles = profile?.singles ?: emptyList()
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomPadding + 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                itemsIndexed(singles.chunked(2)) { rowIndex, rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        rowItems.forEach { single ->
                                            Box(modifier = Modifier.weight(1f)) {
                                                AlbumCard(
                                                    song = single,
                                                    onClick = {
                                                        viewModel.playSong(single)
                                                        onShowFullPlayer()
                                                    }
                                                )
                                            }
                                        }
                                        if (rowItems.size < 2) {
                                            Spacer(modifier = Modifier.weight((2 - rowItems.size).toFloat()))
                                        }
                                    }
                                    
                                    if (rowIndex == singles.chunked(2).lastIndex) {
                                        LaunchedEffect(key1 = singles.size) {
                                            viewModel.loadMoreSection(ArtistProfileSectionType.Singles)
                                        }
                                    }
                                }
                                
                                val isSinglesLoadingMore = loadingMoreSections.contains(ArtistProfileSectionType.Singles)
                                if (isSinglesLoadingMore) {
                                    item {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(text = "Loading more...", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                        4 -> { // Videos Tab
                            val videos = profile?.videos ?: emptyList()
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomPadding + 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                itemsIndexed(videos) { index, video ->
                                    VideoCard(
                                        song = video,
                                        onClick = {
                                            viewModel.playSong(video)
                                            onShowFullPlayer()
                                        }
                                    )
                                    
                                    if (index == videos.lastIndex) {
                                        LaunchedEffect(key1 = videos.size) {
                                            viewModel.loadMoreSection(ArtistProfileSectionType.Videos)
                                        }
                                    }
                                }
                                
                                val isVideosLoadingMore = loadingMoreSections.contains(ArtistProfileSectionType.Videos)
                                if (isVideosLoadingMore) {
                                    item {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(text = "Loading more...", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                        5 -> { // Library Tab
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)
                            ) {
                                if (recentlyPlayed.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(48.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "No recently played songs from this artist.",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                } else {
                                    item {
                                        SectionHeader(
                                            title = "Recently Played",
                                            count = recentlyPlayed.size,
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                                        )
                                    }
                                    items(
                                        items = recentlyPlayed,
                                        key = { "recent_${it.id}" }
                                    ) { history ->
                                        RecentlyPlayedItem(
                                            history = history,
                                            onClick = {
                                                viewModel.playHistorySong(history)
                                                onShowFullPlayer()
                                            }
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
}
// ═════════════════════════════════════════════════════════════════════
// COMPONENTS
// ═════════════════════════════════════════════════════════════════════

/**
 * Section header — title with optional item count
 */
@Composable
private fun SectionHeader(
    title: String,
    count: Int? = null,
    isLoadingMore: Boolean = false,
    onSeeAllClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (isLoadingMore) {
            Text(
                text = "Loading...",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        } else if (onSeeAllClick != null) {
            Text(
                text = "See all",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onSeeAllClick() }
            )
        } else if (count != null && count > 0) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Hero header with large artist image, name, subscribers, follow button, and action buttons
 */
@Composable
private fun ArtistHeroHeader(
    artistName: String,
    imageUrl: String?,
    bannerUrl: String?,
    subscribersText: String?,
    songCount: Int,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    isFollowed: Boolean,
    onToggleFollow: () -> Unit,
    hasSongs: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        if (bannerUrl != null) {
            SongThumbnail(
                artworkUrl = bannerUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                highQuality = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                colorScheme.background
                            )
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = if (bannerUrl != null) 32.dp else 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Artist image — large circular
            Surface(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape),
            color = colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            if (imageUrl != null) {
                SongThumbnail(
                    artworkUrl = imageUrl,
                    contentDescription = artistName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    highQuality = true
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Artist name
        Text(
            text = artistName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Subscriber count (from YTMusic)
        if (!subscribersText.isNullOrBlank()) {
            Text(
                text = subscribersText,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Song count
        if (songCount > 0) {
            Text(
                text = "$songCount songs",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Follow Button
        if (isFollowed) {
            FilledTonalButton(
                onClick = onToggleFollow,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = colorScheme.surfaceVariant,
                    contentColor = colorScheme.onSurfaceVariant
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Following", fontWeight = FontWeight.Medium)
            }
        } else {
            OutlinedButton(
                onClick = onToggleFollow,
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, colorScheme.outline
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = colorScheme.onSurface
                )
            ) {
                Text("Follow", fontWeight = FontWeight.Medium)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onPlayAll,
                enabled = hasSongs,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorScheme.primary
                ),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Play All", fontWeight = FontWeight.SemiBold)
            }

            FilledTonalButton(
                onClick = onShuffle,
                enabled = hasSongs,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Shuffle", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
    }
}

/**
 * Top song item — numbered track with thumbnail, title, artist, duration, and play count
 */
@Composable
private fun ArtistTopSongItem(
    song: Song,
    trackNumber: Int,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Track number
        Text(
            text = "$trackNumber",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.width(32.dp),
            textAlign = TextAlign.Center
        )

        // Thumbnail
        SongThumbnail(
            artworkUrl = song.thumbnailUrl,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Title + Artist + View count
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Normal,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (song.viewCount != null && song.viewCount > 0) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = formatViewCount(song.viewCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // Duration
        if (song.duration > 0) {
            Text(
                text = song.formattedDuration(),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/**
 * Album / Single card for horizontal scroll — artwork + title + subtitle
 */
@Composable
private fun AlbumCard(
    song: Song,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
    ) {
        Surface(
            modifier = Modifier
                .size(140.dp)
                .aspectRatio(1f),
            shape = RoundedCornerShape(12.dp),
            color = colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp
        ) {
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
                        imageVector = Icons.Rounded.Album,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = song.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (song.artist.isNotBlank()) {
            Text(
                text = song.artist,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Video card — wider 16:9 aspect ratio
 */
@Composable
private fun VideoCard(
    song: Song,
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
        if (song.viewCount != null && song.viewCount > 0) {
            Text(
                text = formatViewCount(song.viewCount),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * Related artist chip — circular avatar + name
 */
@Composable
private fun RelatedArtistChip(
    song: Song,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val displayName = song.title.ifBlank { song.artist }
    val subtitle = song.artist.takeIf {
        it.isNotBlank() && !it.equals(displayName, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .width(90.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape),
            color = colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp
        ) {
            if (song.thumbnailUrl.isNotBlank()) {
                SongThumbnail(
                    artworkUrl = song.thumbnailUrl,
                    contentDescription = displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = displayName,
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Recently played item — simpler row from local history
 */
@Composable
private fun RecentlyPlayedItem(
    history: PlaybackHistory,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = history.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = history.title,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = history.artist,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ─── Utility ────────────────────────────────────────────────────────

private fun formatViewCount(count: Long): String {
    return when {
        count >= 1_000_000_000 -> String.format("%.1fB plays", count / 1_000_000_000.0)
        count >= 1_000_000 -> String.format("%.1fM plays", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK plays", count / 1_000.0)
        else -> "$count plays"
    }
}

/**
 * Collapsible bio section for the artist description
 */
@Composable
private fun ArtistBioSection(
    description: String,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "About",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (isExpanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}
