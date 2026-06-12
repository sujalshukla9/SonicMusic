package com.sonicmusic.app.presentation.ui.theme

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Color Scheme containing dynamic theme colors extracted from album art.
 */
data class PlayerColorScheme(
    val primary: Color,
    val secondary: Color,
    val backgroundStops: List<Color>,
    val onSurface: Color,
    val isDarkGradient: Boolean
)

object ColorExtractor {
    // Cache to avoid re-extracting colors for the same bitmap.
    private val colorCache = LruCache<Int, PlayerColorScheme>(50)

    private const val MIN_TEXT_CONTRAST = 4.5f

    /**
     * Extracts dynamic colors from a Bitmap using the Androidx Palette API.
     * Caches the resulting ColorScheme by the bitmap's configuration hash code.
     */
    suspend fun extractColorsFromBitmap(bitmap: Bitmap, isDarkTheme: Boolean): PlayerColorScheme = withContext(Dispatchers.Default) {
        val cacheKey = bitmap.hashCode() xor isDarkTheme.hashCode()
        colorCache.get(cacheKey)?.let { return@withContext it }

        // Generate the Palette from the bitmap
        val palette = try {
            Palette.from(bitmap).generate()
        } catch (e: Exception) {
            return@withContext fallbackColorScheme(isDarkTheme)
        }

        // Extract raw swatches/colors
        val dominantColor = palette.getDominantColor(if (isDarkTheme) 0xFF111318.toInt() else 0xFFFBF8FF.toInt())
        val vibrantColor = palette.getVibrantColor(dominantColor)
        val mutedColor = palette.getMutedColor(dominantColor)
        val darkVibrantColor = palette.getDarkVibrantColor(if (isDarkTheme) 0xFF1E1F25.toInt() else 0xFF8F909A.toInt())
        val lightVibrantColor = palette.getLightVibrantColor(if (isDarkTheme) 0xFFE4E2EB.toInt() else 0xFFFBF8FF.toInt())

        val extracted = if (isDarkTheme) {
            // 1. Dark Mode: Deep vertical gradient stops
            val stop1 = Color(darkVibrantColor)
            // Blend muted color with black to create a smooth, deep secondary stop
            val stop2 = Color(ColorUtils.blendARGB(mutedColor, 0xFF000000.toInt(), 0.6f))
            // Deep background fallback stop
            val stop3 = Color(0xFF111318)

            // Adjust Stop 1 to ensure it isn't overly bright for dark mode backgrounds
            val finalStop1 = if (ColorUtils.calculateLuminance(stop1.toArgb()) > 0.15) {
                Color(ColorUtils.blendARGB(stop1.toArgb(), 0xFF000000.toInt(), 0.5f))
            } else {
                stop1
            }

            val finalStop2 = if (ColorUtils.calculateLuminance(stop2.toArgb()) > 0.1) {
                Color(ColorUtils.blendARGB(stop2.toArgb(), 0xFF000000.toInt(), 0.4f))
            } else {
                stop2
            }

            // High contrast text color based on average background luminance
            val avgBgColor = ColorUtils.blendARGB(finalStop1.toArgb(), stop3.toArgb(), 0.5f)
            val onSurface = calculateContrastColor(avgBgColor)

            PlayerColorScheme(
                primary = Color(vibrantColor),
                secondary = Color(mutedColor),
                backgroundStops = listOf(finalStop1, finalStop2, stop3),
                onSurface = onSurface,
                isDarkGradient = true
            )
        } else {
            // 2. Light Mode: Soft, pastel gradient stops
            val stop1 = Color(lightVibrantColor)
            // Blend muted color with white to create a soft secondary stop
            val stop2 = Color(ColorUtils.blendARGB(mutedColor, 0xFFFFFFFF.toInt(), 0.8f))
            // Clean surface white background
            val stop3 = Color(0xFFFBF8FF)

            // Adjust stops to guarantee they remain light enough for dark text readability
            val finalStop1 = if (ColorUtils.calculateLuminance(stop1.toArgb()) < 0.75) {
                Color(ColorUtils.blendARGB(stop1.toArgb(), 0xFFFFFFFF.toInt(), 0.5f))
            } else {
                stop1
            }

            val finalStop2 = if (ColorUtils.calculateLuminance(stop2.toArgb()) < 0.75) {
                Color(ColorUtils.blendARGB(stop2.toArgb(), 0xFFFFFFFF.toInt(), 0.4f))
            } else {
                stop2
            }

            val avgBgColor = ColorUtils.blendARGB(finalStop1.toArgb(), stop3.toArgb(), 0.5f)
            val onSurface = calculateContrastColor(avgBgColor)

            PlayerColorScheme(
                primary = Color(vibrantColor),
                secondary = Color(mutedColor),
                backgroundStops = listOf(finalStop1, finalStop2, stop3),
                onSurface = onSurface,
                isDarkGradient = false
            )
        }

        colorCache.put(cacheKey, extracted)
        extracted
    }

    /**
     * Determines whether white or black text provides better contrast for a background.
     */
    private fun calculateContrastColor(backgroundColor: Int): Color {
        val whiteContrast = ColorUtils.calculateContrast(0xFFFFFFFF.toInt(), backgroundColor)
        val blackContrast = ColorUtils.calculateContrast(0xFF000000.toInt(), backgroundColor)
        return if (whiteContrast >= blackContrast) Color.White else Color.Black
    }

    /**
     * Standard default dynamic gradient fallbacks when no artwork is active or extraction fails.
     */
    fun fallbackColorScheme(isDarkTheme: Boolean): PlayerColorScheme {
        return if (isDarkTheme) {
            PlayerColorScheme(
                primary = Color(0xFFB7C4FF),
                secondary = Color(0xFFC0C7DD),
                backgroundStops = listOf(
                    Color(0xFF1B1C22),
                    Color(0xFF15161B),
                    Color(0xFF111318)
                ),
                onSurface = Color.White,
                isDarkGradient = true
            )
        } else {
            PlayerColorScheme(
                primary = Color(0xFF3059C5),
                secondary = Color(0xFF555F7A),
                backgroundStops = listOf(
                    Color(0xFFE8EBFC),
                    Color(0xFFF1F3FA),
                    Color(0xFFFBF8FF)
                ),
                onSurface = Color.Black,
                isDarkGradient = false
            )
        }
    }
}
