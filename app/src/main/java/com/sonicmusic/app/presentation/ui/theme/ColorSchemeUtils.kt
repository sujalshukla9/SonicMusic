package com.sonicmusic.app.presentation.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb

/**
 * Shifts the hue of [colorToHarmonize] towards the hue of [primaryColor] by [fraction].
 * Uses HSL space interpolation to preserve original color characteristics while enhancing cohesion.
 */
fun harmonizeColor(
    colorToHarmonize: Color,
    primaryColor: Color,
    fraction: Float = 0.25f
): Color {
    val colorArgb = colorToHarmonize.toArgb()
    val primaryArgb = primaryColor.toArgb()

    val hsl = FloatArray(3)
    val primaryHsl = FloatArray(3)

    androidx.core.graphics.ColorUtils.colorToHSL(colorArgb, hsl)
    androidx.core.graphics.ColorUtils.colorToHSL(primaryArgb, primaryHsl)

    val hueDiff = primaryHsl[0] - hsl[0]
    val shiftedHue = if (Math.abs(hueDiff) > 180f) {
        if (primaryHsl[0] > hsl[0]) {
            (hsl[0] + (primaryHsl[0] - 360f - hsl[0]) * fraction + 360f) % 360f
        } else {
            (hsl[0] + (primaryHsl[0] + 360f - hsl[0]) * fraction) % 360f
        }
    } else {
        hsl[0] + hueDiff * fraction
    }

    hsl[0] = shiftedHue
    return Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
}

/**
 * Harmonizes the tertiary and error color roles of a ColorScheme with its primary color.
 */
fun ColorScheme.harmonized(): ColorScheme {
    val prim = this.primary
    return this.copy(
        tertiary = harmonizeColor(this.tertiary, prim),
        onTertiary = harmonizeColor(this.onTertiary, prim),
        tertiaryContainer = harmonizeColor(this.tertiaryContainer, prim),
        onTertiaryContainer = harmonizeColor(this.onTertiaryContainer, prim),
        error = harmonizeColor(this.error, prim),
        onError = harmonizeColor(this.onError, prim),
        errorContainer = harmonizeColor(this.errorContainer, prim),
        onErrorContainer = harmonizeColor(this.onErrorContainer, prim)
    )
}

/**
 * Blends two [ColorScheme] instances field-by-field.
 *
 * Used by both the global theme transition (Theme.kt) and the
 * player artwork palette (PlayerArtworkPalette.kt).
 *
 * @param from  base / fallback scheme
 * @param to    target scheme
 * @param fraction  0f = fully [from], 1f = fully [to]
 */
fun blendColorScheme(
    from: ColorScheme,
    to: ColorScheme,
    fraction: Float
): ColorScheme {
    val t = fraction.coerceIn(0f, 1f)
    fun c(start: Color, end: Color): Color = lerp(start, end, t)

    return from.copy(
        primary = c(from.primary, to.primary),
        onPrimary = c(from.onPrimary, to.onPrimary),
        primaryContainer = c(from.primaryContainer, to.primaryContainer),
        onPrimaryContainer = c(from.onPrimaryContainer, to.onPrimaryContainer),
        inversePrimary = c(from.inversePrimary, to.inversePrimary),
        secondary = c(from.secondary, to.secondary),
        onSecondary = c(from.onSecondary, to.onSecondary),
        secondaryContainer = c(from.secondaryContainer, to.secondaryContainer),
        onSecondaryContainer = c(from.onSecondaryContainer, to.onSecondaryContainer),
        tertiary = c(from.tertiary, to.tertiary),
        onTertiary = c(from.onTertiary, to.onTertiary),
        tertiaryContainer = c(from.tertiaryContainer, to.tertiaryContainer),
        onTertiaryContainer = c(from.onTertiaryContainer, to.onTertiaryContainer),
        background = c(from.background, to.background),
        onBackground = c(from.onBackground, to.onBackground),
        surface = c(from.surface, to.surface),
        onSurface = c(from.onSurface, to.onSurface),
        surfaceVariant = c(from.surfaceVariant, to.surfaceVariant),
        onSurfaceVariant = c(from.onSurfaceVariant, to.onSurfaceVariant),
        surfaceTint = c(from.surfaceTint, to.surfaceTint),
        inverseSurface = c(from.inverseSurface, to.inverseSurface),
        inverseOnSurface = c(from.inverseOnSurface, to.inverseOnSurface),
        error = c(from.error, to.error),
        onError = c(from.onError, to.onError),
        errorContainer = c(from.errorContainer, to.errorContainer),
        onErrorContainer = c(from.onErrorContainer, to.onErrorContainer),
        outline = c(from.outline, to.outline),
        outlineVariant = c(from.outlineVariant, to.outlineVariant),
        scrim = c(from.scrim, to.scrim),
        surfaceBright = c(from.surfaceBright, to.surfaceBright),
        surfaceDim = c(from.surfaceDim, to.surfaceDim),
        surfaceContainer = c(from.surfaceContainer, to.surfaceContainer),
        surfaceContainerHigh = c(from.surfaceContainerHigh, to.surfaceContainerHigh),
        surfaceContainerHighest = c(from.surfaceContainerHighest, to.surfaceContainerHighest),
        surfaceContainerLow = c(from.surfaceContainerLow, to.surfaceContainerLow),
        surfaceContainerLowest = c(from.surfaceContainerLowest, to.surfaceContainerLowest)
    )
}
