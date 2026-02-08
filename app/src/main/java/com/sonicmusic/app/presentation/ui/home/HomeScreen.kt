package com.sonicmusic.app.presentation.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import coil.compose.AsyncImage
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.presentation.viewmodel.HomeViewModel
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSearch: () -> Unit = {},
    onNavigateToLikedSongs: () -> Unit = {},
    onNavigateToDownloads: () -> Unit = {},
    onNavigateToPlaylists: () -> Unit = {},
    onNavigateToRecentlyPlayed: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onShowFullPlayer: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val homeContent by viewModel.homeContent.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0)
    ) { paddingValues ->
        
        if (isLoading && homeContent.listenAgain.isEmpty()) {
            // Loading state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp
                    )
                    Text(
                        text = "Loading your music...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(bottom = 120.dp) // Space for mini player + nav bar
            ) {
                // ═══════════════════════════════════════════
                // 1️⃣ HEADER - Greeting + Profile + Search
                // ═══════════════════════════════════════════
                item {
                    HomeHeader(
                        onSearchClick = onNavigateToSearch,
                        onSettingsClick = onNavigateToSettings
                    )
                }

                // ═══════════════════════════════════════════
                // 2️⃣ QUICK ACCESS BUTTONS
                // ═══════════════════════════════════════════
                item {
                    QuickAccessSection(
                        onLikedSongsClick = onNavigateToLikedSongs,
                        onDownloadsClick = onNavigateToDownloads,
                        onPlaylistsClick = onNavigateToPlaylists,
                        onRecentlyPlayedClick = onNavigateToRecentlyPlayed
                    )
                }

                // ═══════════════════════════════════════════
                // 3️⃣ CONTINUE LISTENING (Listen Again)
                // ═══════════════════════════════════════════
                if (homeContent.listenAgain.isNotEmpty()) {
                    item {
                        SongSection(
                            title = "Continue Listening",
                            subtitle = "Pick up where you left off",
                            songs = homeContent.listenAgain,
                            onSongClick = { viewModel.onSongClick(it); onShowFullPlayer() },
                            onSeeAllClick = { viewModel.onSectionSeeAll("listen_again") },
                            cardStyle = CardStyle.LARGE_SQUARE
                        )
                    }
                }

                // ═══════════════════════════════════════════
                // 4️⃣ RECOMMENDED FOR YOU (Quick Picks)
                // ═══════════════════════════════════════════
                if (homeContent.quickPicks.isNotEmpty()) {
                    item {
                        SongSection(
                            title = "Recommended For You",
                            subtitle = "Based on your listening",
                            songs = homeContent.quickPicks,
                            onSongClick = { viewModel.onSongClick(it); onShowFullPlayer() },
                            onSeeAllClick = { viewModel.onSectionSeeAll("quick_picks") },
                            cardStyle = CardStyle.MEDIUM_CARD
                        )
                    }
                }

                // ═══════════════════════════════════════════
                // 5️⃣ TRENDING / POPULAR SONGS
                // ═══════════════════════════════════════════
                if (homeContent.trending.isNotEmpty()) {
                    item {
                        SongSection(
                            title = "Trending Now",
                            subtitle = "What's hot in India",
                            songs = homeContent.trending,
                            onSongClick = { viewModel.onSongClick(it); onShowFullPlayer() },
                            onSeeAllClick = { viewModel.onSectionSeeAll("trending") },
                            cardStyle = CardStyle.COMPACT_ROW
                        )
                    }
                }

                // ═══════════════════════════════════════════
                // 6️⃣ NEW RELEASES
                // ═══════════════════════════════════════════
                if (homeContent.newReleases.isNotEmpty()) {
                    item {
                        SongSection(
                            title = "New Releases",
                            subtitle = "Fresh tracks just dropped",
                            songs = homeContent.newReleases,
                            onSongClick = { viewModel.onSongClick(it); onShowFullPlayer() },
                            onSeeAllClick = { viewModel.onSectionSeeAll("new_releases") },
                            cardStyle = CardStyle.MEDIUM_CARD
                        )
                    }
                }
                
                // ═══════════════════════════════════════════
                // 7️⃣ ENGLISH HITS
                // ═══════════════════════════════════════════
                if (homeContent.englishHits.isNotEmpty()) {
                    item {
                        SongSection(
                            title = "English Hits",
                            subtitle = "Top international tracks",
                            songs = homeContent.englishHits,
                            onSongClick = { viewModel.onSongClick(it); onShowFullPlayer() },
                            onSeeAllClick = { viewModel.onSectionSeeAll("english_hits") },
                            cardStyle = CardStyle.LARGE_SQUARE
                        )
                    }
                }

                // ═══════════════════════════════════════════
                // ARTIST SECTIONS (Based on history)
                // ═══════════════════════════════════════════
                items(homeContent.artists) { artistSection ->
                    SongSection(
                        title = "More from ${artistSection.artist.name}",
                        subtitle = "Because you listened",
                        songs = artistSection.songs,
                        onSongClick = { viewModel.onSongClick(it); onShowFullPlayer() },
                        onSeeAllClick = { viewModel.onSectionSeeAll("artist_${artistSection.artist.id}") },
                        cardStyle = CardStyle.MEDIUM_CARD
                    )
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
    val greeting = getGreeting()
    
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
            // Left side: Greeting
            Column {
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
            
            // Right side: Icons
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Search button
                FilledIconButton(
                    onClick = onSearchClick,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                
                // Settings button
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
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
// 2️⃣ QUICK ACCESS BUTTONS
// ═══════════════════════════════════════════════════════════════

@Composable
private fun QuickAccessSection(
    onLikedSongsClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onPlaylistsClick: () -> Unit,
    onRecentlyPlayedClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // Two rows of 2 buttons each (Grid style)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            QuickAccessChip(
                icon = Icons.Default.Favorite,
                label = "Liked Songs",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = onLikedSongsClick,
                modifier = Modifier.weight(1f)
            )
            QuickAccessChip(
                icon = Icons.Default.Download,
                label = "Downloads",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = onDownloadsClick,
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            QuickAccessChip(
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                label = "Playlists",
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                onClick = onPlaylistsClick,
                modifier = Modifier.weight(1f)
            )
            QuickAccessChip(
                icon = Icons.Default.History,
                label = "Recently Played",
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onRecentlyPlayedClick,
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun QuickAccessChip(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
            TextButton(onClick = onSeeAllClick) {
                Text(
                    text = "See all",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
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
                    songs.take(5).forEach { song ->
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
            AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = song.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
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
                    imageVector = Icons.Default.PlayArrow,
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
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
        ) {
            AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = song.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
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
        shape = RoundedCornerShape(12.dp),
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
            AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = song.title,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
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
                    imageVector = Icons.Default.PlayArrow,
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
                .clip(RoundedCornerShape(4.dp))
                .background(brush)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Subtitle placeholder
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
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
                .clip(RoundedCornerShape(12.dp))
                .background(brush)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Title placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush)
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Artist placeholder
        Box(
            modifier = Modifier
                .width(80.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush)
        )
    }
}