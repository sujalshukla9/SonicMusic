package com.sonicmusic.app.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * Centralized motion tokens adhering to Material 3 Expressive motion specifications.
 * Ensures consistent durations, physics-based springs, and natural bezier curves across the app.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object MotionTokens {
    val expressiveMotionScheme: MotionScheme = MotionScheme.expressive()

    val reducedMotionScheme: MotionScheme = object : MotionScheme {
        override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
        override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
        override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
        override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()
        override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
        override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = snap()
    }

    fun <T> spatial(): FiniteAnimationSpec<T> = expressiveMotionScheme.defaultSpatialSpec()
    fun <T> quickSpatial(): FiniteAnimationSpec<T> = expressiveMotionScheme.fastSpatialSpec()
    fun <T> effects(): FiniteAnimationSpec<T> = expressiveMotionScheme.defaultEffectsSpec()
    fun <T> quickEffects(): FiniteAnimationSpec<T> = expressiveMotionScheme.fastEffectsSpec()

    // Standardized duration tokens
    const val DurationFast = 150       // Controls, toggles, icon morphs (100–180ms)
    const val DurationStandard = 280   // Cards, sheets, dialogs, player controls (200–350ms)
    const val DurationLarge = 400      // Full player, bottom sheets, navigation transitions (350–500ms)
    const val DurationTheme = 800      // Dynamic theme color extraction interpolation (~800ms)
    const val FadeThroughDelay = 35
    const val ShimmerPeriod = 1000
    const val PlaybackProgressInterval = 250
    const val SeekFeedbackHold = 580
    const val SeekWavePeriod = 1400
    const val RecordPeriod = 8000

    // Material 3 Emphasized & Natural Easing curves
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    // Physics-based spring tokens
    val InteractiveSpring: FiniteAnimationSpec<Float> = quickSpatial()

    val PanelSpring: SpringSpec<Float> = spring(
        dampingRatio = 0.94f,
        stiffness = 350f,
        visibilityThreshold = 0.001f
    )

    fun <T> linearTween(durationMillis: Int): TweenSpec<T> =
        tween(durationMillis = durationMillis, easing = LinearEasing)

    fun <T> fastTween(delayMillis: Int = 0): TweenSpec<T> =
        tween(durationMillis = DurationFast, delayMillis = delayMillis, easing = EmphasizedDecelerate)

    fun <T> standardTween(delayMillis: Int = 0): TweenSpec<T> =
        tween(durationMillis = DurationStandard, delayMillis = delayMillis, easing = EmphasizedEasing)

    fun <T> largeTween(delayMillis: Int = 0): TweenSpec<T> =
        tween(durationMillis = DurationLarge, delayMillis = delayMillis, easing = EmphasizedDecelerate)
}

internal fun contentMotion(reduced: Boolean): ContentTransform =
    if (reduced) ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
    else ContentTransform(fadeIn(MotionTokens.effects()), fadeOut(MotionTokens.quickEffects()), sizeTransform = null)

internal fun iconMotion(reduced: Boolean): ContentTransform {
    if (reduced) return contentMotion(true)
    val enter = scaleIn(initialScale = 0.82f, animationSpec = MotionTokens.quickSpatial()) + fadeIn(MotionTokens.effects())
    val exit = scaleOut(targetScale = 0.9f, animationSpec = MotionTokens.quickSpatial()) + fadeOut(MotionTokens.quickEffects())
    return enter togetherWith exit
}

/**
 * Returns `true` when the system "Remove animations" or "Animator duration scale = 0"
 * setting is active, indicating the user prefers reduced motion.
 *
 * Use this to conditionally disable **decorative** animations (parallax, bouncing shapes,
 * album art morphing) while keeping **functional** transitions (screen navigation,
 * state changes) intact — per M3 accessibility guidance.
 */
private fun isReducedMotion(context: android.content.Context): Boolean {
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
    return animatorScale == 0f || transitionScale == 0f
}

internal val LocalReducedMotion = compositionLocalOf<Boolean?> { null }

@Composable
internal fun observeReducedMotion(): Boolean {
    val context = LocalContext.current.applicationContext
    var reducedMotion by remember(context) { mutableStateOf(isReducedMotion(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reducedMotion = isReducedMotion(context)
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE), false, observer)
        reducedMotion = isReducedMotion(context)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reducedMotion
}

@Composable
fun shouldReduceMotion(): Boolean = LocalReducedMotion.current ?: observeReducedMotion()

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
    if (!enabled || shouldReduceMotion()) return this
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (isPressed) targetScale else 1f,
        animationSpec = if (isPressed) MotionTokens.quickEffects() else MotionTokens.InteractiveSpring,
        label = "press_scale"
    )
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
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
    targetScale: Float = 0.96f,
    role: Role? = null,
    onClickLabel: String? = null
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }

    val clickableModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = ripple(),
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
            onClick = onClick,
            onLongClick = onLongClick
        )
    } else {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = ripple(),
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
            onClick = onClick
        )
    }

    return this
        .pressScale(interactionSource, targetScale, enabled)
        .then(clickableModifier)
}

