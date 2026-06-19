package com.sonicmusic.app.presentation.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

private class ColorSchemeTransitionState(
    val start: ColorScheme,
    val end: ColorScheme,
    var current: ColorScheme
)

@Composable
fun ColorScheme.animated(
    durationMillis: Int = 1000,
    easing: Easing = FastOutSlowInEasing
): ColorScheme {
    val target = this
    
    // Remember the transition state containing the start, target, and current color scheme
    val transitionState = remember {
        mutableStateOf<ColorSchemeTransitionState?>(null)
    }
    
    // Remember the animator progress (0f to 1f)
    val progress = remember { Animatable(0f) }
    
    val currentState = transitionState.value
    
    if (currentState == null) {
        // Initial setup on first composition
        transitionState.value = ColorSchemeTransitionState(
            start = target,
            end = target,
            current = target
        )
    } else if (currentState.end != target) {
        // The target color scheme changed (either a new song, or user toggled dark/light mode)
        // Set the start scheme to the currently displayed (partially interpolated) scheme.
        transitionState.value = ColorSchemeTransitionState(
            start = currentState.current,
            end = target,
            current = currentState.current
        )
    }
    
    // Reset and launch a fresh animation unconditionally whenever target changes
    LaunchedEffect(target) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = durationMillis, easing = easing)
        )
    }
    
    val activeState = transitionState.value
    return if (activeState != null && progress.value > 0f) {
        val interpolated = blendColorScheme(activeState.start, activeState.end, progress.value)
        activeState.current = interpolated
        interpolated
    } else {
        activeState?.current ?: target
    }
}
