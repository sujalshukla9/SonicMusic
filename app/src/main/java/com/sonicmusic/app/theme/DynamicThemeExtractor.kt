package com.sonicmusic.app.theme

import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import dev.teogor.paletteon.quantize.QuantizerCelebi
import dev.teogor.paletteon.score.Score
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class ArtworkColors(val primary: Int, val complementary: Int? = null)

/**
 * High-performance, production-ready Content-Based Dynamic Theme Extractor.
 *
 * Implements a hybrid extraction pipeline combining AndroidX Palette and
 * Material Color Utilities (Celebi Quantization + CAM16 Scoring).
 *
 * Candidate Selection Rules:
 * 1. Reject transparent or translucent colors (alpha < 200).
 * 2. Reject nearly black colors (lightness < 0.08).
 * 3. Reject nearly white colors (lightness > 0.94).
 * 4. Reject excessively gray/washed out candidates (saturation < 0.16) when better candidates exist.
 * 5. Prioritize visually representative, vibrant artwork colors.
 * 6. Fall back to suitable muted/dominant candidates for low-saturation artwork.
 * 7. Use default neutral seed (0xFF4A7DFF) for strictly monochrome or invalid images.
 */
object DynamicThemeExtractor {

    private const val TAG = "DynamicThemeExtractor"
    const val DEFAULT_FALLBACK_SEED: Int = 0xFF4A7DFF.toInt()

    private const val MIN_ALPHA = 200
    private const val MIN_LIGHTNESS = 0.08f
    private const val MAX_LIGHTNESS = 0.94f
    private const val STRICT_MIN_SATURATION = 0.16f
    private const val RELAXED_MIN_SATURATION = 0.08f

    private data class Candidate(
        val color: Int,
        val score: Float,
        val hue: Float,
        val saturation: Float,
        val lightness: Float,
        val population: Int
    )

    /** Extracts a representative seed and, when present, a distinct artwork accent. */
    suspend fun extractColors(bitmap: Bitmap): ArtworkColors = withContext(Dispatchers.Default) {
        var softwareBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        try {
            // Ensure software bitmap for pixel array access
            softwareBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            } ?: bitmap

            val width = softwareBitmap.width
            val height = softwareBitmap.height
            if (width <= 0 || height <= 0) return@withContext ArtworkColors(DEFAULT_FALLBACK_SEED)

            // Downscale to max 128x128 for rapid, low-memory extraction
            val maxDim = maxOf(width, height)
            val targetDim = 128
            val scale = if (maxDim > targetDim) targetDim.toFloat() / maxDim else 1f
            val targetWidth = (width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (height * scale).toInt().coerceAtLeast(1)

            scaledBitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(softwareBitmap, targetWidth, targetHeight, true)
            } else {
                softwareBitmap
            }

            val totalPixels = scaledBitmap.width * scaledBitmap.height
            val pixels = IntArray(totalPixels)
            scaledBitmap.getPixels(pixels, 0, scaledBitmap.width, 0, 0, scaledBitmap.width, scaledBitmap.height)

            // 1. AndroidX Palette analysis
            val palette = runCatching {
                Palette.from(scaledBitmap).maximumColorCount(32).generate()
            }.getOrNull()

            // 2. Material Color Utilities (Celebi Quantization + CAM16 Scoring)
            val opaquePixels = pixels.filter { (it ushr 24) >= MIN_ALPHA }.toIntArray()
            if (opaquePixels.isEmpty()) return@withContext ArtworkColors(DEFAULT_FALLBACK_SEED)
            val quantizedColors = QuantizerCelebi.quantize(opaquePixels, 64)
            val scoredColors: List<Int> = Score.score(quantizedColors)

            // 3. Assemble and rank candidates
            val candidates = mutableListOf<Candidate>()

            fun addCandidate(color: Int, baseScore: Float, pop: Int) {
                val alpha = (color ushr 24) and 0xFF
                if (alpha < MIN_ALPHA) return

                val hsl = FloatArray(3)
                ColorUtils.colorToHSL(color, hsl)
                val saturation = hsl[1]
                val lightness = hsl[2]

                // Scoring formula:
                // + Base priority (swatch role / MCU rank)
                // + Saturation bonus (rewards rich, vibrant colors)
                // - Distance from optimal mid-tone lightness 0.5 (avoids near-black/near-white extremes)
                // + Population bonus (rewards prominent colors without letting background noise take over)
                val satBonus = saturation * 70f
                val lightPenalty = abs(lightness - 0.5f) * 45f
                val popRatio = if (totalPixels > 0) (pop.toFloat() / totalPixels).coerceIn(0f, 1f) else 0f
                val popBonus = popRatio * 25f

                val totalScore = baseScore + satBonus - lightPenalty + popBonus
                candidates.add(Candidate(color, totalScore, hsl[0], saturation, lightness, pop))
            }

            // Add AndroidX Palette swatches
            palette?.vibrantSwatch?.let { addCandidate(it.rgb, 150f, it.population) }
            palette?.lightVibrantSwatch?.let { addCandidate(it.rgb, 125f, it.population) }
            palette?.darkVibrantSwatch?.let { addCandidate(it.rgb, 125f, it.population) }
            palette?.dominantSwatch?.let { addCandidate(it.rgb, 100f, it.population) }
            palette?.mutedSwatch?.let { addCandidate(it.rgb, 85f, it.population) }
            palette?.lightMutedSwatch?.let { addCandidate(it.rgb, 70f, it.population) }
            palette?.darkMutedSwatch?.let { addCandidate(it.rgb, 70f, it.population) }

            palette?.swatches?.forEach { swatch ->
                addCandidate(swatch.rgb, 60f, swatch.population)
            }

            // Add Material Color Utilities scored colors
            scoredColors.forEachIndexed { index, color ->
                val mcuBase = (140f - (index * 15f)).coerceAtLeast(50f)
                val pop = quantizedColors[color] ?: return@forEachIndexed
                addCandidate(color, mcuBase, pop)
            }

            val usable = candidates.filter { it.lightness in MIN_LIGHTNESS..MAX_LIGHTNESS }
            val primary = usable.filter { it.saturation >= STRICT_MIN_SATURATION }.maxByOrNull { it.score }
                ?: usable.filter { it.saturation >= RELAXED_MIN_SATURATION }.maxByOrNull { it.score }
                ?: return@withContext ArtworkColors(DEFAULT_FALLBACK_SEED)

            val complementary = usable.filter {
                val hueDistance = abs(primary.hue - it.hue)
                minOf(hueDistance, 360f - hueDistance) >= 55f &&
                    it.saturation >= STRICT_MIN_SATURATION && it.population >= opaquePixels.size / 10
            }.maxByOrNull { it.score }
            ArtworkColors(primary.color, complementary?.color)
        } catch (e: Exception) {
            Log.e(TAG, "Seed extraction failed: ${e.message}", e)
            ArtworkColors(DEFAULT_FALLBACK_SEED)
        } finally {
            // Recycle temporary downscaled / copied bitmaps
            if (scaledBitmap != null && scaledBitmap != softwareBitmap && scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            if (softwareBitmap != null && softwareBitmap != bitmap) {
                softwareBitmap.recycle()
            }
        }
    }
}
