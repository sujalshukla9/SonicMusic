package com.sonicmusic.app.features.player.presentation

import androidx.compose.runtime.getValue
import com.sonicmusic.app.theme.MotionTokens
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.domain.model.FullPlayerStyle
import com.sonicmusic.app.theme.shouldReduceMotion
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun PlayerSeekTrack(
    progress: Float,
    style: FullPlayerStyle,
    isPlaying: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 24.dp
) {
    val isWavyStyle = style == FullPlayerStyle.WAVY
    val reduceMotion = shouldReduceMotion()
    val wavePhaseState = if (isWavyStyle && isPlaying && !reduceMotion) {
        rememberInfiniteTransition(label = "seek_wave_motion_transition").animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI.toFloat()),
            animationSpec = infiniteRepeatable(
                animation = MotionTokens.linearTween(MotionTokens.SeekWavePeriod),
                repeatMode = RepeatMode.Restart
            ),
            label = "seek_wave_phase"
        )
    } else {
        null
    }
    val waveAmplitudeScale by animateFloatAsState(
        targetValue = when {
            isWavyStyle && isPlaying -> 1f
            isWavyStyle -> 0.72f
            else -> 0f
        },
        animationSpec = if (reduceMotion) snap() else MotionTokens.effects(),
        label = "seek_wave_amplitude"
    )

    Canvas(
        modifier = modifier.height(trackHeight)
    ) {
        val wavePhase = wavePhaseState?.value ?: 0f
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
