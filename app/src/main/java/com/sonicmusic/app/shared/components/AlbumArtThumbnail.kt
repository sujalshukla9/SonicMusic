package com.sonicmusic.app.shared.components

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.sonicmusic.app.theme.MotionTokens
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import com.sonicmusic.app.core.util.ThumbnailUrlUtils
import com.sonicmusic.app.domain.model.ThumbnailShape
import com.sonicmusic.app.theme.MorphComposeShape
import com.sonicmusic.app.theme.toComposeShape
import com.sonicmusic.app.theme.toRoundedPolygon
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
    backgroundColor: Color = Color.Transparent,
    isLarge: Boolean = false
) {
    val context = LocalContext.current

    // Candidate URL resolution for fallback handling
    val reduceMotion = com.sonicmusic.app.theme.shouldReduceMotion()
    val displaySizePx = when {
        targetSizePx <= 256 -> 256
        targetSizePx <= 448 -> 448
        else -> 640
    }
    val candidateUrls = remember(imageUrl, highQuality, displaySizePx) {
        if (highQuality) ThumbnailUrlUtils.buildCandidates(imageUrl)
        else ThumbnailUrlUtils.buildDisplayCandidates(imageUrl, sizePx = displaySizePx)
    }
    var candidateIndex by remember(candidateUrls) { mutableIntStateOf(0) }
    val modelUrl = candidateUrls.getOrNull(candidateIndex) ?: imageUrl
    val crossfadeDuration = if (crossfade && !reduceMotion) MotionTokens.DurationStandard else 0
    val imageRequest = remember(context, modelUrl, crossfadeDuration, targetSizePx) {
        ImageRequest.Builder(context)
            .data(modelUrl)
            .allowHardware(true)
            .crossfade(crossfadeDuration)
            .size(Size(targetSizePx, targetSizePx))
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .placeholder(android.R.color.transparent)
            .error(android.R.color.transparent)
            .build()
    }

    // Animating shape morph transitions
    var previousShape by remember { mutableStateOf(shape) }
    var targetShape by remember { mutableStateOf(shape) }
    val animProgress = remember { Animatable(1f) }

    LaunchedEffect(shape, reduceMotion) {
        if (shape != targetShape) {
            previousShape = targetShape
            targetShape = shape
            if (reduceMotion) {
                animProgress.snapTo(1f)
            } else {
                animProgress.snapTo(0f)
                animProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = MotionTokens.standardTween()
                )
            }
            previousShape = shape // Reset morph state to prevent infinite rebuilds
        }
    }

    // Build the morphing Compose Shape - Fast path for static shapes (99.9% of runtime)
    val staticShape = remember(targetShape, isLarge) { targetShape.toComposeShape(isLarge) }
    val morph = remember(previousShape, targetShape, reduceMotion) {
        if (previousShape != targetShape && !reduceMotion) {
            Morph(previousShape.toRoundedPolygon(), targetShape.toRoundedPolygon())
        } else null
    }
    val progress = if (morph != null) animProgress.value else 1f
    val morphingShape = remember(staticShape, morph, progress) {
        if (morph != null && progress < 1f) MorphComposeShape(morph, progress) else staticShape
    }

    // Animated padding inset to prevent layout pops when changing shapes
    val targetPadding = when (targetShape) {
        ThumbnailShape.CLOVER, ThumbnailShape.BURST, ThumbnailShape.SCALLOP -> 4.dp
        ThumbnailShape.CIRCLE, ThumbnailShape.COOKIE, ThumbnailShape.ARCH -> 2.dp
        else -> 0.dp
    }
    val animatedPadding by animateDpAsState(
        targetValue = targetPadding,
        animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else MotionTokens.spatial(),
        label = "thumbnailPadding"
    )

    val surfaceContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow
    val effectiveBackgroundColor = if (backgroundColor == Color.Transparent) surfaceContainerColor else backgroundColor

    val shadowModifier = if (shadowElevation > 0.dp) {
        val safeShadowShape = when (targetShape) {
            ThumbnailShape.CIRCLE -> androidx.compose.foundation.shape.CircleShape
            ThumbnailShape.SQUARE -> androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
            ThumbnailShape.ROUNDED_SQUARE -> androidx.compose.foundation.shape.RoundedCornerShape(if (isLarge) 28.dp else 12.dp)
            ThumbnailShape.ARCH -> com.sonicmusic.app.theme.ArchShape
            ThumbnailShape.PILL -> com.sonicmusic.app.theme.PillShape
            else -> androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        }
        Modifier.shadow(shadowElevation, safeShadowShape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .padding(animatedPadding)
            .then(shadowModifier)
            .clip(morphingShape)
            .background(effectiveBackgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            modifier = Modifier.fillMaxSize(0.35f)
        )

        AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
            onError = {
                if (candidateIndex < candidateUrls.lastIndex) {
                    candidateIndex += 1
                }
            }
        )
    }
}
