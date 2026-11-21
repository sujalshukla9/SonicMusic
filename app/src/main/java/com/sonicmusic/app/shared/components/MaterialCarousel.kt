package com.sonicmusic.app.shared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.deviceLocalSongId
import com.sonicmusic.app.features.player.presentation.sheets.SongActionSource
import com.sonicmusic.app.theme.Dimens
import com.sonicmusic.app.theme.LocalThumbnailShape
import com.sonicmusic.app.theme.toComposeShape

/**
 * Material 3 Song Carousel Card displaying the song title directly overlaid
 * on the thumbnail with a protective dark gradient, matching the visual style
 * of the Listen Again grid items.
 */
@Composable
fun SongCarouselCard(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = LocalThumbnailShape.current.toComposeShape(),
    isEnabled: Boolean = true,
    source: SongActionSource = SongActionSource.HOME
) {
    Card(
        modifier = modifier
            .aspectRatio(1f)
            .alpha(if (isEnabled) 1f else 0.4f)
            .songPressable(
                song = song,
                source = source,
                enabled = isEnabled,
                onClick = onClick
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SongThumbnail(
                artworkUrl = song.thumbnailUrl,
                modifier = Modifier.fillMaxSize(),
                contentDescription = song.title
            )

            // Dark gradient overlay for text readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.25f),
                                Color.Black.copy(alpha = 0.75f)
                            ),
                            startY = 50f
                        )
                    )
            )

            // Song title at bottom start
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

/**
 * Reusable Material 3 Carousel for a list of songs using Jetpack Compose Material 3
 * carousel components (HorizontalUncontainedCarousel / HorizontalMultiBrowseCarousel).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongCarousel(
    songs: List<Song>,
    onSongClick: (Song) -> Unit,
    modifier: Modifier = Modifier,
    itemWidth: Dp = Dimens.cardWidth,
    itemSpacing: Dp = 12.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = Dimens.screenPadding),
    isOnline: Boolean = true,
    downloadedSongIds: Set<String> = emptySet(),
    useMultiBrowse: Boolean = false
) {
    if (songs.isEmpty()) return

    val carouselState = rememberCarouselState { songs.size }
    val thumbnailShape = LocalThumbnailShape.current
    val currentShape = remember(thumbnailShape) { thumbnailShape.toComposeShape() }

    if (useMultiBrowse) {
        HorizontalMultiBrowseCarousel(
            state = carouselState,
            preferredItemWidth = itemWidth,
            itemSpacing = itemSpacing,
            contentPadding = contentPadding,
            modifier = modifier
                .fillMaxWidth()
                .height(itemWidth)
        ) { index ->
            val song = songs[index]
            val isSongEnabled = isOnline || song.id.deviceLocalSongId() != null || song.id in downloadedSongIds

            key("${song.id}_$index") {
                SongCarouselCard(
                    song = song,
                    onClick = { if (isSongEnabled) onSongClick(song) },
                    isEnabled = isSongEnabled,
                    shape = currentShape,
                    modifier = Modifier
                        .maskClip(currentShape)
                        .fillMaxSize()
                )
            }
        }
    } else {
        HorizontalUncontainedCarousel(
            state = carouselState,
            itemWidth = itemWidth,
            itemSpacing = itemSpacing,
            contentPadding = contentPadding,
            modifier = modifier
                .fillMaxWidth()
                .height(itemWidth)
        ) { index ->
            val song = songs[index]
            val isSongEnabled = isOnline || song.id.deviceLocalSongId() != null || song.id in downloadedSongIds

            key("${song.id}_$index") {
                SongCarouselCard(
                    song = song,
                    onClick = { if (isSongEnabled) onSongClick(song) },
                    isEnabled = isSongEnabled,
                    shape = currentShape,
                    modifier = Modifier
                        .maskClip(currentShape)
                        .fillMaxSize()
                )
            }
        }
    }
}
