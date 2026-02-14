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
import coil.request.ImageRequest
import com.sonicmusic.app.data.util.ThumbnailUrlUtils

@Composable
fun SongThumbnail(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    crossfade: Boolean = true
) {
    val context = LocalContext.current

    val candidateUrls = remember(artworkUrl) {
        ThumbnailUrlUtils.buildCandidates(artworkUrl)
    }
    var candidateIndex by remember(artworkUrl) { mutableIntStateOf(0) }
    val modelUrl = candidateUrls.getOrNull(candidateIndex) ?: artworkUrl

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(modelUrl)
            .allowHardware(false)
            .crossfade(crossfade)
            .build(),
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
