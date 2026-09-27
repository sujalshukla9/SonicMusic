package com.sonicmusic.app.theme

import android.graphics.Bitmap
import dev.teogor.paletteon.quantize.QuantizerCelebi
import dev.teogor.paletteon.score.Score
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DynamicThemeExtractor {

    /**
     * Extract seed color from a Bitmap — ViTune's core logic.
     *
     * Steps:
     * 1. Downscale bitmap to 128x128 for intractability
     * 2. Extract pixel array
     * 3. Quantize using QuantizerCelebi (perceptual grouping)
     * 4. Score the quantized colors for "theme-worthiness"
     * 5. Return the top-scored color as the seed
     */
    suspend fun extractSeedColor(bitmap: Bitmap): Int {
        return withContext(Dispatchers.Default) {
            try {
                val softwareBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    bitmap
                } ?: bitmap

                // Step 1: Downscale for performance
                val width = softwareBitmap.width
                val height = softwareBitmap.height
                if (width == 0 || height == 0) return@withContext 0xFF4A7DFF.toInt()

                val scale = 64f / maxOf(width, height)
                val targetWidth = (width * scale).toInt().coerceAtLeast(1)
                val targetHeight = (height * scale).toInt().coerceAtLeast(1)

                val scaledBitmap = Bitmap.createScaledBitmap(softwareBitmap, targetWidth, targetHeight, true)
                
                // Step 2: Get pixels
                val pixels = IntArray(scaledBitmap.width * scaledBitmap.height)
                scaledBitmap.getPixels(
                    pixels, 0, scaledBitmap.width,
                    0, 0, scaledBitmap.width, scaledBitmap.height
                )
                
                // Step 3: Quantize
                val quantizedColors: Map<Int, Int> = QuantizerCelebi.quantize(pixels, 64)
                
                // Step 4: Score
                val scoredColors: List<Int> = Score.score(quantizedColors)
                
                // Release temporary bitmaps
                if (scaledBitmap != softwareBitmap && scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }
                if (softwareBitmap != bitmap) {
                    softwareBitmap.recycle()
                }

                // Step 5: Return top seed (fallback to default blue)
                scoredColors.firstOrNull() ?: 0xFF4A7DFF.toInt()
            } catch (e: Exception) {
                android.util.Log.e("DynamicThemeExtractor", "Seed extraction failed: ${e.message}", e)
                0xFF4A7DFF.toInt()
            }
        }
    }
}
