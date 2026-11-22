package com.sonicmusic.app.shared.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.ThumbnailShape
import com.sonicmusic.app.theme.pressScale

@Composable
internal fun ReleaseAlbumCard(
    album: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artworkCrossfade: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val details = remember(album.artist, album.year) {
        listOfNotNull(
            album.artist.takeIf { it.isNotBlank() && !it.equals("Unknown artist", ignoreCase = true) },
            album.year?.toString()
        ).joinToString(" • ")
    }
    Surface(
        onClick = onClick,
        modifier = modifier.pressScale(interaction, targetScale = 0.97f),
        interactionSource = interaction,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large
    ) {
        ArtworkCardContent(album.thumbnailUrl, album.title, details, artworkCrossfade, targetSizePx = 440)
    }
}

@Composable
internal fun ArtworkCardContent(
    artworkUrl: String?,
    title: String,
    subtitle: String,
    artworkCrossfade: Boolean,
    targetSizePx: Int
) {
    Column(Modifier.fillMaxWidth()) {
        SongThumbnail(
            artworkUrl = artworkUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            shape = ThumbnailShape.SQUARE,
            crossfade = artworkCrossfade,
            targetSizePx = targetSizePx
        )
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
