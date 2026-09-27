package com.sonicmusic.app.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * Centralized motion tokens adhering to Material 3 Expressive motion specifications.
 * Ensures consistent durations, physics-based springs, and natural bezier curves across the app.
 */
object MotionTokens {
    // Standardized duration tokens
    const val DurationFast = 150       // Controls, toggles, icon morphs (100–180ms)
    const val DurationStandard = 280   // Cards, sheets, dialogs, player controls (200–350ms)
    const val DurationLarge = 400      // Full player, bottom sheets, navigation transitions (350–500ms)
    const val DurationTheme = 600      // Dynamic theme color extraction interpolation (500–800ms)

    // Material 3 Emphasized & Natural Easing curves
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    // Physics-based spring tokens
    val InteractiveSpring: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    val SettledSpring: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    fun <T> fastTween(delayMillis: Int = 0): TweenSpec<T> =
        tween(durationMillis = DurationFast, delayMillis = delayMillis, easing = EmphasizedDecelerate)

    fun <T> standardTween(delayMillis: Int = 0): TweenSpec<T> =
        tween(durationMillis = DurationStandard, delayMillis = delayMillis, easing = EmphasizedEasing)

    fun <T> largeTween(delayMillis: Int = 0): TweenSpec<T> =
        tween(durationMillis = DurationLarge, delayMillis = delayMillis, easing = EmphasizedDecelerate)
}

/**
 * Returns `true` when the system "Remove animations" or "Animator duration scale = 0"
 * setting is active, indicating the user prefers reduced motion.
 *
 * Use this to conditionally disable **decorative** animations (parallax, bouncing shapes,
 * album art morphing) while keeping **functional** transitions (screen navigation,
 * state changes) intact — per M3 accessibility guidance.
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

/**
 * Tactile touch feedback modifier that subtly scales an element down on press and
 * springs back naturally on release.
 *
 * Utilizes [Modifier.graphicsLayer] so dimensions remain 100% stable with zero
 * layout shifts or sibling element reflows. Respects [shouldReduceMotion].
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    targetScale: Float = 0.96f,
    enabled: Boolean = true
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = shouldReduceMotion()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled && !reduceMotion) targetScale else 1f,
        animationSpec = MotionTokens.InteractiveSpring,
        label = "press_scale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Reusable pressable modifier that combines subtle spring-based scale feedback,
 * Material 3 ripple indication, and click handling into a single high-performance modifier.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    targetScale: Float = 0.96f
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = shouldReduceMotion()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled && !reduceMotion) targetScale else 1f,
        animationSpec = MotionTokens.InteractiveSpring,
        label = "pressable_scale"
    )

    val clickableModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = ripple(),
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick
        )
    } else {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = ripple(),
            enabled = enabled,
            onClick = onClick
        )
    }

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(clickableModifier)
}

