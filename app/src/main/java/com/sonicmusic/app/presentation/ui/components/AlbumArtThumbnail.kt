package com.sonicmusic.app.presentation.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import com.sonicmusic.app.core.util.ThumbnailUrlUtils
import com.sonicmusic.app.domain.model.ThumbnailShape
import com.sonicmusic.app.presentation.ui.theme.MorphComposeShape
import com.sonicmusic.app.presentation.ui.theme.toComposeShape
import com.sonicmusic.app.presentation.ui.theme.toRoundedPolygon
import androidx.graphics.shapes.Morph

/**
 * Reusable Composable that loads album art images using Coil, handles candidate URL retries,
 * and clips the image to a user-configured shape with smooth morph animation transitions.
 */
@Composable
fun AlbumArtThumbnail(
    imageUrl: String?,
    shape: ThumbnailShape,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    crossfade: Boolean = true,
    targetSizePx: Int = 320,
    highQuality: Boolean = false,
    shadowElevation: Dp = 0.dp,
    backgroundColor: Color = Color.Transparent
) {
    val context = LocalContext.current

    // Candidate URL resolution for fallback handling
    val candidateUrls = remember(imageUrl, highQuality) {
        if (highQuality) ThumbnailUrlUtils.buildCandidates(imageUrl)
        else ThumbnailUrlUtils.buildDisplayCandidates(imageUrl)
    }
    var candidateIndex by remember(candidateUrls) { mutableIntStateOf(0) }
    val modelUrl = candidateUrls.getOrNull(candidateIndex) ?: imageUrl
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

    // Animating shape morph transitions
    var previousShape by remember { mutableStateOf(shape) }
    var targetShape by remember { mutableStateOf(shape) }
    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(shape) {
        if (shape != targetShape) {
            previousShape = targetShape
            targetShape = shape
            animProgress.snapTo(0f)
            animProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
            )
            previousShape = shape // Reset morph state to prevent infinite rebuilds
        }
    }

    // Build the morphing Compose Shape
    val morphingShape = remember(previousShape, targetShape, animProgress.value) {
        if (previousShape == targetShape) {
            targetShape.toComposeShape()
        } else {
            val startPoly = previousShape.toRoundedPolygon()
            val endPoly = targetShape.toRoundedPolygon()
            MorphComposeShape(Morph(startPoly, endPoly), animProgress.value)
        }
    }

    // Animated padding inset to prevent layout pops when changing shapes
    val targetPadding = when (targetShape) {
        ThumbnailShape.CLOVER, ThumbnailShape.BURST, ThumbnailShape.SCALLOP -> 4.dp
        ThumbnailShape.CIRCLE, ThumbnailShape.COOKIE, ThumbnailShape.ARCH -> 2.dp
        else -> 0.dp
    }
    val animatedPadding by animateDpAsState(
        targetValue = targetPadding,
        animationSpec = tween(durationMillis = 500),
        label = "thumbnailPadding"
    )

    AsyncImage(
        model = imageRequest,
        contentDescription = contentDescription,
        modifier = modifier
            .padding(animatedPadding)
            .shadow(shadowElevation, morphingShape)
            .background(backgroundColor, morphingShape)
            .clip(morphingShape),
        contentScale = contentScale,
        onError = {
            if (candidateIndex < candidateUrls.lastIndex) {
                candidateIndex += 1
            }
        }
    )
}
