package com.sonicmusic.app.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.domain.model.ThumbnailShape
import com.sonicmusic.app.features.player.presentation.PlayerViewModel
import com.sonicmusic.app.shared.components.SongThumbnail
import com.sonicmusic.app.theme.pressScale

/**
 * Modern Material 3 Circular Mini Player for SonicMusic.
 * Displays only the song thumbnail in a circular vinyl shape with a circular seek/progress indicator
 * around the perimeter. Positioned separately alongside the modern floating navbar without merging.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CircularMiniPlayer(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 58.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val currentSong by viewModel.currentSong.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val isBuffering by viewModel.isBuffering.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current

    val reduceMotion = com.sonicmusic.app.theme.shouldReduceMotion()
    val interactionSource = remember { MutableInteractionSource() }

    AnimatedVisibility(
        visible = currentSong != null,
        enter = if (reduceMotion) {
            fadeIn(animationSpec = tween(150))
        } else {
            scaleIn(
                initialScale = 0.7f,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)
            ) + fadeIn(animationSpec = tween(200))
        },
        exit = if (reduceMotion) {
            fadeOut(animationSpec = tween(150))
        } else {
            scaleOut(
                targetScale = 0.7f,
                animationSpec = tween(150)
            ) + fadeOut(animationSpec = tween(150))
        },
        modifier = modifier
    ) {
        // Smooth vinyl record rotation while playing - executed on RenderNode via graphicsLayer
        val infiniteTransition = rememberInfiniteTransition(label = "vinyl_spin_transition")
        val continuousRotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 8000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "vinyl_spin"
        )

        val containerScale by animateFloatAsState(
            targetValue = if (isPlaying && !reduceMotion) 1.0f else 0.96f,
            animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
            label = "mini_player_scale"
        )

        Surface(
            modifier = Modifier
                .size(size)
                .pressScale(interactionSource, targetScale = 0.92f)
                .graphicsLayer {
                    scaleX = containerScale
                    scaleY = containerScale
                }
                .clip(CircleShape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true, radius = size / 2),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onExpand()
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.togglePlayPause()
                    }
                ),
            shape = CircleShape,
            color = containerColor,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // Circular Seek / Progress Indicator around the thumbnail (isolated recomposition)
                MiniPlayerProgressRing(
                    viewModel = viewModel,
                    isBuffering = isBuffering,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.5.dp)
                )

                // Clean Circular Song Thumbnail inside the indicator ring with zero-recomposition GPU rotation
                Box(
                    modifier = Modifier
                        .size(size - 12.dp)
                        .clip(CircleShape)
                        .graphicsLayer {
                            rotationZ = if (isPlaying && !isBuffering && !reduceMotion) continuousRotation else 0f
                        },
                    contentAlignment = Alignment.Center
                ) {
                    SongThumbnail(
                        artworkUrl = currentSong?.thumbnailUrl,
                        shape = ThumbnailShape.CIRCLE,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * Isolated progress indicator ring to prevent parent MiniPlayer recompositions on position ticks.
 */
@Composable
private fun MiniPlayerProgressRing(
    viewModel: PlayerViewModel,
    isBuffering: Boolean,
    modifier: Modifier = Modifier
) {
    val duration by viewModel.duration.collectAsStateWithLifecycle()
    val currentPosition by viewModel.currentPosition.collectAsStateWithLifecycle()

    val progress = if (duration > 0L) {
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    if (isBuffering) {
        CircularProgressIndicator(
            modifier = modifier,
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
            strokeCap = StrokeCap.Round
        )
    } else {
        CircularProgressIndicator(
            progress = { progress },
            modifier = modifier,
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
            strokeCap = StrokeCap.Round
        )
    }
}
