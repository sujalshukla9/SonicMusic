package com.sonicmusic.app.presentation.ui.theme

import android.util.LruCache
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import dev.teogor.paletteon.scheme.DynamicScheme
import dev.teogor.paletteon.dynamiccolor.MaterialDynamicColors
import dev.teogor.paletteon.hct.Hct
import dev.teogor.paletteon.scheme.SchemeContent
import dev.teogor.paletteon.scheme.SchemeExpressive
import dev.teogor.paletteon.scheme.SchemeFidelity
import dev.teogor.paletteon.scheme.SchemeTonalSpot
import dev.teogor.paletteon.scheme.SchemeVibrant
import com.sonicmusic.app.domain.model.PaletteStyle

object DynamicColorSchemeFactory {

    private val dynamicColors = MaterialDynamicColors()
    private val schemeCache = LruCache<Long, ColorScheme>(8)

    /**
     * Generate a full M3 ColorScheme from a seed color.
     */
    fun generateColorScheme(
        seedColor: Int,
        isDark: Boolean,
        style: PaletteStyle = PaletteStyle.CONTENT,
        contrastLevel: Double = 0.0  // -1.0 to 1.0
    ): ColorScheme {
        // Check cache first
        val cacheKey = seedColor.toLong().shl(32) or
            (if (isDark) 1L.shl(16) else 0L) or
            style.ordinal.toLong().shl(8) or
            ((contrastLevel * 100).toLong() and 0xFF)
        schemeCache.get(cacheKey)?.let { return it }

        val sourceHct = Hct.fromInt(seedColor)
        
        val scheme: DynamicScheme = when (style) {
            PaletteStyle.TONAL_SPOT -> SchemeTonalSpot(sourceHct, isDark, contrastLevel)
            PaletteStyle.CONTENT -> SchemeContent(sourceHct, isDark, contrastLevel)
            PaletteStyle.VIBRANT -> SchemeVibrant(sourceHct, isDark, contrastLevel)
            PaletteStyle.EXPRESSIVE -> SchemeExpressive(sourceHct, isDark, contrastLevel)
            PaletteStyle.FIDELITY -> SchemeFidelity(sourceHct, isDark, contrastLevel)
        }

        return ColorScheme(
            primary = Color(dynamicColors.primary().getArgb(scheme)),
            onPrimary = Color(dynamicColors.onPrimary().getArgb(scheme)),
            primaryContainer = Color(dynamicColors.primaryContainer().getArgb(scheme)),
            onPrimaryContainer = Color(dynamicColors.onPrimaryContainer().getArgb(scheme)),
            inversePrimary = Color(dynamicColors.inversePrimary().getArgb(scheme)),
            
            secondary = Color(dynamicColors.secondary().getArgb(scheme)),
            onSecondary = Color(dynamicColors.onSecondary().getArgb(scheme)),
            secondaryContainer = Color(dynamicColors.secondaryContainer().getArgb(scheme)),
            onSecondaryContainer = Color(dynamicColors.onSecondaryContainer().getArgb(scheme)),
            
            tertiary = Color(dynamicColors.tertiary().getArgb(scheme)),
            onTertiary = Color(dynamicColors.onTertiary().getArgb(scheme)),
            tertiaryContainer = Color(dynamicColors.tertiaryContainer().getArgb(scheme)),
            onTertiaryContainer = Color(dynamicColors.onTertiaryContainer().getArgb(scheme)),
            
            background = Color(dynamicColors.background().getArgb(scheme)),
            onBackground = Color(dynamicColors.onBackground().getArgb(scheme)),
            
            surface = Color(dynamicColors.surface().getArgb(scheme)),
            onSurface = Color(dynamicColors.onSurface().getArgb(scheme)),
            surfaceVariant = Color(dynamicColors.surfaceVariant().getArgb(scheme)),
            onSurfaceVariant = Color(dynamicColors.onSurfaceVariant().getArgb(scheme)),
            surfaceTint = Color(dynamicColors.primary().getArgb(scheme)),
            
            inverseSurface = Color(dynamicColors.inverseSurface().getArgb(scheme)),
            inverseOnSurface = Color(dynamicColors.inverseOnSurface().getArgb(scheme)),
            
            error = Color(dynamicColors.error().getArgb(scheme)),
            onError = Color(dynamicColors.onError().getArgb(scheme)),
            errorContainer = Color(dynamicColors.errorContainer().getArgb(scheme)),
            onErrorContainer = Color(dynamicColors.onErrorContainer().getArgb(scheme)),
            
            outline = Color(dynamicColors.outline().getArgb(scheme)),
            outlineVariant = Color(dynamicColors.outlineVariant().getArgb(scheme)),
            scrim = Color(dynamicColors.scrim().getArgb(scheme)),

            // Surface elevation tints (M3 surface containers)
            surfaceBright = Color(dynamicColors.surfaceBright().getArgb(scheme)),
            surfaceDim = Color(dynamicColors.surfaceDim().getArgb(scheme)),
            surfaceContainer = Color(dynamicColors.surfaceContainer().getArgb(scheme)),
            surfaceContainerHigh = Color(dynamicColors.surfaceContainerHigh().getArgb(scheme)),
            surfaceContainerHighest = Color(dynamicColors.surfaceContainerHighest().getArgb(scheme)),
            surfaceContainerLow = Color(dynamicColors.surfaceContainerLow().getArgb(scheme)),
            surfaceContainerLowest = Color(dynamicColors.surfaceContainerLowest().getArgb(scheme)),
        ).also { schemeCache.put(cacheKey, it) }
    }

    /**
     * Generate Pure Black (AMOLED) variant
     */
    fun generatePureBlackScheme(seedColor: Int): ColorScheme {
        val base = generateColorScheme(seedColor, isDark = true, style = PaletteStyle.CONTENT)
        return base.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceDim = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0A0A0A),
            surfaceContainer = Color(0xFF111111),
            surfaceContainerHigh = Color(0xFF1A1A1A),
            surfaceContainerHighest = Color(0xFF222222),
        )
    }
}
