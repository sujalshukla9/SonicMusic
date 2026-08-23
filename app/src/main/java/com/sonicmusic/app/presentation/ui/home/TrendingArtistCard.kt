package com.sonicmusic.app.presentation.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import coil.compose.AsyncImage
import com.sonicmusic.app.domain.model.Song

/**
 * Horizontal scrollable row of circular artist avatar cards for the Trending Artists section.
 *
 * Each card shows the artist's thumbnail in a circle with their name below,
 * plus a follow/unfollow toggle button.
 */
@Composable
fun TrendingArtistRow(
    artists: List<Song>,
    followedArtistIds: Set<String>,
    onArtistClick: (Song) -> Unit,
    onFollowToggle: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    if (artists.isEmpty()) return

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(
            items = artists,
            key = { it.id }
        ) { artist ->
            TrendingArtistCard(
                artist = artist,
                isFollowed = artist.id in followedArtistIds || artist.artistId in followedArtistIds,
                onClick = { onArtistClick(artist) },
                onFollowToggle = { onFollowToggle(artist) }
            )
        }
    }
}

/**
 * Individual circular artist card with avatar, name, and follow toggle.
 */
@Composable
fun TrendingArtistCard(
    artist: Song,
    isFollowed: Boolean,
    onClick: () -> Unit,
    onFollowToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val followIconColor by animateColorAsState(
        targetValue = if (isFollowed) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        },
        animationSpec = tween(durationMillis = 300),
        label = "follow_color"
    )

    Column(
        modifier = modifier
            .width(96.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Circular avatar with gradient border
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(80.dp)
                .then(
                    if (isFollowed) {
                        Modifier.border(
                            width = 2.dp,
                            brush = Brush.sweepGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary,
                                    MaterialTheme.colorScheme.primary
                                )
                            ),
                            shape = CircleShape
                        )
                    } else {
                        Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            shape = CircleShape
                        )
                    }
                )
                .padding(2.dp)
        ) {
            if (artist.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = artist.thumbnailUrl,
                    contentDescription = artist.artist,
                    modifier = Modifier
                        .size(74.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Fallback avatar
                Box(
                    modifier = Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Artist name
        Text(
            text = artist.artist.ifBlank { artist.title },
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Medium,
                lineHeight = 14.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        // Follow button
        IconButton(
            onClick = onFollowToggle,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = if (isFollowed) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFollowed) "Unfollow" else " Follow",
                tint = followIconColor,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
