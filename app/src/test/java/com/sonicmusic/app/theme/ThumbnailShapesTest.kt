package com.sonicmusic.app.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.sonicmusic.app.domain.model.ThumbnailShape
import org.junit.Test
import org.junit.Assert.*

class ThumbnailShapesTest {

    @Test
    fun testAllShapesToRoundedPolygonAndComposeShape() {
        val density = Density(1f)
        val testSize = Size(200f, 200f)
        for (shape in ThumbnailShape.entries) {
            println("Testing shape: ${shape.name}")
            val polygon = shape.toRoundedPolygon()
            assertNotNull("Polygon should not be null for $shape", polygon)
            val bounds = polygon.calculateBounds()
            assertNotNull("Bounds should not be null for $shape", bounds)
            
            val composeShape = shape.toComposeShape()
            assertNotNull("ComposeShape should not be null for $shape", composeShape)
            val outline = composeShape.createOutline(testSize, LayoutDirection.Ltr, density)
            assertNotNull("Outline should not be null for $shape", outline)
            
            // Also test zero size safety
            val zeroOutline = composeShape.createOutline(Size.Zero, LayoutDirection.Ltr, density)
            assertNotNull("Zero size outline should not be null for $shape", zeroOutline)
        }
    }

    @Test
    fun testMatrixTransform() {
        val matrix = androidx.compose.ui.graphics.Matrix()
        val width = 2f
        val height = 2f
        val minX = -1f
        val minY = -1f
        val targetWidth = 200f
        val targetHeight = 200f

        matrix.scale(targetWidth / width, targetHeight / height)
        matrix.translate(-minX, -minY)

        val pt = matrix.map(androidx.compose.ui.geometry.Offset(-1f, -1f))
        println("Mapped (-1, -1) with scale then translate: $pt")

        val pt2 = matrix.map(androidx.compose.ui.geometry.Offset(1f, 1f))
        println("Mapped (1, 1) with scale then translate: $pt2")

        val matrix2 = androidx.compose.ui.graphics.Matrix()
        matrix2.translate(-minX, -minY)
        matrix2.scale(targetWidth / width, targetHeight / height)
        val pt3 = matrix2.map(androidx.compose.ui.geometry.Offset(-1f, -1f))
        println("Mapped (-1, -1) with translate then scale: $pt3")
        val pt4 = matrix2.map(androidx.compose.ui.geometry.Offset(1f, 1f))
        println("Mapped (1, 1) with translate then scale: $pt4")
    }
}
