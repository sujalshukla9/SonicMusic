package com.sonicmusic.app.features.player.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sonicmusic.app.theme.MotionTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class PlayerPanel(val offset: Float) {
    Queue(-1f), Player(0f), Minimized(1f)
}

internal fun settlePlayerPanel(start: PlayerPanel, offset: Float, velocity: Float): PlayerPanel {
    val fling = 1.15f
    return if (start == PlayerPanel.Queue) {
        if (offset > -0.8f || velocity > fling) PlayerPanel.Player else PlayerPanel.Queue
    } else {
        val projectedOffset = offset + velocity.coerceIn(-3f, 3f) * 0.12f
        when {
            offset < -0.16f || (offset < -0.02f && velocity < -fling) -> PlayerPanel.Queue
            offset > 0.02f && projectedOffset > 0.18f -> PlayerPanel.Minimized
            else -> PlayerPanel.Player
        }
    }
}

@Stable
internal class PlayerPanelMotion(
    private val scope: CoroutineScope,
    private val reducedMotion: Boolean,
    initiallyExpanded: Boolean,
    private val onMinimized: () -> Unit
) {
    var offset by mutableFloatStateOf(if (reducedMotion || initiallyExpanded) 0f else 1f)
        private set
    var target by mutableStateOf(PlayerPanel.Player)
        private set

    private var animation: Job? = null

    fun seek(offset: Float) {
        animation?.cancel()
        this.offset = offset.coerceIn(-1f, 1f)
    }

    fun drag(delta: Float, start: PlayerPanel): Float {
        if (target == PlayerPanel.Minimized) return 0f
        animation?.cancel()
        val before = offset
        offset = (before + delta).coerceIn(-1f, if (start == PlayerPanel.Queue) 0f else 1f)
        return offset - before
    }

    fun settle(panel: PlayerPanel, velocity: Float = 0f) {
        animation?.cancel()
        target = panel
        animation = scope.launch {
            if (reducedMotion) {
                offset = panel.offset
            } else {
                Animatable(offset).animateTo(
                    targetValue = panel.offset,
                    animationSpec = MotionTokens.PanelSpring,
                    initialVelocity = velocity.coerceIn(-4f, 4f)
                ) { offset = value.coerceIn(-1f, 1f) }
                offset = panel.offset
            }
            if (panel == PlayerPanel.Minimized) onMinimized()
        }
    }
}
