package com.sonicmusic.app.presentation.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun ColorScheme.animated(
    durationMillis: Int = 1000,
    easing: Easing = FastOutSlowInEasing
): ColorScheme {
    val spec = tween<Color>(durationMillis = durationMillis, easing = easing)
    
    @Composable
    fun Color.a(): Color = animateColorAsState(this, spec, label = "color_anim").value
    
    return this.copy(
        primary = primary.a(),
        onPrimary = onPrimary.a(),
        primaryContainer = primaryContainer.a(),
        onPrimaryContainer = onPrimaryContainer.a(),
        inversePrimary = inversePrimary.a(),
        secondary = secondary.a(),
        onSecondary = onSecondary.a(),
        secondaryContainer = secondaryContainer.a(),
        onSecondaryContainer = onSecondaryContainer.a(),
        tertiary = tertiary.a(),
        onTertiary = onTertiary.a(),
        tertiaryContainer = tertiaryContainer.a(),
        onTertiaryContainer = onTertiaryContainer.a(),
        background = background.a(),
        onBackground = onBackground.a(),
        surface = surface.a(),
        onSurface = onSurface.a(),
        surfaceVariant = surfaceVariant.a(),
        onSurfaceVariant = onSurfaceVariant.a(),
        surfaceTint = surfaceTint.a(),
        inverseSurface = inverseSurface.a(),
        inverseOnSurface = inverseOnSurface.a(),
        error = error.a(),
        onError = onError.a(),
        errorContainer = errorContainer.a(),
        onErrorContainer = onErrorContainer.a(),
        outline = outline.a(),
        outlineVariant = outlineVariant.a(),
        scrim = scrim.a(),
        surfaceBright = surfaceBright.a(),
        surfaceDim = surfaceDim.a(),
        surfaceContainer = surfaceContainer.a(),
        surfaceContainerHigh = surfaceContainerHigh.a(),
        surfaceContainerHighest = surfaceContainerHighest.a(),
        surfaceContainerLow = surfaceContainerLow.a(),
        surfaceContainerLowest = surfaceContainerLowest.a(),
    )
}
