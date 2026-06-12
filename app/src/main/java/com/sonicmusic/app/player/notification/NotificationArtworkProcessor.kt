package com.sonicmusic.app.player.notification

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.abs
import kotlin.math.max

/**
 * Normalizes notification artwork for media notifications:
 * - center-crops artwork to eliminate side bars/letterboxing
 * - rounded corners
 */
object NotificationArtworkProcessor {

    fun fromDrawable(drawable: Drawable, targetSize: Int): Bitmap {
        val safeSize = targetSize.coerceAtLeast(64)
        val source = when (drawable) {
            is BitmapDrawable -> drawable.bitmap
            else -> {
                val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else safeSize
                val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else safeSize
                drawable.toBitmap(width = max(1, width), height = max(1, height))
            }
        }
        val trimmed = trimLikelySideBars(source)
        return centerCropRoundedSquare(trimmed, safeSize)
    }

    fun centerCropRoundedSquare(
        source: Bitmap,
        targetSize: Int,
        cornerRadiusPercent: Float = 0.14f
    ): Bitmap {
        val safeTarget = targetSize.coerceAtLeast(64)
        if (source.width <= 0 || source.height <= 0) {
            return Bitmap.createBitmap(safeTarget, safeTarget, Bitmap.Config.ARGB_8888)
        }

        val output = Bitmap.createBitmap(safeTarget, safeTarget, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val radius = safeTarget * cornerRadiusPercent.coerceIn(0f, 0.5f)
        val rect = RectF(0f, 0f, safeTarget.toFloat(), safeTarget.toFloat())
        val roundPath = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }

        // Cover the entire square to avoid visible bars in system media controls.
        val coverScale = max(
            safeTarget / source.width.toFloat(),
            safeTarget / source.height.toFloat()
        )
        val dstWidth = source.width * coverScale
        val dstHeight = source.height * coverScale
        val dstRect = RectF(
            (safeTarget - dstWidth) / 2f,
            (safeTarget - dstHeight) / 2f,
            (safeTarget + dstWidth) / 2f,
            (safeTarget + dstHeight) / 2f
        )

        val saveCount = canvas.save()
        canvas.clipPath(roundPath)
        canvas.drawBitmap(
            source,
            Rect(0, 0, source.width, source.height),
            dstRect,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        canvas.restoreToCount(saveCount)

        return output
    }

    private fun trimLikelySideBars(source: Bitmap): Bitmap {
        if (source.width < 80 || source.height < 80) return source
        if (source.width <= source.height) return source

        // Only trim images that are clearly landscape (aspect ratio >= ~1.2:1).
        // Near-square images rarely have true sidebars; trimming them
        // produces asymmetric artwork.
        val aspectRatio = source.width.toFloat() / source.height.toFloat()
        if (aspectRatio < 1.2f) return source

        val maxTrim = (source.width * 0.34f).toInt().coerceAtLeast(0)
        if (maxTrim <= 0) return source

        val leftTrim = detectUniformEdgeWidth(source, fromLeft = true, maxTrimPx = maxTrim)
        val rightTrim = detectUniformEdgeWidth(source, fromLeft = false, maxTrimPx = maxTrim)
        val minPerSide = (source.width * 0.05f).toInt().coerceAtLeast(10)
        val trimmedLeft = if (leftTrim >= minPerSide) leftTrim else 0
        val trimmedRight = if (rightTrim >= minPerSide) rightTrim else 0
        val newWidth = source.width - trimmedLeft - trimmedRight
        val base = if (newWidth <= source.height / 2) {
            source
        } else {
            Bitmap.createBitmap(source, trimmedLeft, 0, newWidth, source.height)
        }

        // Some thumbnails have non-uniform bars that evade edge-color trimming.
        // Focus the crop on the most detailed vertical window when edges are low-detail.
        return focusOnDetailedRegion(base)
    }

    private fun focusOnDetailedRegion(source: Bitmap): Bitmap {
        if (source.width <= source.height) return source
        val width = source.width
        val height = source.height
        val samples = 14
        val top = (height * 0.12f).toInt()
        val bottom = (height * 0.88f).toInt().coerceAtLeast(top + 1)
        val step = ((bottom - top) / (samples - 1).coerceAtLeast(1)).coerceAtLeast(1)

        val detailScores = FloatArray(width) { x ->
            val avg = averageColumnColor(source, x, top, step, samples)
            columnVariance(source, x, top, step, samples, avg)
        }

        val centerStart = (width * 0.3f).toInt().coerceIn(0, width - 1)
        val centerEnd = (width * 0.7f).toInt().coerceIn(centerStart + 1, width)
        val edgeWindow = (width * 0.14f).toInt().coerceAtLeast(1)
        val leftEdgeMean = averageRange(detailScores, 0, edgeWindow)
        val rightEdgeMean = averageRange(detailScores, width - edgeWindow, width)
        val centerMean = averageRange(detailScores, centerStart, centerEnd)
        val edgeMean = (leftEdgeMean + rightEdgeMean) / 2f

        // If edges are not significantly flatter than center, preserve normal crop.
        if (centerMean <= 0f || edgeMean > centerMean * 0.62f) return source

        val prefix = FloatArray(width + 1)
        for (i in 0 until width) {
            prefix[i + 1] = prefix[i] + detailScores[i]
        }

        val fullWindow = height.coerceAtMost(width)
        val minWindow = (height * 0.58f).toInt().coerceAtLeast((height * 0.5f).toInt())
        val candidateWindows = linkedSetOf(
            fullWindow,
            (height * 0.92f).toInt(),
            (height * 0.84f).toInt(),
            (height * 0.76f).toInt(),
            (height * 0.68f).toInt(),
            minWindow
        ).map { it.coerceIn(minWindow, fullWindow) }
            .distinct()
            .sortedDescending()

        var fullDensity = Float.NEGATIVE_INFINITY
        var bestDensity = Float.NEGATIVE_INFINITY
        var bestStart = ((width - fullWindow) / 2).coerceAtLeast(0)
        var bestWindow = fullWindow
        val centerX = width / 2f

        candidateWindows.forEach { window ->
            if (window <= 0 || window > width) return@forEach
            var localBestDensity = Float.NEGATIVE_INFINITY
            var localBestStart = 0
            val maxStart = width - window
            for (start in 0..maxStart) {
                val rawScore = prefix[start + window] - prefix[start]
                val density = rawScore / window.toFloat()
                val windowCenter = start + (window / 2f)
                val centerPenalty = abs(windowCenter - centerX) * 0.012f
                val effectiveDensity = density - centerPenalty
                if (effectiveDensity > localBestDensity) {
                    localBestDensity = effectiveDensity
                    localBestStart = start
                }
            }

            if (window == fullWindow) {
                fullDensity = localBestDensity
            }

            if (localBestDensity > bestDensity) {
                bestDensity = localBestDensity
                bestStart = localBestStart
                bestWindow = window
            }
        }

        // Only use narrower window when detail density is meaningfully better.
        if (bestWindow < fullWindow && bestDensity < fullDensity * 1.14f) {
            bestWindow = fullWindow
            bestStart = ((width - fullWindow) / 2).coerceAtLeast(0)
        }

        if (bestWindow <= 0 || bestStart < 0 || bestStart + bestWindow > width) return source
        if (bestWindow == width) return source

        return Bitmap.createBitmap(source, bestStart, 0, bestWindow, height)
    }

    private fun averageRange(values: FloatArray, start: Int, endExclusive: Int): Float {
        if (values.isEmpty()) return 0f
        val safeStart = start.coerceIn(0, values.size - 1)
        val safeEnd = endExclusive.coerceIn(safeStart + 1, values.size)
        var sum = 0f
        var count = 0
        for (i in safeStart until safeEnd) {
            sum += values[i]
            count += 1
        }
        return if (count == 0) 0f else sum / count
    }

    private fun detectUniformEdgeWidth(
        source: Bitmap,
        fromLeft: Boolean,
        maxTrimPx: Int
    ): Int {
        val width = source.width
        val height = source.height
        val samples = 14
        val top = (height * 0.12f).toInt()
        val bottom = (height * 0.88f).toInt().coerceAtLeast(top + 1)
        val step = ((bottom - top) / (samples - 1).coerceAtLeast(1)).coerceAtLeast(1)

        val baseX = if (fromLeft) 0 else width - 1
        val base = averageColumnColor(source, baseX, top, step, samples)

        var trim = 0
        for (offset in 0 until maxTrimPx.coerceAtMost(width / 3)) {
            val x = if (fromLeft) offset else width - 1 - offset
            val avg = averageColumnColor(source, x, top, step, samples)
            val delta = colorDistance(base, avg)
            val variance = columnVariance(source, x, top, step, samples, avg)

            if (delta <= 16f && variance <= 22f) {
                trim = offset + 1
            } else {
                break
            }
        }
        return trim
    }

    private fun averageColumnColor(
        source: Bitmap,
        x: Int,
        top: Int,
        step: Int,
        samples: Int
    ): IntArray {
        var r = 0
        var g = 0
        var b = 0
        var count = 0
        var y = top
        repeat(samples) {
            val safeY = y.coerceIn(0, source.height - 1)
            val pixel = source.getPixel(x.coerceIn(0, source.width - 1), safeY)
            r += (pixel shr 16) and 0xFF
            g += (pixel shr 8) and 0xFF
            b += pixel and 0xFF
            count += 1
            y += step
        }
        val safeCount = count.coerceAtLeast(1)
        return intArrayOf(r / safeCount, g / safeCount, b / safeCount)
    }

    private fun columnVariance(
        source: Bitmap,
        x: Int,
        top: Int,
        step: Int,
        samples: Int,
        avg: IntArray
    ): Float {
        var diff = 0f
        var count = 0
        var y = top
        repeat(samples) {
            val safeY = y.coerceIn(0, source.height - 1)
            val pixel = source.getPixel(x.coerceIn(0, source.width - 1), safeY)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            diff += abs(r - avg[0]) + abs(g - avg[1]) + abs(b - avg[2])
            count += 1
            y += step
        }
        return diff / count.coerceAtLeast(1)
    }

    private fun colorDistance(a: IntArray, b: IntArray): Float {
        return (abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])) / 3f
    }
}
