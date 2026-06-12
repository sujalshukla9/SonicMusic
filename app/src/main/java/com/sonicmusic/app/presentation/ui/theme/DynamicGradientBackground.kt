package com.sonicmusic.app.presentation.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Renders a vertically blended dynamic gradient background.
 * Animates transitions between color schemes using smooth crossfade animations.
 */
@Composable
fun DynamicGradientBackground(
    colorScheme: PlayerColorScheme,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {}
) {
    // Individually animate the color stops for a smooth crossfading effect
    val animatedStop1 by animateColorAsState(
        targetValue = colorScheme.backgroundStops.getOrElse(0) { Color.Transparent },
        animationSpec = tween(durationMillis = 1000),
        label = "gradient_stop_1"
    )
    val animatedStop2 by animateColorAsState(
        targetValue = colorScheme.backgroundStops.getOrElse(1) { Color.Transparent },
        animationSpec = tween(durationMillis = 1000),
        label = "gradient_stop_2"
    )
    val animatedStop3 by animateColorAsState(
        targetValue = colorScheme.backgroundStops.getOrElse(2) { Color.Transparent },
        animationSpec = tween(durationMillis = 1000),
        label = "gradient_stop_3"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(animatedStop1, animatedStop2, animatedStop3)
                )
            )
    ) {
        content()
    }
}
