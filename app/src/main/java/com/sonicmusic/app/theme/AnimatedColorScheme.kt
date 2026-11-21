package com.sonicmusic.app.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

@Composable
fun ColorScheme.animated(
    durationMillis: Int = MotionTokens.DurationStandard,
    easing: Easing = MotionTokens.EmphasizedDecelerate
): ColorScheme {
    val target = this
    val displayed = remember { mutableStateOf(target) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(target, durationMillis, easing) {
        // Capture the last displayed frame when an in-flight transition is cancelled.
        val start = displayed.value
        if (durationMillis <= 0 || start == target) {
            displayed.value = target
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis, easing = easing)) {
                displayed.value = blendColorScheme(start, target, value)
            }
            displayed.value = target
        }
    }
    return if (durationMillis <= 0) target else displayed.value
}
