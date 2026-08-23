package com.sonicmusic.app.presentation.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.domain.model.AlbumDetails
import com.sonicmusic.app.domain.model.AlbumTrack
import com.sonicmusic.app.domain.model.AlbumVideo
import com.sonicmusic.app.presentation.state.UiState
import com.sonicmusic.app.presentation.ui.components.SongListSkeleton
import com.sonicmusic.app.presentation.ui.components.SongRowSkeleton
import com.sonicmusic.app.presentation.ui.theme.LocalThumbnailShape
import com.sonicmusic.app.presentation.ui.theme.toComposeShape
import com.sonicmusic.app.presentation.ui.components.SongThumbnail
import com.sonicmusic.app.presentation.viewmodel.AlbumDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    albumTitle: String,
    artistName: String,
    thumbnailUrl: String?,
    onNavigateBack: () -> Unit,
    onShowFullPlayer: () -> Unit = {},
    onNavigateToArtist: (name: String, browseId: String?) -> Unit = { _, _ -> },
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    viewModel: AlbumDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(albumId) {
        viewModel.loadAlbum(albumId = albumId)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (val state = uiState) {
                            is UiState.Success -> state.data.title
                            else -> albumTitle.ifBlank { "Album" }
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
        ) {
            when (val state = uiState) {
                is UiState.Loading -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        AlbumHeaderSkeleton(
                            fallbackTitle = albumTitle,
                            fallbackArtist = artistName,
                            fallbackThumbnail = thumbnailUrl
                        )
                        SongListSkeleton(
                            contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)
                        )
                    }
                }
                is UiState.Error -> {
                    ErrorStateView(
                        message = state.message,
                        onRetry = { viewModel.loadAlbum(albumId = albumId, forceRefresh = true) },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is UiState.Success -> {
                    val details = state.data
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)
                    ) {
                        item(key = "album_header") {
                            AlbumHeader(
                                details = details,
                                onNavigateToArtist = onNavigateToArtist,
                                onPlayAll = {
                                    viewModel.playAll(details)
                                    if (details.songs.isNotEmpty()) {
                                        onShowFullPlayer()
                                    }
                                },
                                onShuffle = {
                                    viewModel.shufflePlay(details)
                                    if (details.songs.isNotEmpty()) {
                                        onShowFullPlayer()
                                    }
                                }
                            )
                        }

                        if (details.songs.isEmpty()) {
                            item(key = "empty_songs") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No tracks found for this album.",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            item(key = "songs_header") {
                                Text(
                                    text = "Songs",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }

                            items(
                                items = details.songs,
                                key = { it.videoId }
                            ) { track ->
                                AlbumSongItem(
                                    track = track,
                                    albumArtUrl = details.thumbnailUrl,
                                    onClick = {
                                        viewModel.playSong(track, details)
                                        onShowFullPlayer()
                                    }
                                )
                            }
                        }

                        // Render Videos Section if present
                        if (details.videos.isNotEmpty()) {
                            item(key = "videos_header") {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Videos",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }

                            item(key = "videos_carousel") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(
                                        items = details.videos,
                                        key = { it.videoId }
                                    ) { video ->
                                        VideoCard(
                                            video = video,
                                            onClick = {
                                                viewModel.playVideo(video, details)
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

@Composable
private fun AlbumHeader(
    details: AlbumDetails,
    onNavigateToArtist: (name: String, browseId: String?) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit
) {
    val thumbnailShape = LocalThumbnailShape.current.toComposeShape()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier
                .size(200.dp)
                .clip(thumbnailShape),
            shape = thumbnailShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp
        ) {
            if (details.thumbnailUrl.isNotBlank()) {
                SongThumbnail(
                    artworkUrl = details.thumbnailUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = details.title,
                    highQuality = true,
                    targetSizePx = 720
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Album,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(72.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = details.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Multiple Clickable Artists Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            details.artists.forEachIndexed { index, name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onNavigateToArtist(name, null) }
                )
                if (index < details.artists.size - 1) {
                    Text(
                        text = ", ",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            details.albumType?.let { type ->
                SuggestionChip(
                    onClick = {},
                    label = { Text(type) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }

            val metadataText = buildString {
                details.year?.let { append(it) }
                if (details.trackCount > 0) {
                    if (isNotEmpty()) append("  •  ")
                    append("${details.trackCount} tracks")
                }
            }
            if (metadataText.isNotEmpty()) {
                Text(
                    text = metadataText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = onPlayAll,
                enabled = details.songs.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Play All")
            }

            OutlinedButton(
                onClick = onShuffle,
                enabled = details.songs.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Shuffle")
            }
        }
    }
}

@Composable
private fun AlbumSongItem(
    track: AlbumTrack,
    albumArtUrl: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = track.trackNumber.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.width(8.dp))

        SongThumbnail(
            artworkUrl = albumArtUrl.takeIf { !it.isNullOrBlank() } ?: "https://i.ytimg.com/vi/${track.videoId}/hqdefault.jpg",
            modifier = Modifier
                .size(40.dp)
                .clip(LocalThumbnailShape.current.toComposeShape()),
            contentDescription = track.title,
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = track.artists.joinToString(", ").ifBlank { " Unknown artist" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = track.durationText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VideoCard(
    video: AlbumVideo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .width(180.dp)
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            ) {
                if (video.thumbnailUrl.isNotBlank()) {
                    SongThumbnail(
                        artworkUrl = video.thumbnailUrl,
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = video.title,
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (!video.durationText.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.7f)
                    ) {
                        Text(
                            text = video.durationText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
private fun AlbumHeaderSkeleton(
    fallbackTitle: String,
    fallbackArtist: String,
    fallbackThumbnail: String?
) {
    val thumbnailShape = LocalThumbnailShape.current.toComposeShape()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier
                .size(200.dp)
                .clip(thumbnailShape),
            shape = thumbnailShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            if (!fallbackThumbnail.isNullOrBlank()) {
                SongThumbnail(
                    artworkUrl = fallbackThumbnail,
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = fallbackTitle,
                    highQuality = true,
                    targetSizePx = 720
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Album,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(72.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = fallbackTitle.ifBlank { "Loading Album..." },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = fallbackArtist.ifBlank { "Loading artist..." },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        LinearProgressIndicator(
            modifier = Modifier.width(140.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun ErrorStateView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Oops! Something went wrong",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}
