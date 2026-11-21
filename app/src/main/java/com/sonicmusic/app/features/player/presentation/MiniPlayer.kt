package com.sonicmusic.app.features.player.presentation

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.sonicmusic.app.theme.contentMotion
import com.sonicmusic.app.theme.iconMotion
import com.sonicmusic.app.theme.pressable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import com.sonicmusic.app.theme.MotionTokens
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import com.sonicmusic.app.theme.Dimens
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.shared.components.SongThumbnail
import com.sonicmusic.app.theme.sharedPlayerArtwork
import com.sonicmusic.app.features.player.presentation.PlayerViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    floating: Boolean = false,
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
        enter = if (reduceMotion) EnterTransition.None else
            slideInVertically(MotionTokens.spatial()) { it / 2 } + fadeIn(MotionTokens.effects()),
        exit = if (reduceMotion) ExitTransition.None else
            slideOutVertically(MotionTokens.quickSpatial()) { it / 2 } + fadeOut(MotionTokens.quickEffects()),
        modifier = modifier
    ) {
        val miniHeight = if (floating) 64.dp else Dimens.miniPlayerHeight
        val artSize = Dimens.miniPlayerArtSize

        var dragOffset by remember { mutableFloatStateOf(0f) }
        val settleOffset = remember { Animatable(0f) }
        var dragging by remember { mutableStateOf(false) }
        val swipeScope = rememberCoroutineScope()
        var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        fun settleSwipe() {
            val offset = dragOffset
            settleJob?.cancel()
            settleJob = swipeScope.launch {
                settleOffset.snapTo(offset)
                dragging = false
                if (reduceMotion) settleOffset.snapTo(0f)
                else settleOffset.animateTo(0f, MotionTokens.PanelSpring)
            }
        }

        val colorScheme = MaterialTheme.colorScheme
        val backgroundColor = backgroundColorOverride ?: colorScheme.secondaryContainer
        val foregroundColor = if (backgroundColorOverride == null) {
            colorScheme.onSecondaryContainer
        } else {
            colorScheme.onSurface
        }
        val accentColor = colorScheme.primary
        val onAccentColor = colorScheme.onPrimary

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
        Surface(
            modifier = Modifier
                .widthIn(max = Dimens.maxContentWidth)
                .fillMaxWidth()
                .height(miniHeight)
                .pointerInput(reduceMotion) {
                    val swipeThreshold = 64.dp.toPx()
                    val maxOffset = 116.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            settleJob?.cancel()
                            dragOffset = settleOffset.value
                            dragging = true
                        },
                        onDragEnd = {
                            when {
                                dragOffset < -swipeThreshold && viewModel.playerUiState.value.canSkipNext -> viewModel.skipToNext()
                                dragOffset > swipeThreshold && viewModel.playerUiState.value.canSkipPrevious -> viewModel.forceSkipToPrevious()
                            }
                            settleSwipe()
                        },
                        onDragCancel = { settleSwipe() },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset = (dragOffset + dragAmount).coerceIn(-maxOffset, maxOffset)
                        }
                    )
                }
                .pressable(role = Role.Button, onClickLabel = "Open Now Playing", onClick = onExpand),
            shape = if (floating) {
                RoundedCornerShape(24.dp)
            } else {
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            },
            color = backgroundColor,
            tonalElevation = if (floating) 0.dp else 6.dp,
            shadowElevation = if (floating) 0.dp else 2.dp
        ) {
            Box {
                // Swipe indicators (background)
                // Main content
                Column(
                    modifier = Modifier.graphicsLayer {
                        translationX = if (dragging) dragOffset else settleOffset.value
                    }
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
                            shadowElevation = if (floating) 0.dp else 8.dp,
                            modifier = Modifier.size(artSize).sharedPlayerArtwork(songToDisplay?.id)
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        // Song Info
                        AnimatedContent(
                            targetState = songToDisplay,
                            contentKey = { it?.id },
                            transitionSpec = { contentMotion(reduceMotion) },
                            label = "mini_player_metadata",
                            modifier = Modifier.weight(1f)
                        ) { song ->
                            Column {
                                Text(
                                    text = song?.title.orEmpty(),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = foregroundColor
                                )
                                Text(
                                    text = song?.artist.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (floating) foregroundColor else colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        if (!floating) {
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
                                    transitionSpec = { iconMotion(reduceMotion) },
                                    label = "mini_like_icon_morph"
                                ) { liked ->
                                    Icon(
                                        imageVector = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                        contentDescription = if (liked) "Unlike" else "Like",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        // Playback and buffering share the same footprint.
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
                                    PlaybackGlyph(playing = isPlaying, modifier = Modifier.size(28.dp))
                                }
                            }
                        }

                        if (floating) {
                            val canSkipNext by remember(viewModel) {
                                viewModel.playerUiState
                                    .map { it.canSkipNext }
                                    .distinctUntilChanged()
                            }.collectAsStateWithLifecycle(
                                initialValue = viewModel.playerUiState.value.canSkipNext
                            )
                            IconButton(
                                onClick = { viewModel.skipToNext() },
                                enabled = canSkipNext
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SkipNext,
                                    contentDescription = "Next track",
                                    tint = foregroundColor.copy(alpha = if (canSkipNext) 1f else 0.38f)
                                )
                            }
                        }
                    }

                    // Progress Bar - Isolated to prevent full recomposition
                    MiniPlayerProgress(
                        viewModel = viewModel,
                        progressColor = accentColor,
                        modifier = if (floating) Modifier.padding(horizontal = 24.dp) else Modifier
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
    progressColor: Color,
    modifier: Modifier = Modifier
) {
    val progress = viewModel.progress.collectAsStateWithLifecycle()
    
    LinearProgressIndicator(
        progress = { progress.value },
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp),
        color = progressColor,
        trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        strokeCap = StrokeCap.Round
    )
}
