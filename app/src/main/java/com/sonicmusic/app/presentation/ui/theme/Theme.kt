package com.sonicmusic.app.presentation.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.sonicmusic.app.domain.model.ThemeMode
import com.sonicmusic.app.presentation.ui.player.rememberPlayerArtworkPalette

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
    inversePrimary = SonicDarkInversePrimary
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
    inversePrimary = SonicLightInversePrimary
)

private val SonicShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun SonicMusicTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    dynamicColorIntensity: Int = 85,
    artworkUrl: String? = null,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val baseColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val colorScheme = if (dynamicColor && !artworkUrl.isNullOrBlank()) {
        rememberPlayerArtworkPalette(
            artworkUrl = artworkUrl,
            fallbackColorScheme = baseColorScheme,
            enabled = true,
            intensity = dynamicColorIntensity / 100f
        ).colorScheme
    } else {
        baseColorScheme
    }
    val useLightSystemBars = colorScheme.surface.luminance() > 0.5f

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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = SonicShapes,
        content = content
    )
}
