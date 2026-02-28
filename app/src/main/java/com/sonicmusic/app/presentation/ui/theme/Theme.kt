package com.sonicmusic.app.presentation.ui.theme
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
                    if (isPureBlack) {
                        systemScheme.copy(
                            background = androidx.compose.ui.graphics.Color.Black,
                            surface = androidx.compose.ui.graphics.Color.Black,
                            surfaceDim = androidx.compose.ui.graphics.Color.Black,
                            surfaceContainerLowest = androidx.compose.ui.graphics.Color.Black,
                            surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF0A0A0A),
                            surfaceContainer = androidx.compose.ui.graphics.Color(0xFF111111),
                            surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF1A1A1A),
                            surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF222222),
                        )
                    } else {
                        systemScheme
                    }
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
            val window = context.findActivity()?.window
            if (window != null) {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = useLightSystemBars
                insetsController.isAppearanceLightNavigationBars = useLightSystemBars
            }
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// blendColorScheme is now in ColorSchemeUtils.kt
