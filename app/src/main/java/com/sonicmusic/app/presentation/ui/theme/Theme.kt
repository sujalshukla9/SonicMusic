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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.domain.model.ThemeMode
import com.sonicmusic.app.presentation.viewmodel.ThemeViewModel

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
    val defaultFamily = FontFamily.SansSerif
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

// Expressive motion springs
data class ExpressiveMotion(
    val playerSheetSpring: SpringSpec<Float> = spring(
        dampingRatio = 0.75f,
        stiffness = 350f
    ),
    val colorSpring: SpringSpec<Color> = spring(
        dampingRatio = 0.85f,
        stiffness = 300f
    ),
    val scaleSpring: SpringSpec<Float> = spring(
        dampingRatio = 0.70f,
        stiffness = 400f
    )
)

val LocalExpressiveMotion = staticCompositionLocalOf { ExpressiveMotion() }

// CompositionLocal for PlayerColorScheme
val LocalPlayerColorScheme = compositionLocalOf {
    ColorExtractor.fallbackColorScheme(isDarkTheme = true)
}

val MaterialTheme.expressiveShapes: ExpressiveShapes
    @Composable
    @ReadOnlyComposable
    get() = LocalExpressiveShapes.current

val MaterialTheme.expressiveTypography: ExpressiveTypography
    @Composable
    @ReadOnlyComposable
    get() = LocalExpressiveTypography.current

val MaterialTheme.expressiveMotion: ExpressiveMotion
    @Composable
    @ReadOnlyComposable
    get() = LocalExpressiveMotion.current

val MaterialTheme.playerColorScheme: PlayerColorScheme
    @Composable
    @ReadOnlyComposable
    get() = LocalPlayerColorScheme.current

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
    val playerColorScheme by themeViewModel.playerColorScheme.collectAsStateWithLifecycle()
    
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
                    DynamicColorSchemeFactory.generatePureBlackScheme(seed).harmonized()
                } else {
                    val dynamicScheme = DynamicColorSchemeFactory.generateColorScheme(seed, isDark, paletteStyle).harmonized()
                    applyDynamicStrength(dynamicScheme)
                }
            }
            ThemeMode.MATERIAL_YOU -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val systemScheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                    val harmonizedSystem = systemScheme.harmonized()
                    if (isPureBlack) {
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
                    seedColor ?: DEFAULT_DYNAMIC_SEED
                } else {
                    DEFAULT_DYNAMIC_SEED
                }
                DynamicColorSchemeFactory.generatePureBlackScheme(seed).harmonized()
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

    androidx.compose.runtime.CompositionLocalProvider(
        LocalDynamicThemeState provides DynamicThemeState(
            seedColor = seedColor?.let { androidx.compose.ui.graphics.Color(it) } ?: androidx.compose.ui.graphics.Color(0xFF4A7DFF),
            isDark = isDark,
            themeMode = themeMode
        ),
        LocalPlayerColorScheme provides playerColorScheme,
        LocalExpressiveShapes provides ExpressiveShapes(),
        LocalExpressiveTypography provides ExpressiveTypography(
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
        ),
        LocalExpressiveMotion provides ExpressiveMotion()
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
