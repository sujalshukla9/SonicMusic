package com.sonicmusic.app.features.player.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.foundation.layout.widthIn
import com.sonicmusic.app.theme.Dimens
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.shared.components.SongThumbnail
import com.sonicmusic.app.features.player.presentation.PlayerViewModel
import com.sonicmusic.app.domain.model.ThumbnailShape
import com.sonicmusic.app.theme.toComposeShape
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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
    val thumbnailShape by viewModel.thumbnailShape.collectAsStateWithLifecycle()
    val reduceMotion = com.sonicmusic.app.theme.shouldReduceMotion()

    // Cache the most recent song so exit animation smoothly slides down without becoming blank
    var lastSong by remember { mutableStateOf(currentSong) }
    LaunchedEffect(currentSong) {
        if (currentSong != null) {
            lastSong = currentSong
        }
    }
    val songToDisplay = currentSong ?: lastSong

    androidx.compose.animation.AnimatedVisibility(
        visible = currentSong != null && songToDisplay != null,
        enter = if (reduceMotion) fadeIn(androidx.compose.animation.core.snap()) else {
            androidx.compose.animation.slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(
                    durationMillis = com.sonicmusic.app.theme.MotionTokens.DurationStandard,
                    easing = com.sonicmusic.app.theme.MotionTokens.EmphasizedDecelerate
                )
            ) + fadeIn(animationSpec = tween(com.sonicmusic.app.theme.MotionTokens.DurationFast))
        },
        exit = if (reduceMotion) fadeOut(androidx.compose.animation.core.snap()) else {
            androidx.compose.animation.slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(
                    durationMillis = com.sonicmusic.app.theme.MotionTokens.DurationFast,
                    easing = com.sonicmusic.app.theme.MotionTokens.EmphasizedAccelerate
                )
            ) + fadeOut(animationSpec = tween(com.sonicmusic.app.theme.MotionTokens.DurationFast))
        },
        modifier = modifier
    ) {
        val miniHeight = Dimens.miniPlayerHeight
        val artSize = Dimens.miniPlayerArtSize

        // Swipe offset driven by an Animatable inside the gesture handlers.
        val offsetX = remember { Animatable(0f) }
        val swipeScope = rememberCoroutineScope()
        
        val swipeThreshold = 120f
        val colorScheme = MaterialTheme.colorScheme
        val accentColor = colorScheme.primary
        val onAccentColor = colorScheme.onPrimary
        val backgroundColor = backgroundColorOverride ?: colorScheme.surfaceContainerHigh

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
        Surface(
            modifier = Modifier
                .widthIn(max = Dimens.maxContentWidth)
                .fillMaxWidth()
                .height(miniHeight)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            when {
                                offsetX.value < -swipeThreshold -> {
                                    if (viewModel.playerUiState.value.canSkipNext) {
                                        viewModel.skipToNext()
                                    }
                                }
                                offsetX.value > swipeThreshold -> {
                                    if (viewModel.playerUiState.value.canSkipPrevious) {
                                        viewModel.forceSkipToPrevious()
                                    }
                                }
                            }
                            swipeScope.launch {
                                offsetX.animateTo(0f, tween(durationMillis = 150, easing = FastOutSlowInEasing))
                            }
                        },
                        onDragCancel = {
                            swipeScope.launch {
                                offsetX.animateTo(0f, tween(durationMillis = 150, easing = FastOutSlowInEasing))
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            swipeScope.launch {
                                offsetX.snapTo((offsetX.value + dragAmount).coerceIn(-200f, 200f))
                            }
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
                    modifier = Modifier.offset { IntOffset(offsetX.value.roundToInt(), 0) }
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
                            artworkUrl = songToDisplay?.thumbnailUrl,
                            shape = thumbnailShape,
                            shadowElevation = 8.dp,
                            modifier = Modifier.size(artSize)
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        // Song Info
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = songToDisplay?.title ?: "",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = songToDisplay?.artist ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Like Button — AnimatedContent with spring pop for heart fill
                        FilledTonalIconButton(
                            onClick = { viewModel.toggleLike() },
                            modifier = Modifier.size(40.dp),
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
                            AnimatedContent(
                                targetState = isLiked,
                                transitionSpec = {
                                    if (targetState) {
                                        (scaleIn(
                                            animationSpec = spring(dampingRatio = 0.55f, stiffness = 600f),
                                            initialScale = 0.6f
                                        ) + fadeIn(tween(100)))
                                            .togetherWith(scaleOut(tween(70)) + fadeOut(tween(70)))
                                    } else {
                                        (fadeIn(tween(100)) + scaleIn(initialScale = 0.85f, animationSpec = tween(100)))
                                            .togetherWith(fadeOut(tween(70)) + scaleOut(targetScale = 0.85f, animationSpec = tween(70)))
                                    }
                                },
                                label = "mini_like_icon_morph"
                            ) { liked ->
                                Icon(
                                    imageVector = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = if (liked) "Unlike" else " Like",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Play/Pause Button — AnimatedContent crossfade
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
                                    AnimatedContent(
                                        targetState = isPlaying,
                                        transitionSpec = {
                                            (fadeIn(animationSpec = tween(180, delayMillis = 50)) +
                                                    scaleIn(initialScale = 0.85f, animationSpec = tween(180, delayMillis = 50)))
                                                .togetherWith(
                                                    fadeOut(animationSpec = tween(70)) +
                                                            scaleOut(targetScale = 0.85f, animationSpec = tween(70))
                                                )
                                        },
                                        label = "mini_play_pause_morph"
                                    ) { playing ->
                                        Icon(
                                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                            contentDescription = if (playing) "Pause" else "Play",
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
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
        } // Surface
    } // Box (centering container)
    } // AnimatedVisibility
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
