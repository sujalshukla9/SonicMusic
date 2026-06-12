package com.sonicmusic.app.presentation.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import com.sonicmusic.app.core.util.ThumbnailUrlUtils

@Composable
fun SongThumbnail(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    crossfade: Boolean = true,
    targetSizePx: Int = 320,
    highQuality: Boolean = false
) {
    val context = LocalContext.current

    // High quality uses maxresdefault candidates for full-screen artwork;
    // display quality uses hqdefault (~15KB) for lists/grids.
    val candidateUrls = remember(artworkUrl, highQuality) {
        if (highQuality) ThumbnailUrlUtils.buildCandidates(artworkUrl)
        else ThumbnailUrlUtils.buildDisplayCandidates(artworkUrl)
    }
    var candidateIndex by remember(candidateUrls) { mutableIntStateOf(0) }
    val modelUrl = candidateUrls.getOrNull(candidateIndex) ?: artworkUrl
    val imageRequest = remember(context, modelUrl, crossfade, targetSizePx) {
        ImageRequest.Builder(context)
            .data(modelUrl)
            .allowHardware(true)
            .crossfade(crossfade)
            .size(Size(targetSizePx, targetSizePx))
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .placeholder(android.R.color.transparent)
            .error(android.R.drawable.ic_media_play)
            .build()
    }

    AsyncImage(
        model = imageRequest,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onError = {
            if (candidateIndex < candidateUrls.lastIndex) {
                candidateIndex += 1
            }
        }
    )
}
