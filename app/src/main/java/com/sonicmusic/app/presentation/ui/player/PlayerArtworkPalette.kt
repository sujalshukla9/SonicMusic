package com.sonicmusic.app.presentation.ui.player

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.sonicmusic.app.domain.model.PaletteStyle
import com.sonicmusic.app.presentation.ui.theme.DynamicColorSchemeFactory
import com.sonicmusic.app.presentation.ui.theme.LocalDynamicThemeState
import com.sonicmusic.app.presentation.ui.theme.blendColorScheme

data class PlayerArtworkPalette(
    val colorScheme: ColorScheme,
    val seedColor: Color,
    val container: Color,
    val containerSoft: Color,
    val accent: Color,
    val onAccent: Color,
    val dominantColors: List<Color> = emptyList()
)

private const val MIN_TEXT_CONTRAST = 4.5

@Composable
fun rememberPlayerArtworkPalette(
    artworkUrl: String?,
    fallbackColorScheme: ColorScheme = MaterialTheme.colorScheme,
    enabled: Boolean = true,
    intensity: Float = 1f
): PlayerArtworkPalette {
    val themeState = LocalDynamicThemeState.current
    val isDarkTheme = themeState.isDark
    val normalizedIntensity = intensity.coerceIn(0f, 1f)

    val fallbackPalette = remember(fallbackColorScheme) {
        fallbackPalette(fallbackColorScheme)
    }

    if (!enabled || normalizedIntensity <= 0.001f || artworkUrl.isNullOrBlank()) {
        return fallbackPalette
    }

    val seedColor = themeState.seedColor

    val targetPalette = remember(seedColor, isDarkTheme) {
        val generatedScheme = DynamicColorSchemeFactory.generateColorScheme(
            seedColor = seedColor.toArgb(),
            isDark = isDarkTheme,
            style = PaletteStyle.CONTENT
        )

        val accent = generatedScheme.primary
        val onAccent = readableContentColor(
            background = accent,
            preferred = generatedScheme.onPrimary
        )

        val container = lerp(
            start = generatedScheme.surfaceContainerLow,
            stop = generatedScheme.primaryContainer,
            fraction = if (isDarkTheme) 0.30f else 0.22f
        )

        val containerSoft = lerp(
            start = generatedScheme.surfaceContainerLowest,
            stop = generatedScheme.surfaceContainerHigh,
            fraction = if (isDarkTheme) 0.44f else 0.30f
        )

        PlayerArtworkPalette(
            colorScheme = generatedScheme,
            seedColor = seedColor,
            container = container,
            containerSoft = containerSoft,
            accent = accent,
            onAccent = onAccent,
            dominantColors = listOf(seedColor, generatedScheme.secondary, generatedScheme.tertiary, generatedScheme.surfaceVariant, generatedScheme.surface)
        )
    }

    // Quantize to 5% steps to avoid thrashing blendColorScheme (35 lerps) on tiny slider jitters
    val quantizedIntensity = (normalizedIntensity * 20).toInt() / 20f
    return remember(targetPalette, fallbackPalette, quantizedIntensity) {
        targetPalette.withIntensity(
            fallback = fallbackPalette,
            intensity = quantizedIntensity
        )
    }
}




private fun fallbackPalette(colorScheme: ColorScheme): PlayerArtworkPalette {
    return PlayerArtworkPalette(
        colorScheme = colorScheme,
        seedColor = colorScheme.primary,
        container = colorScheme.surfaceContainerHigh,
        containerSoft = colorScheme.surfaceContainerLow,
        accent = colorScheme.primary,
        onAccent = colorScheme.onPrimary,
        dominantColors = listOf(
            colorScheme.primary,
            colorScheme.secondary,
            colorScheme.tertiary,
            colorScheme.surfaceVariant,
            colorScheme.surface
        )
    )
}

private fun PlayerArtworkPalette.withIntensity(
    fallback: PlayerArtworkPalette,
    intensity: Float
): PlayerArtworkPalette {
    val t = intensity.coerceIn(0f, 1f)
    if (t <= 0.001f) return fallback
    if (t >= 0.999f) return this

    val blendedScheme = blendColorScheme(
        from = fallback.colorScheme,
        to = colorScheme,
        fraction = t
    )
    val blendedAccent = lerp(fallback.accent, accent, t)

    return PlayerArtworkPalette(
        colorScheme = blendedScheme,
        seedColor = lerp(fallback.seedColor, seedColor, t),
        container = lerp(fallback.container, container, t),
        containerSoft = lerp(fallback.containerSoft, containerSoft, t),
        accent = blendedAccent,
        onAccent = readableContentColor(
            background = blendedAccent,
            preferred = blendedScheme.onPrimary
        ),
        dominantColors = if (t >= 0.5f) dominantColors else fallback.dominantColors
    )
}

// blendColorScheme is imported from com.sonicmusic.app.presentation.ui.theme.blendColorScheme

private fun readableContentColor(background: Color, preferred: Color): Color {
    val preferredContrast = ColorUtils.calculateContrast(
        preferred.toArgb(),
        background.toArgb()
    )
    if (preferredContrast >= MIN_TEXT_CONTRAST) return preferred

    val whiteContrast = ColorUtils.calculateContrast(
        Color.White.toArgb(),
        background.toArgb()
    )
    val blackContrast = ColorUtils.calculateContrast(
        Color.Black.toArgb(),
        background.toArgb()
    )

    return if (whiteContrast >= blackContrast) Color.White else Color.Black
}
