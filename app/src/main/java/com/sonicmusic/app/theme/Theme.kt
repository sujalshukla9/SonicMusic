package com.sonicmusic.app.theme
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color

import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.domain.model.PaletteStyle
import com.sonicmusic.app.domain.model.ThemeMode
import com.sonicmusic.app.features.settings.presentation.ThemeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Material 3 Expressive shapes morphing
data class ExpressiveShapes(
    val buttonMorph: androidx.compose.ui.graphics.Shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 8.dp, bottomStart = 8.dp),
    val cardMorph: androidx.compose.ui.graphics.Shape = RoundedCornerShape(topStart = 8.dp, topEnd = 28.dp, bottomEnd = 8.dp, bottomStart = 28.dp),
    val chipMorph: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    val floatingActionButtonMorph: androidx.compose.ui.graphics.Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 8.dp, bottomEnd = 28.dp, bottomStart = 8.dp)
)

val LocalExpressiveShapes = staticCompositionLocalOf { ExpressiveShapes() }

// Expressive typography scale containing bold emphasized variants
data class ExpressiveTypography(
    val displayLargeEmphasized: TextStyle,
    val displayMediumEmphasized: TextStyle,
    val displaySmallEmphasized: TextStyle,
    val headlineLargeEmphasized: TextStyle,
    val headlineMediumEmphasized: TextStyle,
    val headlineSmallEmphasized: TextStyle,
    val titleLargeEmphasized: TextStyle,
    val titleMediumEmphasized: TextStyle,
    val titleSmallEmphasized: TextStyle,
    val bodyLargeEmphasized: TextStyle,
    val bodyMediumEmphasized: TextStyle,
    val bodySmallEmphasized: TextStyle,
    val labelLargeEmphasized: TextStyle,
    val labelMediumEmphasized: TextStyle,
    val labelSmallEmphasized: TextStyle
)

