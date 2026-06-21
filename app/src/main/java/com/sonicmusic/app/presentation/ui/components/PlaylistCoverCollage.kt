package com.sonicmusic.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.domain.model.Playlist

@Composable
fun PlaylistCoverCollage(
    playlist: Playlist,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        if (!playlist.coverArtUrl.isNullOrBlank()) {
            // Explicit cover art always takes priority
            SongThumbnail(
                artworkUrl = playlist.coverArtUrl,
                modifier = Modifier.fillMaxSize(),
                contentDescription = null
            )
        } else if (playlist.songs.size >= 4) {
            // 4-song collage grid
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        SongThumbnail(artworkUrl = playlist.songs[0].thumbnailUrl, modifier = Modifier.fillMaxSize(), contentDescription = null)
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        SongThumbnail(artworkUrl = playlist.songs[1].thumbnailUrl, modifier = Modifier.fillMaxSize(), contentDescription = null)
                    }
                }
                Row(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        SongThumbnail(artworkUrl = playlist.songs[2].thumbnailUrl, modifier = Modifier.fillMaxSize(), contentDescription = null)
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        SongThumbnail(artworkUrl = playlist.songs[3].thumbnailUrl, modifier = Modifier.fillMaxSize(), contentDescription = null)
                    }
                }
            }
        } else if (playlist.songs.isNotEmpty()) {
            // 1–3 songs: show first song's art
            SongThumbnail(
                artworkUrl = playlist.songs.first().thumbnailUrl,
                modifier = Modifier.fillMaxSize(),
                contentDescription = null
            )
        } else {
            // Empty playlist: show placeholder icon
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}
