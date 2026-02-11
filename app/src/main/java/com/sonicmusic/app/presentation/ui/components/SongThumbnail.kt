package com.sonicmusic.app.presentation.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sonicmusic.app.R

@Composable
fun SongThumbnail(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    crossfade: Boolean = true
) {
    val context = LocalContext.current
    
    // Logic to ensure we use the highest resolution available
    val highResUrl = remember(artworkUrl) {
        artworkUrl?.let { url ->
            when {
                url.contains("hqdefault.jpg") -> url.replace("hqdefault.jpg", "maxresdefault.jpg")
                url.contains("mqdefault.jpg") -> url.replace("mqdefault.jpg", "maxresdefault.jpg")
                url.contains("sddefault.jpg") -> url.replace("sddefault.jpg", "maxresdefault.jpg")
                url.contains("w120-h120") -> url.replace("w120-h120", "w1080-h1080")
                url.contains("w60-h60") -> url.replace("w60-h60", "w1080-h1080")
                url.contains("s120") -> url.replace("s120", "s1080")
                url.contains("=s") -> url.replace(Regex("=s[0-9]+.*"), "=s1080") // Catch-all for other sizes
                url.contains("=w") -> url.replace(Regex("=w[0-9]+-h[0-9]+.*"), "=w1080-h1080-l90-rj") // Catch-all for width/height
                else -> url
            }
        }
    }

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(highResUrl)
            .crossfade(crossfade)
            .build(),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale
    )
}
