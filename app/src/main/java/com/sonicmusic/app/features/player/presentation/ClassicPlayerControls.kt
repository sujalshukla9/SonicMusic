package com.sonicmusic.app.features.player.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.sonicmusic.app.domain.model.FullPlayerStyle
import com.sonicmusic.app.features.player.presentation.PlayerUiState
import com.sonicmusic.app.features.player.presentation.PlayerActions
import com.sonicmusic.app.theme.Dimens
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassicPlayerControls(
    state: PlayerUiState,
    actions: PlayerActions,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    repeatMode: Int,
    onToggleRepeat: () -> Unit,
    accentColor: Color,
    onAccentColor: Color,
    fullPlayerStyle: FullPlayerStyle,
    modifier: Modifier = Modifier,
    isCompactHeight: Boolean = false,
    isCompactWidth: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme

    // Track dragging state locally
    var isUserDragging by remember(state.title + state.artist) { mutableStateOf(false) }
    var dragValue by remember(state.title + state.artist) { mutableFloatStateOf(0f) }
    var lastSeekTime by remember(state.title + state.artist) { mutableStateOf(0L) }

    val progress = if (state.durationMs > 0) {
        state.currentPositionMs.toFloat() / state.durationMs
    } else {
        0f
    }
    val isRecentSeek = lastSeekTime > 0L && (System.currentTimeMillis() - lastSeekTime < 800L)
    
    // Animate the progress smoothly when not dragging or recently seeking
    val animatedProgress by androidx.compose.runtime.key(state.title, state.artist) {
        animateFloatAsState(
            targetValue = progress,
            animationSpec = if (isUserDragging || isRecentSeek) {
                snap()
            } else {
                tween(durationMillis = 250, easing = LinearEasing)
            },
            label = "smooth_progress"
        )
    }

    val sliderPosition = if (isUserDragging) {
        dragValue
    } else if (isRecentSeek) {
        dragValue
    } else {
        animatedProgress
    }

    val isCompact = isCompactHeight || isCompactWidth
    val spacingHeight = if (isCompact) 4.dp else 8.dp
    val trackHeight = if (isCompact) 16.dp else 24.dp
    val secondaryBtnSize = if (isCompact) 40.dp else 48.dp
    val secondaryIconSize = if (isCompact) 20.dp else 24.dp
    val skipBtnSize = if (isCompact) 40.dp else Dimens.skipButtonSize
    val skipIcnSize = if (isCompact) 20.dp else Dimens.skipIconSize
    val playBtnSize = if (isCompact) 56.dp else Dimens.playButtonSize
    val playIcnSize = if (isCompact) 28.dp else Dimens.playIconSize

    Column(modifier = modifier) {
        // Seeker Bar Row
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isCompactWidth) 12.dp else 20.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isCompactWidth) 4.dp else 10.dp, vertical = 2.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                PlayerSeekTrack(
                    progress = sliderPosition,
                    style = fullPlayerStyle,
                    isPlaying = state.isPlaying,
                    activeColor = colorScheme.onSurface,
                    inactiveColor = colorScheme.onSurface.copy(alpha = 0.32f),
                    modifier = Modifier.fillMaxWidth(),
                    trackHeight = trackHeight
                )

                Slider(
                    value = sliderPosition,
                    onValueChange = { newValue ->
                        isUserDragging = true
                        dragValue = newValue
                        actions.onSeekPreviewChange((newValue * state.durationMs).toLong())
                    },
                    onValueChangeFinished = {
                        actions.onSeekTo((dragValue * state.durationMs).toLong())
                        lastSeekTime = System.currentTimeMillis()
                        isUserDragging = false
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.Transparent,
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent
                    ),
                    thumb = { /* Custom Canvas thumb drawn instead */ }
                )
            }

            val displayTimeMs = if (isUserDragging) {
                (dragValue * state.durationMs).toLong()
            } else if (isRecentSeek) {
                (dragValue * state.durationMs).toLong()
            } else {
                state.currentPositionMs
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration((displayTimeMs / 1000).toInt()),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = colorScheme.onSurface.copy(alpha = 0.70f)
                )
                Text(
                    text = formatDuration((state.durationMs / 1000).toInt()),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = colorScheme.onSurface.copy(alpha = 0.70f)
                )
            }
        }

        Spacer(modifier = Modifier.height(spacingHeight))

        // Control Buttons Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isCompactWidth) 12.dp else 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Like — AnimatedContent so the heart pops in with a spring scale
            IconButton(
                onClick = onToggleLike,
                modifier = Modifier.size(secondaryBtnSize)
            ) {
                AnimatedContent(
                    targetState = isLiked,
                    transitionSpec = {
                        if (targetState) {
                            // Filling the heart: spring pop-in
                            (scaleIn(
                                animationSpec = spring(dampingRatio = 0.55f, stiffness = 600f),
                                initialScale = 0.6f
                            ) + fadeIn(tween(120)))
                                .togetherWith(scaleOut(tween(80)) + fadeOut(tween(80)))
                        } else {
                            // Unfilling: simple crossfade
                            (fadeIn(tween(120)) + scaleIn(initialScale = 0.85f, animationSpec = tween(120)))
                                .togetherWith(fadeOut(tween(80)) + scaleOut(targetScale = 0.85f, animationSpec = tween(80)))
                        }
                    },
                    label = "like_icon_morph"
                ) { liked ->
                    Icon(
                        imageVector = if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (liked) "Unlike" else " Like",
                        modifier = Modifier.size(secondaryIconSize),
                        tint = if (liked) colorScheme.error else colorScheme.onSurfaceVariant
                    )
                }
            }

            // Previous
            FilledIconButton(
                onClick = { actions.onSkipPrevious() },
                enabled = state.canSkipPrevious,
                modifier = Modifier.size(skipBtnSize),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = accentColor.copy(alpha = 0.2f),
                    contentColor = accentColor,
                    disabledContainerColor = accentColor.copy(alpha = 0.08f),
                    disabledContentColor = accentColor.copy(alpha = 0.38f)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    modifier = Modifier.size(skipIcnSize)
                )
            }

            // Play/Pause — AnimatedContent morph between play and pause icons
            Box(modifier = Modifier.size(playBtnSize), contentAlignment = Alignment.Center) {
                FilledIconButton(
                    onClick = { actions.onPlayPause() },
                    modifier = Modifier.size(playBtnSize),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = accentColor,
                        contentColor = onAccentColor
                    )
                ) {
                    AnimatedContent(
                        targetState = state.isPlaying,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(200, delayMillis = 60)) +
                                    scaleIn(initialScale = 0.85f, animationSpec = tween(200, delayMillis = 60)))
                                .togetherWith(
                                    fadeOut(animationSpec = tween(80)) +
                                            scaleOut(targetScale = 0.85f, animationSpec = tween(80))
                                )
                        },
                        label = "classic_play_pause_morph"
                    ) { isPlaying ->
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else " Play",
                            modifier = Modifier.size(playIcnSize)
                        )
                    }
                }

                if (state.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(playBtnSize * 0.9f),
                        color = onAccentColor.copy(alpha = 0.6f),
                        strokeWidth = 3.dp
                    )
                }
            }

            // Next
            FilledIconButton(
                onClick = { actions.onSkipNext() },
                enabled = state.canSkipNext,
                modifier = Modifier.size(skipBtnSize),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = accentColor.copy(alpha = 0.2f),
                    contentColor = accentColor,
                    disabledContainerColor = accentColor.copy(alpha = 0.08f),
                    disabledContentColor = accentColor.copy(alpha = 0.38f)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    modifier = Modifier.size(skipIcnSize)
                )
            }

            // Repeat — contentDescription describes current state for TalkBack
            IconButton(
                onClick = onToggleRepeat,
                modifier = Modifier.size(secondaryBtnSize)
            ) {
                Icon(
                    imageVector = when (repeatMode) {
                        Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne
                        Player.REPEAT_MODE_ALL -> Icons.Filled.Repeat
                        else -> Icons.Outlined.Repeat
                    },
                    contentDescription = when (repeatMode) {
                        Player.REPEAT_MODE_ONE -> "Repeat one — tap to turn off"
                        Player.REPEAT_MODE_ALL -> "Repeat all — tap to repeat one"
                        else -> "Repeat off — tap to repeat all"
                    },
                    modifier = Modifier.size(secondaryIconSize),
                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) {
                        accentColor
                    } else {
                        colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
fun PlayerSeekTrack(
    progress: Float,
    style: FullPlayerStyle,
    isPlaying: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
    trackHeight: androidx.compose.ui.unit.Dp = 24.dp
) {
    val isWavyStyle = style == FullPlayerStyle.WAVY
    val wavePhase = if (isWavyStyle && isPlaying) {
        rememberInfiniteTransition(label = "seek_wave_motion_transition").animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI.toFloat()),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "seek_wave_phase"
        ).value
    } else {
        0f
    }
    val waveAmplitudeScale by animateFloatAsState(
        targetValue = when {
            isWavyStyle && isPlaying -> 1f
            isWavyStyle -> 0.72f
            else -> 0f
        },
        animationSpec = tween(durationMillis = 240),
        label = "seek_wave_amplitude"
    )

    Canvas(
        modifier = modifier.height(trackHeight)
    ) {
        val centerY = size.height / 2f
        val isCompact = trackHeight < 24.dp
        val trackStroke = if (isCompact) 3.dp.toPx() else 5.dp.toPx()
        val thumbStroke = if (isCompact) 5.dp.toPx() else 7.dp.toPx()
        val thumbHeight = if (isCompact) 12.dp.toPx() else 18.dp.toPx()
        val clampedProgress = progress.coerceIn(0f, 1f)
        val activeEndX = size.width * clampedProgress
        val waveAmplitude = (if (isCompact) 1.8.dp.toPx() else 2.8.dp.toPx()) * waveAmplitudeScale
        val wavelength = 34.dp.toPx()
        val waveStep = 2.dp.toPx()

        var thumbCenterY = centerY

        if (activeEndX > 0f) {
            if (isWavyStyle) {
                val wavePath = Path().apply {
                    val startY = centerY + sin(wavePhase) * waveAmplitude
                    moveTo(0f, startY)
                    var x = waveStep
                    while (x <= activeEndX) {
                        val radians = ((x / wavelength) * (2f * PI.toFloat())) + wavePhase
                        lineTo(x, centerY + sin(radians) * waveAmplitude)
                        x += waveStep
                    }
                    if (x - waveStep < activeEndX) {
                        val radians = ((activeEndX / wavelength) * (2f * PI.toFloat())) + wavePhase
                        lineTo(activeEndX, centerY + sin(radians) * waveAmplitude)
                    }
                }

                drawPath(
                    path = wavePath,
                    color = activeColor,
                    style = Stroke(width = trackStroke, cap = StrokeCap.Round)
                )

                val thumbRadians = ((activeEndX / wavelength) * (2f * PI.toFloat())) + wavePhase
                thumbCenterY = centerY + sin(thumbRadians) * waveAmplitude
            } else {
                drawLine(
                    color = activeColor,
                    start = Offset(0f, centerY),
                    end = Offset(activeEndX, centerY),
                    strokeWidth = trackStroke,
                    cap = StrokeCap.Round
                )
            }
        }

        if (activeEndX < size.width) {
            drawLine(
                color = inactiveColor,
                start = Offset(activeEndX.coerceIn(0f, size.width), centerY),
                end = Offset(size.width, centerY),
                strokeWidth = trackStroke,
                cap = StrokeCap.Round
            )
        }

        drawLine(
            color = activeColor,
            start = Offset(
                x = activeEndX.coerceIn(0f, size.width),
                y = thumbCenterY - (thumbHeight / 2f)
            ),
            end = Offset(
                x = activeEndX.coerceIn(0f, size.width),
                y = thumbCenterY + (thumbHeight / 2f)
            ),
            strokeWidth = thumbStroke,
            cap = StrokeCap.Round
        )
    }
}

private fun formatDuration(seconds: Int): String {
    if (seconds < 0) return "0:00"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%d:%02d", minutes, secs)
    }
}
