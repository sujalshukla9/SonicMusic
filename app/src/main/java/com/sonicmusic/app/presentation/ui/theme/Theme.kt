package com.sonicmusic.app.presentation.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.domain.model.ThemeMode
import com.sonicmusic.app.presentation.viewmodel.ThemeViewModel

private val DarkColorScheme = darkColorScheme(
    primary = SonicDarkPrimary,
    onPrimary = SonicDarkOnPrimary,
    primaryContainer = SonicDarkPrimaryContainer,
    onPrimaryContainer = SonicDarkOnPrimaryContainer,
    secondary = SonicDarkSecondary,
    onSecondary = SonicDarkOnSecondary,
    secondaryContainer = SonicDarkSecondaryContainer,
    onSecondaryContainer = SonicDarkOnSecondaryContainer,
    tertiary = SonicDarkTertiary,
    onTertiary = SonicDarkOnTertiary,
    tertiaryContainer = SonicDarkTertiaryContainer,
    onTertiaryContainer = SonicDarkOnTertiaryContainer,
    error = SonicDarkError,
    onError = SonicDarkOnError,
    errorContainer = SonicDarkErrorContainer,
    onErrorContainer = SonicDarkOnErrorContainer,
    background = SonicDarkBackground,
    onBackground = SonicDarkOnBackground,
    surface = SonicDarkSurface,
    onSurface = SonicDarkOnSurface,
    surfaceVariant = SonicDarkSurfaceVariant,
    onSurfaceVariant = SonicDarkOnSurfaceVariant,
    outline = SonicDarkOutline,
    outlineVariant = SonicDarkOutlineVariant,
    scrim = SonicDarkScrim,
    inverseSurface = SonicDarkInverseSurface,
    inverseOnSurface = SonicDarkInverseOnSurface,
    inversePrimary = SonicDarkInversePrimary,
    surfaceContainerLowest = SonicDarkSurfaceContainerLowest,
    surfaceContainerLow = SonicDarkSurfaceContainerLow,
    surfaceContainer = SonicDarkSurfaceContainer,
    surfaceContainerHigh = SonicDarkSurfaceContainerHigh,
    surfaceContainerHighest = SonicDarkSurfaceContainerHighest
)

private val LightColorScheme = lightColorScheme(
    primary = SonicLightPrimary,
    onPrimary = SonicLightOnPrimary,
    primaryContainer = SonicLightPrimaryContainer,
    onPrimaryContainer = SonicLightOnPrimaryContainer,
    secondary = SonicLightSecondary,
    onSecondary = SonicLightOnSecondary,
    secondaryContainer = SonicLightSecondaryContainer,
    onSecondaryContainer = SonicLightOnSecondaryContainer,
    tertiary = SonicLightTertiary,
    onTertiary = SonicLightOnTertiary,
    tertiaryContainer = SonicLightTertiaryContainer,
    onTertiaryContainer = SonicLightOnTertiaryContainer,
    error = SonicLightError,
    onError = SonicLightOnError,
    errorContainer = SonicLightErrorContainer,
    onErrorContainer = SonicLightOnErrorContainer,
    background = SonicLightBackground,
    onBackground = SonicLightOnBackground,
    surface = SonicLightSurface,
    onSurface = SonicLightOnSurface,
    surfaceVariant = SonicLightSurfaceVariant,
    onSurfaceVariant = SonicLightOnSurfaceVariant,
    outline = SonicLightOutline,
    outlineVariant = SonicLightOutlineVariant,
    scrim = SonicLightScrim,
    inverseSurface = SonicLightInverseSurface,
    inverseOnSurface = SonicLightInverseOnSurface,
    inversePrimary = SonicLightInversePrimary,
    surfaceContainerLowest = SonicLightSurfaceContainerLowest,
    surfaceContainerLow = SonicLightSurfaceContainerLow,
    surfaceContainer = SonicLightSurfaceContainer,
    surfaceContainerHigh = SonicLightSurfaceContainerHigh,
    surfaceContainerHighest = SonicLightSurfaceContainerHighest
)

private val SonicShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private const val DEFAULT_DYNAMIC_SEED = 0xFF4A7DFF.toInt()

