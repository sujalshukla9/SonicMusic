package com.sonicmusic.app.presentation.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.star
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.toPath
import androidx.graphics.shapes.PointTransformer
import androidx.graphics.shapes.TransformResult
import com.sonicmusic.app.domain.model.ThumbnailShape

/**
 * Custom Compose Shape to handle morphing between two RoundedPolygons.
 */
class MorphComposeShape(
    val morph: Morph,
    val progress: Float
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val composePath = Path()
        val androidPath = morph.toPath(progress)
        composePath.addPath(androidPath.asComposePath())
        
        // Map unit space morph to the actual size of the Composable
        val bounds = morph.calculateBounds()
        val minX = bounds[0]
        val minY = bounds[1]
        val maxX = bounds[2]
        val maxY = bounds[3]
        val width = maxX - minX
        val height = maxY - minY
        
        val matrix = Matrix()
        matrix.scale(size.width / width, size.height / height)
        matrix.translate(-minX, -minY)
        composePath.transform(matrix)
        
        return Outline.Generic(composePath)
    }
}

/**
 * Maps a ThumbnailShape enum value to a graphics-shapes RoundedPolygon.
 */
fun ThumbnailShape.toRoundedPolygon(): RoundedPolygon {
    val polygon = when (this) {
        ThumbnailShape.SQUARE -> RoundedPolygon(
            numVertices = 4,
            rounding = CornerRounding(0f)
        )
        ThumbnailShape.ROUNDED_SQUARE -> RoundedPolygon(
            numVertices = 4,
            rounding = CornerRounding(0.2f, 0.55f)
        )
        ThumbnailShape.CIRCLE -> RoundedPolygon.circle(numVertices = 12)
        ThumbnailShape.SQUIRCLE -> RoundedPolygon(
            numVertices = 4,
            rounding = CornerRounding(0.4f, 0.6f)
        )
        ThumbnailShape.COOKIE -> RoundedPolygon.star(
            numVerticesPerRadius = 9,
            innerRadius = 0.84f,
            rounding = CornerRounding(0.15f)
        )
        ThumbnailShape.CLOVER -> RoundedPolygon.star(
            numVerticesPerRadius = 4,
            innerRadius = 0.55f,
            rounding = CornerRounding(0.25f)
        )
        ThumbnailShape.SCALLOP -> RoundedPolygon.star(
            numVerticesPerRadius = 12,
            innerRadius = 0.9f,
            rounding = CornerRounding(0.08f)
        )
        ThumbnailShape.BURST -> RoundedPolygon.star(
            numVerticesPerRadius = 8,
            innerRadius = 0.65f,
            rounding = CornerRounding(0.05f)
        )
        ThumbnailShape.PILL -> RoundedPolygon(
            numVertices = 4,
            rounding = CornerRounding(0.48f, 0.6f)
        )
        ThumbnailShape.ARCH -> RoundedPolygon(
            numVertices = 4,
            rounding = CornerRounding(0.15f)
        )
    }

    // Regular 4-vertex polygons in graphics-shapes are generated with vertices at 0, 90, 180, 270 degrees,
    // which makes them diamonds. We rotate them by 45 degrees to make them flat-edged (axis-aligned).
    return if (this == ThumbnailShape.SQUARE ||
        this == ThumbnailShape.ROUNDED_SQUARE ||
        this == ThumbnailShape.SQUIRCLE ||
        this == ThumbnailShape.PILL ||
        this == ThumbnailShape.ARCH
    ) {
        polygon.transformed { x, y ->
            val cos45 = 0.70710678f
            val sin45 = 0.70710678f
            val newX = x * cos45 - y * sin45
            val newY = x * sin45 + y * cos45
            TransformResult(newX, newY)
        }
    } else {
        polygon
    }
}

/**
 * Standard Compose Shape wrapper around graphics-shapes RoundedPolygon.
 * Handles bounds calculation and auto-scaling path to match layout constraints.
 */
class RoundedPolygonShape(
    val polygon: RoundedPolygon
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val composePath = Path()
        val androidPath = polygon.toPath()
        composePath.addPath(androidPath.asComposePath())
        
        // Map unit space polygon to the actual size of the Composable
        val bounds = polygon.calculateBounds()
        val minX = bounds[0]
        val minY = bounds[1]
        val maxX = bounds[2]
        val maxY = bounds[3]
        val width = maxX - minX
        val height = maxY - minY
        
        val matrix = Matrix()
        matrix.scale(size.width / width, size.height / height)
        matrix.translate(-minX, -minY)
        composePath.transform(matrix)
        
        return Outline.Generic(composePath)
    }
}

/**
 * Definition of a flat-bottom, round-top Arch shape in Compose.
 */
val ArchShape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    moveTo(0f, h)
    lineTo(0f, w / 2f)
    arcTo(
        rect = androidx.compose.ui.geometry.Rect(0f, 0f, w, w),
        startAngleDegrees = 180f,
        sweepAngleDegrees = 180f,
        forceMoveTo = false
    )
    lineTo(w, h)
    close()
}

/**
 * Extension helper to directly convert a ThumbnailShape into a Compose Shape.
 * Uses native Shapes for standard items and polygon shapes for expressive morphs.
 */
fun ThumbnailShape.toComposeShape(): Shape {
    return when (this) {
        ThumbnailShape.SQUARE -> RoundedCornerShape(0.dp)
        ThumbnailShape.ROUNDED_SQUARE -> RoundedCornerShape(12.dp)
        ThumbnailShape.CIRCLE -> CircleShape
        ThumbnailShape.PILL -> RoundedCornerShape(50)
        ThumbnailShape.ARCH -> ArchShape
        else -> RoundedPolygonShape(this.toRoundedPolygon())
    }
}

/**
 * CompositionLocal to expose the user-configured ThumbnailShape down the composable tree.
 */
val LocalThumbnailShape = staticCompositionLocalOf { ThumbnailShape.ROUNDED_SQUARE }
