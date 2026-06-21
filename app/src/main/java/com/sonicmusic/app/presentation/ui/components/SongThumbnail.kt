package com.sonicmusic.app.presentation.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.domain.model.ThumbnailShape
import com.sonicmusic.app.presentation.ui.theme.LocalThumbnailShape

/**
 * Delegating thumbnail composable that uses the dynamically configured AlbumArtThumbnail
 * clipped with the current LocalThumbnailShape composition local by default.
 */
@Composable
fun SongThumbnail(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    crossfade: Boolean = true,
    targetSizePx: Int = 320,
    highQuality: Boolean = false,
    shape: ThumbnailShape = LocalThumbnailShape.current,
    shadowElevation: Dp = 0.dp,
    backgroundColor: Color = Color.Transparent,
    isLarge: Boolean = false
) {
    AlbumArtThumbnail(
        imageUrl = artworkUrl,
        shape = shape,
        modifier = modifier,
        contentDescription = contentDescription,
        contentScale = contentScale,
        crossfade = crossfade,
        targetSizePx = targetSizePx,
        highQuality = highQuality,
        shadowElevation = shadowElevation,
        backgroundColor = backgroundColor,
        isLarge = isLarge
    )
}
