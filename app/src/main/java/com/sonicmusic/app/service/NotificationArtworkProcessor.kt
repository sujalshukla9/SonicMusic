package com.sonicmusic.app.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
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
        return centerCropRoundedSquare(source, safeSize)
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
}
