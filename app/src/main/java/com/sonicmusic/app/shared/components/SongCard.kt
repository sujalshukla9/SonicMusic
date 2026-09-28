package com.sonicmusic.app.shared.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.features.player.presentation.sheets.SongActionSource
import com.sonicmusic.app.theme.LocalThumbnailShape
import com.sonicmusic.app.theme.pressable
import com.sonicmusic.app.theme.toComposeShape

@Composable
fun SongCard(
    song: Song,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    source: SongActionSource = SongActionSource.OTHER,
    modifier: Modifier = Modifier
) {
    val currentShape = LocalThumbnailShape.current.toComposeShape()
    val pressModifier = if (onLongClick != null) {
        modifier.pressable(onClick = onClick, onLongClick = onLongClick)
    } else {
        modifier.songPressable(song = song, source = source, onClick = onClick)
    }

    Column(
        modifier = pressModifier.width(140.dp)
    ) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = currentShape,
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            SongThumbnail(
                artworkUrl = song.thumbnailUrl,
                contentDescription = "${song.title} album art",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = song.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        
        Text(
            text = song.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun SongCardCompact(
    song: Song,
    onClick: () -> Unit,
    source: SongActionSource = SongActionSource.OTHER,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    val currentShape = LocalThumbnailShape.current.toComposeShape()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .songPressable(song = song, source = source, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = currentShape,
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            SongThumbnail(
                artworkUrl = song.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        trailing?.invoke()
    }
}