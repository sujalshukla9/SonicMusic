package com.sonicmusic.app.features.player.presentation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.util.lerp
import com.sonicmusic.app.theme.MotionTokens
import com.sonicmusic.app.theme.shouldReduceMotion

// Two polygons join into the play triangle and separate into the two pause bars.
private val PlayLeft = floatArrayOf(.32f, .20f, .52f, .33f, .52f, .67f, .32f, .80f)
private val PlayRight = floatArrayOf(.52f, .33f, .78f, .50f, .78f, .50f, .52f, .67f)
private val PauseLeft = floatArrayOf(.28f, .22f, .44f, .22f, .44f, .78f, .28f, .78f)
private val PauseRight = floatArrayOf(.56f, .22f, .72f, .22f, .72f, .78f, .56f, .78f)

@Composable
internal fun PlaybackGlyph(
    playing: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    val progress = animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = if (shouldReduceMotion()) snap() else MotionTokens.quickSpatial(),
        label = "play_pause_glyph"
    )
    val path = remember { Path() }
    Canvas(modifier.semantics { contentDescription = if (playing) "Pause" else "Play" }) {
        val fraction = progress.value.coerceIn(0f, 1f)
        fun append(from: FloatArray, to: FloatArray) {
            path.moveTo(lerp(from[0], to[0], fraction) * size.width, lerp(from[1], to[1], fraction) * size.height)
            for (index in 2 until from.size step 2) {
                path.lineTo(
                    lerp(from[index], to[index], fraction) * size.width,
                    lerp(from[index + 1], to[index + 1], fraction) * size.height
                )
            }
            path.close()
        }
        path.rewind()
        append(PlayLeft, PauseLeft)
        append(PlayRight, PauseRight)
        drawPath(path, tint)
    }
}
