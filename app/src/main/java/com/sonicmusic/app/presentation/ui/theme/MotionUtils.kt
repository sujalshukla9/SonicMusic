package com.sonicmusic.app.presentation.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Returns `true` when the system "Remove animations" or " Animator duration scale = 0"
 * setting is active, indicating the user prefers reduced motion.
 *
 * Use this to conditionally disable **decorative** animations (parallax, bouncing shapes,
 * album art morphing) while keeping **functional** transitions (screen navigation,
 * state changes) intact — per M3 accessibility guidance.
 *
 * Usage:
 * ```kotlin
 * val reduceMotion = shouldReduceMotion()
 * val animSpec = if (reduceMotion) snap() else spring(...)
 * ```
 */
@Composable
fun shouldReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val animatorScale = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        } catch (_: Exception) {
            1f
        }
        val transitionScale = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1f
            )
        } catch (_: Exception) {
            1f
        }
        // User has disabled animations if either scale is 0
        animatorScale == 0f || transitionScale == 0f
    }
}