@Composable
fun SonicMusicTheme(
    themeViewModel: ThemeViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    artworkUrl: String? = null,
    content: @Composable () -> Unit
) {
    val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
    val darkMode by themeViewModel.darkMode.collectAsStateWithLifecycle()
    val seedColor by themeViewModel.seedColor.collectAsStateWithLifecycle()
    val paletteStyle by themeViewModel.paletteStyle.collectAsStateWithLifecycle()
    val isPureBlack by themeViewModel.pureBlack.collectAsStateWithLifecycle()
    val dynamicColorsEnabled by themeViewModel.dynamicColorsEnabled.collectAsStateWithLifecycle()
    val dynamicColorIntensity by themeViewModel.dynamicColorIntensity.collectAsStateWithLifecycle()
    
    val context = LocalContext.current

    androidx.compose.runtime.LaunchedEffect(artworkUrl) {
        themeViewModel.onAlbumArtChanged(artworkUrl)
    }

    val isDark = when (darkMode) {
        com.sonicmusic.app.domain.model.DarkMode.LIGHT -> false
        com.sonicmusic.app.domain.model.DarkMode.DARK -> true
        com.sonicmusic.app.domain.model.DarkMode.SYSTEM -> isSystemInDarkTheme()
    }

    val dynamicStrength = (dynamicColorIntensity.coerceIn(0, 100) / 100f)

    val targetColorScheme = androidx.compose.runtime.remember(
        themeMode,
        seedColor,
        isDark,
        paletteStyle,
        isPureBlack,
        dynamicColorsEnabled,
        dynamicStrength
    ) {
        val baseScheme = if (isDark) DarkColorScheme else LightColorScheme
        fun applyDynamicStrength(dynamicScheme: ColorScheme): ColorScheme {
            if (!dynamicColorsEnabled) return baseScheme
            if (dynamicStrength <= 0.001f) return baseScheme
            if (dynamicStrength >= 0.999f) return dynamicScheme
            return blendColorScheme(baseScheme, dynamicScheme, dynamicStrength)
        }

        when (themeMode) {
            ThemeMode.DEFAULT -> {
                baseScheme
            }
            ThemeMode.DYNAMIC -> {
                val seed = if (dynamicColorsEnabled) {
                    seedColor ?: DEFAULT_DYNAMIC_SEED
                } else {
                    DEFAULT_DYNAMIC_SEED
                }

                if (isPureBlack) {
                    DynamicColorSchemeFactory.generatePureBlackScheme(seed)
                } else {
                    val dynamicScheme = DynamicColorSchemeFactory.generateColorScheme(seed, isDark, paletteStyle)
                    applyDynamicStrength(dynamicScheme)
                }
            }
            ThemeMode.MATERIAL_YOU -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val systemScheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                    if (isPureBlack) systemScheme.copy(background = androidx.compose.ui.graphics.Color.Black, surface = androidx.compose.ui.graphics.Color.Black) else systemScheme
                } else {
                    baseScheme
                }
            }
            ThemeMode.PURE_BLACK -> {
                val seed = if (dynamicColorsEnabled) {
                    seedColor ?: DEFAULT_DYNAMIC_SEED
                } else {
                    DEFAULT_DYNAMIC_SEED
                }
                DynamicColorSchemeFactory.generatePureBlackScheme(seed)
            }
        }
    }

    val animatedColorScheme = targetColorScheme.animated()
    val useLightSystemBars = animatedColorScheme.surface.luminance() > 0.5f

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = useLightSystemBars
            insetsController.isAppearanceLightNavigationBars = useLightSystemBars
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalDynamicThemeState provides DynamicThemeState(
            seedColor = seedColor?.let { androidx.compose.ui.graphics.Color(it) } ?: androidx.compose.ui.graphics.Color(0xFF4A7DFF),
            isDark = isDark,
            themeMode = themeMode
        )
    ) {
        MaterialTheme(
            colorScheme = animatedColorScheme,
            typography = Typography,
            shapes = SonicShapes,
            content = content
        )
    }
}

private fun blendColorScheme(
    from: ColorScheme,
    to: ColorScheme,
    fraction: Float
): ColorScheme {
    val t = fraction.coerceIn(0f, 1f)
    fun c(start: androidx.compose.ui.graphics.Color, end: androidx.compose.ui.graphics.Color) =
        lerp(start, end, t)

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
