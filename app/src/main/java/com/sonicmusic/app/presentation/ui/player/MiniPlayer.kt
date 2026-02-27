package com.sonicmusic.app.presentation.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.presentation.ui.components.SongThumbnail
import com.sonicmusic.app.presentation.viewmodel.PlayerViewModel
import kotlin.math.roundToInt

/**
 * Material 3 Expressive MiniPlayer
 * 
 * Features:
 * - Swipe gestures for skip next/previous
 * - Large cover art with shadow
 * - Tonal buttons with proper M3 styling
 * - Smooth progress indicator
 * - Premium glassmorphism effect
 */
@Composable
fun MiniPlayer(
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
    backgroundColorOverride: Color? = null,
    onExpand: () -> Unit = {}
) {
    val currentSong by viewModel.currentSong.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val isBuffering by viewModel.isBuffering.collectAsStateWithLifecycle()
    val isLiked by viewModel.isLiked.collectAsStateWithLifecycle()
    val dynamicColorsEnabled by viewModel.dynamicColorsEnabled.collectAsStateWithLifecycle()
    val dynamicColorIntensity by viewModel.dynamicColorIntensity.collectAsStateWithLifecycle()

    if (currentSong == null) return

    var offsetX by remember { mutableFloatStateOf(0f) }
    val animatedOffsetX by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "offset"
    )
    
    val swipeThreshold = 120f
    val isSwipingNext = offsetX < -swipeThreshold
    val isSwipingPrevious = offsetX > swipeThreshold
    val artworkPalette = rememberPlayerArtworkPalette(
        artworkUrl = currentSong?.thumbnailUrl,
        fallbackColorScheme = MaterialTheme.colorScheme,
        enabled = dynamicColorsEnabled,
        intensity = dynamicColorIntensity / 100f
    )
    MaterialTheme(colorScheme = artworkPalette.colorScheme) {
        val colorScheme = MaterialTheme.colorScheme
        val accentColor by animateColorAsState(
            targetValue = artworkPalette.accent,
            animationSpec = tween(1000),
            label = "miniAccent"
        )
        val onAccentColor by animateColorAsState(
            targetValue = artworkPalette.onAccent,
            animationSpec = tween(1000),
            label = "miniOnAccent"
        )

        val backgroundColor by animateColorAsState(
            targetValue = backgroundColorOverride ?: colorScheme.surfaceContainerHigh,
            animationSpec = tween(1000),
            label = "bgColor"
        )

        Surface(
            modifier = modifier
                .fillMaxWidth()
                .height(76.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            when {
                                offsetX < -swipeThreshold -> viewModel.skipToNext()
                                offsetX > swipeThreshold -> viewModel.skipToPrevious()
                            }
                            offsetX = 0f
                        },
                        onDragCancel = { offsetX = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            offsetX = (offsetX + dragAmount).coerceIn(-200f, 200f)
                        }
                    )
                }
                .clickable(onClick = onExpand),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = backgroundColor,
            tonalElevation = 6.dp,
            shadowElevation = 2.dp
        ) {
            Box {
                // Swipe indicators (background)
                // Main content
                Column(
                    modifier = Modifier.offset { IntOffset(animatedOffsetX.roundToInt(), 0) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Album Art with shadow
                        SongThumbnail(
                            artworkUrl = currentSong?.thumbnailUrl,
                            modifier = Modifier
                                .size(52.dp)
                                .shadow(8.dp, RoundedCornerShape(8.dp))
                                .clip(RoundedCornerShape(8.dp))
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        // Song Info
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentSong?.title ?: "",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = currentSong?.artist ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Like Button
                        FilledTonalIconButton(
                            onClick = { viewModel.toggleLike() },
                            modifier = Modifier.size(34.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (isLiked) {
                                    colorScheme.errorContainer
                                } else {
                                    colorScheme.surfaceContainerHighest
                                },
                                contentColor = if (isLiked) {
                                    colorScheme.onErrorContainer
                                } else {
                                    colorScheme.onSurfaceVariant
                                }
                            )
                        ) {
                            Icon(
                                imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = if (isLiked) "Unlike" else "Like",
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Play/Pause Button - Large M3 FAB style
                        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            if (isBuffering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = accentColor,
                                    strokeWidth = 3.dp
                                )
                            } else {
                                FilledIconButton(
                                    onClick = { viewModel.togglePlayPause() },
                                    modifier = Modifier.size(48.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = accentColor,
                                        contentColor = onAccentColor
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Progress Bar - Isolated to prevent full recomposition
                    MiniPlayerProgress(
                        viewModel = viewModel,
                        progressColor = accentColor
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerProgress(
    viewModel: PlayerViewModel,
    progressColor: Color
) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp),
        color = progressColor,
        trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        strokeCap = StrokeCap.Round
    )
}
