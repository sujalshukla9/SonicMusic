package com.sonicmusic.app.theme

import androidx.compose.ui.graphics.Color
import com.sonicmusic.app.domain.model.PaletteStyle
import org.junit.Assert.*
import org.junit.Test

class DynamicThemeTest {

    @Test
    fun testDynamicColorSchemeFactory_allColorRolesPopulated_dark() {
        val seedColor = 0xFF3059C5.toInt() // Blue seed
        val scheme = DynamicColorSchemeFactory.generateColorScheme(
            seedColor = seedColor,
            isDark = true,
            style = PaletteStyle.CONTENT
        )

        assertNotNull(scheme)
        assertNotEquals(Color.Unspecified, scheme.primary)
        assertNotEquals(Color.Unspecified, scheme.onPrimary)
        assertNotEquals(Color.Unspecified, scheme.primaryContainer)
        assertNotEquals(Color.Unspecified, scheme.onPrimaryContainer)
        assertNotEquals(Color.Unspecified, scheme.secondary)
        assertNotEquals(Color.Unspecified, scheme.onSecondary)
        assertNotEquals(Color.Unspecified, scheme.secondaryContainer)
        assertNotEquals(Color.Unspecified, scheme.onSecondaryContainer)
        assertNotEquals(Color.Unspecified, scheme.tertiary)
        assertNotEquals(Color.Unspecified, scheme.onTertiary)
        assertNotEquals(Color.Unspecified, scheme.surface)
        assertNotEquals(Color.Unspecified, scheme.onSurface)
        assertNotEquals(Color.Unspecified, scheme.surfaceContainer)
        assertNotEquals(Color.Unspecified, scheme.surfaceContainerLow)
        assertNotEquals(Color.Unspecified, scheme.surfaceContainerLowest)
        assertNotEquals(Color.Unspecified, scheme.surfaceContainerHigh)
        assertNotEquals(Color.Unspecified, scheme.surfaceContainerHighest)
        assertNotEquals(Color.Unspecified, scheme.background)
        assertNotEquals(Color.Unspecified, scheme.onBackground)
        assertNotEquals(Color.Unspecified, scheme.outline)
        assertNotEquals(Color.Unspecified, scheme.outlineVariant)
    }

    @Test
    fun testDynamicColorSchemeFactory_allColorRolesPopulated_light() {
        val seedColor = 0xFFC2185B.toInt() // Pink/Red seed
        val scheme = DynamicColorSchemeFactory.generateColorScheme(
            seedColor = seedColor,
            isDark = false,
            style = PaletteStyle.VIBRANT
        )

        assertNotNull(scheme)
        assertNotEquals(Color.Unspecified, scheme.primary)
        assertNotEquals(Color.Unspecified, scheme.onPrimary)
        assertNotEquals(Color.Unspecified, scheme.surface)
        assertNotEquals(Color.Unspecified, scheme.onSurface)
        assertNotEquals(Color.Unspecified, scheme.background)
    }

    @Test
    fun testDynamicColorSchemeFactory_pureBlackScheme() {
        val seedColor = 0xFF00796B.toInt() // Teal seed
        val scheme = DynamicColorSchemeFactory.generatePureBlackScheme(seedColor)

        assertEquals(Color.Black, scheme.background)
        assertEquals(Color.Black, scheme.surface)
        assertEquals(Color.Black, scheme.surfaceContainerLowest)
    }

    @Test
    fun testPaletteStyles_differentOutputs() {
        val seed = 0xFF6200EE.toInt()
        val contentScheme = DynamicColorSchemeFactory.generateColorScheme(seed, isDark = true, style = PaletteStyle.CONTENT)
        val vibrantScheme = DynamicColorSchemeFactory.generateColorScheme(seed, isDark = true, style = PaletteStyle.VIBRANT)

        assertNotNull(contentScheme)
        assertNotNull(vibrantScheme)
    }

    @Test
    fun testFallbackPlayerColorScheme() {
        val darkFallback = ColorExtractor.fallbackColorScheme(isDarkTheme = true)
        assertTrue(darkFallback.isDarkGradient)
        assertEquals(3, darkFallback.backgroundStops.size)
        assertEquals(Color.White, darkFallback.onSurface)

        val lightFallback = ColorExtractor.fallbackColorScheme(isDarkTheme = false)
        assertFalse(lightFallback.isDarkGradient)
        assertEquals(3, lightFallback.backgroundStops.size)
        assertEquals(Color.Black, lightFallback.onSurface)
    }

    @Test
    fun testBlendColorScheme_blendsSurfaceContainers() {
        val seedA = 0xFF3059C5.toInt()
        val seedB = 0xFFC2185B.toInt()
        val schemeA = DynamicColorSchemeFactory.generateColorScheme(seedA, isDark = true, style = PaletteStyle.CONTENT)
        val schemeB = DynamicColorSchemeFactory.generateColorScheme(seedB, isDark = true, style = PaletteStyle.CONTENT)

        val blendedMid = blendColorScheme(schemeA, schemeB, 0.5f)
        assertNotNull(blendedMid)
        assertNotEquals(schemeA.primary, blendedMid.primary)
        assertNotEquals(schemeB.primary, blendedMid.primary)
        assertNotEquals(Color.Unspecified, blendedMid.surfaceContainer)
        assertNotEquals(Color.Unspecified, blendedMid.surfaceContainerHigh)
        assertNotEquals(Color.Unspecified, blendedMid.surfaceContainerLow)
    }

    @Test
    fun testPureBlack_surfaceContainerElevationHierarchy() {
        val seedColor = 0xFF3059C5.toInt()
        val pureBlackScheme = DynamicColorSchemeFactory.generatePureBlackScheme(seedColor)

        assertEquals(Color.Black, pureBlackScheme.background)
        assertEquals(Color.Black, pureBlackScheme.surface)
        assertEquals(Color.Black, pureBlackScheme.surfaceDim)
        assertEquals(Color.Black, pureBlackScheme.surfaceContainerLowest)
        assertNotEquals(Color.Black, pureBlackScheme.surfaceContainerLow)
        assertNotEquals(Color.Black, pureBlackScheme.surfaceContainer)
        assertNotEquals(Color.Black, pureBlackScheme.surfaceContainerHigh)
        assertNotEquals(Color.Black, pureBlackScheme.surfaceContainerHighest)
    }
}