val LocalExpressiveTypography = staticCompositionLocalOf {
    val defaultFamily = SonicFontFamily
    ExpressiveTypography(
        displayLargeEmphasized = TextStyle(fontFamily = defaultFamily, fontWeight = FontWeight.ExtraBold, fontSize = 57.sp, lineHeight = 64.sp),
        displayMediumEmphasized = TextStyle(fontFamily = defaultFamily, fontWeight = FontWeight.ExtraBold, fontSize = 45.sp, lineHeight = 52.sp),
        displaySmallEmphasized = TextStyle(fontFamily = defaultFamily, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp, lineHeight = 44.sp),
        headlineLargeEmphasized = TextStyle(fontFamily = defaultFamily, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
        headlineMediumEmphasized = TextStyle(fontFamily = defaultFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
        headlineSmallEmphasized = TextStyle(fontFamily = defaultFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp),
        titleLargeEmphasized = TextStyle(fontFamily = defaultFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
        titleMediumEmphasized = TextStyle(fontFamily = defaultFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 24.sp),
        titleSmallEmphasized = TextStyle(fontFamily = defaultFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
        bodyLargeEmphasized = TextStyle(fontFamily = defaultFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMediumEmphasized = TextStyle(fontFamily = defaultFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
        bodySmallEmphasized = TextStyle(fontFamily = defaultFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
        labelLargeEmphasized = TextStyle(fontFamily = defaultFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
        labelMediumEmphasized = TextStyle(fontFamily = defaultFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp),
        labelSmallEmphasized = TextStyle(fontFamily = defaultFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 16.sp)
    )
}

val MaterialTheme.expressiveShapes: ExpressiveShapes
    @Composable
    @ReadOnlyComposable
    get() = LocalExpressiveShapes.current

val MaterialTheme.expressiveTypography: ExpressiveTypography
    @Composable
    @ReadOnlyComposable
    get() = LocalExpressiveTypography.current

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

// Allocated once at class-load time. SonicMusicTheme recomposes on every
// animated color frame during playback; re-creating these objects there
// caused needless allocations and CompositionLocal equality churn.
private val DefaultExpressiveShapes = ExpressiveShapes()
private val EmphasizedExpressiveTypography = ExpressiveTypography(
    displayLargeEmphasized = Typography.displayLarge.copy(fontWeight = FontWeight.ExtraBold),
    displayMediumEmphasized = Typography.displayMedium.copy(fontWeight = FontWeight.ExtraBold),
    displaySmallEmphasized = Typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
    headlineLargeEmphasized = Typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMediumEmphasized = Typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmallEmphasized = Typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLargeEmphasized = Typography.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMediumEmphasized = Typography.titleMedium.copy(fontWeight = FontWeight.Bold),
    titleSmallEmphasized = Typography.titleSmall.copy(fontWeight = FontWeight.Bold),
    bodyLargeEmphasized = Typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
    bodyMediumEmphasized = Typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
    bodySmallEmphasized = Typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
    labelLargeEmphasized = Typography.labelLarge.copy(fontWeight = FontWeight.Bold),
    labelMediumEmphasized = Typography.labelMedium.copy(fontWeight = FontWeight.Bold),
    labelSmallEmphasized = Typography.labelSmall.copy(fontWeight = FontWeight.Bold)
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SonicMusicTheme(
    themeViewModel: ThemeViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    content: @Composable () -> Unit
) {
    val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
    val darkMode by themeViewModel.darkMode.collectAsStateWithLifecycle()
    val artworkColors by themeViewModel.artworkColors.collectAsStateWithLifecycle()
    val seedColor = artworkColors?.primary
    val paletteStyle by themeViewModel.paletteStyle.collectAsStateWithLifecycle()
    val isPureBlack by themeViewModel.pureBlack.collectAsStateWithLifecycle()
    val dynamicColorsEnabled by themeViewModel.dynamicColorsEnabled.collectAsStateWithLifecycle()
    val dynamicColorIntensity by themeViewModel.dynamicColorIntensity.collectAsStateWithLifecycle()
    val thumbnailShape by themeViewModel.thumbnailShape.collectAsStateWithLifecycle()
    
    val context = LocalContext.current

    val isDark = when (darkMode) {
        com.sonicmusic.app.domain.model.DarkMode.LIGHT -> false
        com.sonicmusic.app.domain.model.DarkMode.DARK -> true
        com.sonicmusic.app.domain.model.DarkMode.SYSTEM -> isSystemInDarkTheme()
    }

    val dynamicStrength = (dynamicColorIntensity.coerceIn(0, 100) / 100f)

    val baseScheme = if (isDark) DarkColorScheme else LightColorScheme
    val initialScheme = if (isDark && (themeMode == ThemeMode.PURE_BLACK || isPureBlack)) {
        baseScheme.withPureBlackSurfaces()
    } else {
        baseScheme
    }
    // Seed-to-palette generation can do dozens of tone calculations on a cold cache.
    val calculatedScheme by androidx.compose.runtime.key(isDark, themeMode, isPureBlack) {
        produceState(
            initialValue = initialScheme,
            themeMode, artworkColors, isDark, paletteStyle, isPureBlack, dynamicColorsEnabled, dynamicStrength, context
        ) {
            value = withContext(Dispatchers.Default) {
                resolveAppColorScheme(
                    context, themeMode, artworkColors, isDark, paletteStyle,
                    isPureBlack, dynamicColorsEnabled, dynamicStrength
                )
            }
        }
    }

    val targetColorScheme = calculatedScheme
    val useLightSystemBars = targetColorScheme.surface.luminance() > 0.5f

    val view = LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.LaunchedEffect(useLightSystemBars) {
            val window = context.findActivity()?.window
            if (window != null) {
                // statusBarColor/navigationBarColor are deprecated on API 35+,
                // but still needed for API 26-34 backward compatibility.
                @Suppress("DEPRECATION")
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                @Suppress("DEPRECATION")
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = useLightSystemBars
                insetsController.isAppearanceLightNavigationBars = useLightSystemBars
            }
        }
    }

    val dynamicThemeState = androidx.compose.runtime.remember(seedColor, useLightSystemBars, themeMode, paletteStyle) {
        DynamicThemeState(
            seedColor = seedColor?.let { androidx.compose.ui.graphics.Color(it) } ?: androidx.compose.ui.graphics.Color(0xFF4A7DFF),
            isDark = !useLightSystemBars,
            themeMode = themeMode,
            paletteStyle = paletteStyle
        )
    }

    val reduceMotion = shouldReduceMotion()
    // Commit light/dark changes together so foreground and surface roles stay readable.
    val displayedColorScheme = androidx.compose.runtime.key(useLightSystemBars) {
        targetColorScheme.animated(durationMillis = if (reduceMotion) 0 else MotionTokens.DurationStandard)
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalDynamicThemeState provides dynamicThemeState,
        LocalReducedMotion provides reduceMotion,
        LocalExpressiveShapes provides DefaultExpressiveShapes,
        LocalExpressiveTypography provides EmphasizedExpressiveTypography,
        LocalThumbnailShape provides thumbnailShape
    ) {
        @OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
        MaterialTheme(
            colorScheme = displayedColorScheme,
            typography = Typography,
            shapes = SonicShapes,
            motionScheme = if (reduceMotion) MotionTokens.reducedMotionScheme else MotionTokens.expressiveMotionScheme,
            content = content
        )
    }
}

private fun resolveAppColorScheme(
    context: Context,
    themeMode: ThemeMode,
    artworkColors: ArtworkColors?,
    isDark: Boolean,
    paletteStyle: PaletteStyle,
    isPureBlack: Boolean,
    dynamicColorsEnabled: Boolean,
    dynamicStrength: Float
): ColorScheme {
    val baseScheme = if (isDark) DarkColorScheme else LightColorScheme

    fun applyDynamicStrength(dynamicScheme: ColorScheme): ColorScheme {
        if (!dynamicColorsEnabled) return baseScheme
        if (dynamicStrength <= 0.001f) return baseScheme
        if (dynamicStrength >= 0.999f) return dynamicScheme
        return blendColorScheme(baseScheme, dynamicScheme, dynamicStrength)
    }

    // Fast in-memory M3 Content Dynamic Scheme (using SchemeContent with chroma fidelity and contrast control)
    fun resolveContentDynamicScheme(seed: Int, dark: Boolean = isDark): ColorScheme {
        val contrastLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
                uiModeManager?.contrast?.toDouble() ?: 0.0
            } catch (e: Throwable) {
                0.0
            }
        } else {
            0.0
        }

        val scheme = DynamicColorSchemeFactory.generateColorScheme(seed, dark, paletteStyle, contrastLevel)
        val companion = artworkColors?.complementary ?: return scheme
        // Keep each foreground/container pair from a single tonal palette.
        val accent = DynamicColorSchemeFactory.generateColorScheme(companion, dark, PaletteStyle.TONAL_SPOT, contrastLevel)
        return scheme.copy(
            tertiary = accent.primary,
            onTertiary = accent.onPrimary,
            tertiaryContainer = accent.primaryContainer,
            onTertiaryContainer = accent.onPrimaryContainer
        )
    }

    return when (themeMode) {
        ThemeMode.DEFAULT -> {
            baseScheme
        }
        ThemeMode.DYNAMIC -> {
            val seed = if (dynamicColorsEnabled) {
                artworkColors?.primary ?: DEFAULT_DYNAMIC_SEED
            } else {
                DEFAULT_DYNAMIC_SEED
            }

            val scheme = if (dynamicColorsEnabled && dynamicStrength > 0f) {
                applyDynamicStrength(resolveContentDynamicScheme(seed))
            } else {
                baseScheme
            }
            if (isPureBlack && isDark) scheme.withPureBlackSurfaces() else scheme
        }
        ThemeMode.MATERIAL_YOU -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val systemScheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                val harmonizedSystem = systemScheme.harmonized()
                if (isPureBlack && isDark) {
                    harmonizedSystem.copy(
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
                    harmonizedSystem
                }
            } else {
                baseScheme
            }
        }
        ThemeMode.PURE_BLACK -> {
            val seed = if (dynamicColorsEnabled) {
                artworkColors?.primary ?: DEFAULT_DYNAMIC_SEED
            } else {
                DEFAULT_DYNAMIC_SEED
            }
            val artworkScheme = if (dynamicColorsEnabled && dynamicStrength > 0f) {
                resolveContentDynamicScheme(seed, dark = true)
            } else {
                DarkColorScheme
            }
            val scheme = if (dynamicColorsEnabled && dynamicStrength < 1f) {
                blendColorScheme(DarkColorScheme, artworkScheme, dynamicStrength)
            } else {
                artworkScheme
            }
            scheme.withPureBlackSurfaces()
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun ColorScheme.withPureBlackSurfaces(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black
)
