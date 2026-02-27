@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.sonicmusic.app.presentation.ui.components.pullrefresh

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// ─── Spring presets from the PRD ────────────────────────────────────
private val PullSpring: SpringSpec<Float> = spring(
    dampingRatio = 0.7f,
    stiffness = 200f
)

private val SettleSpring: SpringSpec<Float> = spring(
    dampingRatio = 0.8f,
    stiffness = 400f
)

private val DismissSpring: SpringSpec<Float> = spring(
    dampingRatio = 1.0f,
    stiffness = 600f
)

private val SnapSpring: SpringSpec<Float> = spring(
    dampingRatio = 0.5f,
    stiffness = 800f
)

// ─── Indicator sizing / offsets (dp → converted to px inside composable) ──
private val INDICATOR_SIZE = 56.dp
private val INDICATOR_RESTING_OFFSET = 36.dp   // centre-Y while refreshing
private val CONTENT_RESTING_OFFSET = 72.dp     // content pushed down while refreshing
private val SPINNER_STROKE = 4.dp
private val SPINNER_ICON_SIZE = 36.dp

/**
 * Material Design 3 Expressive pull-to-refresh wrapper inspired by Google Photos 2024/2025.
 *
 * Drop this around any vertically-scrollable content.  It intercepts vertical scroll via
 * [NestedScrollConnection] and drives a physics-based indicator through the state machine
 * defined in [PullToRefreshState].
 *
 * @param isRefreshing   driven by the ViewModel — `true` while the network call is in flight.
 * @param onRefresh      called once when the user releases in the ARMED state.
 * @param refreshResult  set by the ViewModel after the refresh completes (`SUCCESS` / `ERROR`).
 * @param onRefreshResultConsumed called after the completing animation finishes.
 * @param enabled        set to `false` to disable the gesture entirely.
 * @param content        the scrollable content below.
 */
@Composable
fun M3ExpressivePullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    refreshResult: RefreshResult? = null,
    onRefreshResultConsumed: () -> Unit = {},
    enabled: Boolean = true,
    indicatorPadding: PaddingValues = PaddingValues(0.dp),
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val state = rememberPullToRefreshState()

    // Px conversions
    val topPaddingPx = with(density) { indicatorPadding.calculateTopPadding().toPx() }
    val thresholdPx = with(density) { state.threshold.toPx() }
    val maxDragPx = with(density) { state.maxDragDistance.toPx() }
    val indicatorRestOffsetPx = with(density) { INDICATOR_RESTING_OFFSET.toPx() } + topPaddingPx
    val contentRestOffsetPx = with(density) { CONTENT_RESTING_OFFSET.toPx() } + topPaddingPx

    // ── Animatable values ──
    val contentOffsetAnim = remember { Animatable(0f) }
    val indicatorScaleAnim = remember { Animatable(0f) }
    val indicatorAlphaAnim = remember { Animatable(0f) }

    // ── Track haptic milestones fired during current gesture ──
    var lastHapticMilestone by remember { mutableIntStateOf(0) }

    // ── Completion icon progress (0→1 for draw-on anim) ──
    var completionIconProgress by remember { mutableFloatStateOf(0f) }

    // ── Reduced motion check ──
    val reducedMotion = remember {
        try {
            android.provider.Settings.Global.getFloat(
                view.context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE
            ) == 0f
        } catch (_: Exception) { false }
    }

    // ── React to external isRefreshing changes ──
    LaunchedEffect(isRefreshing) {
        if (isRefreshing && state.status != RefreshStatus.REFRESHING) {
            // ViewModel started refreshing (e.g. programmatic)
            state.status = RefreshStatus.REFRESHING
            state.refreshStartTimeMs = System.currentTimeMillis()
            if (!reducedMotion) {
                launch { contentOffsetAnim.animateTo(contentRestOffsetPx, SettleSpring) }
                launch { indicatorScaleAnim.animateTo(1f, SettleSpring) }
                launch { indicatorAlphaAnim.animateTo(1f, tween(200)) }
            } else {
                contentOffsetAnim.snapTo(contentRestOffsetPx)
                indicatorScaleAnim.snapTo(1f)
                indicatorAlphaAnim.snapTo(1f)
            }
        }
    }

    // ── React to refreshResult (success / error) ──
    LaunchedEffect(refreshResult) {
        if (refreshResult == null || state.status != RefreshStatus.REFRESHING) return@LaunchedEffect

        // Ensure minimum display time
        val elapsed = System.currentTimeMillis() - state.refreshStartTimeMs
        val remaining = PullToRefreshState.MIN_REFRESH_DISPLAY_MS - elapsed
        if (remaining > 0) delay(remaining)

        when (refreshResult) {
            RefreshResult.SUCCESS -> {
                state.status = RefreshStatus.COMPLETING_SUCCESS
                performHaptic(view, HapticType.SUCCESS)

                // Draw-on checkmark
                completionIconProgress = 0f
                val animatable = Animatable(0f)
                launch {
                    animatable.animateTo(1f, tween(300))
                }
                launch {
                    while (animatable.isRunning) {
                        completionIconProgress = animatable.value
                        delay(16)
                    }
                    completionIconProgress = 1f
                }

                delay(PullToRefreshState.SUCCESS_DISPLAY_MS)
            }
            RefreshResult.ERROR, RefreshResult.TIMEOUT -> {
                state.status = RefreshStatus.COMPLETING_ERROR
                performHaptic(view, HapticType.ERROR)

                completionIconProgress = 0f
                val animatable = Animatable(0f)
                launch {
                    animatable.animateTo(1f, tween(300))
                }
                launch {
                    while (animatable.isRunning) {
                        completionIconProgress = animatable.value
                        delay(16)
                    }
                    completionIconProgress = 1f
                }

                delay(PullToRefreshState.ERROR_DISPLAY_MS)
            }
        }

        // Dismiss
        state.lastRefreshTimeMs = System.currentTimeMillis()
        if (!reducedMotion) {
            val j1 = launch { indicatorScaleAnim.animateTo(0f, DismissSpring) }
            val j2 = launch { indicatorAlphaAnim.animateTo(0f, tween(150)) }
            val j3 = launch { contentOffsetAnim.animateTo(0f, DismissSpring) }
            joinAll(j1, j2, j3)
        } else {
            indicatorScaleAnim.snapTo(0f)
            indicatorAlphaAnim.snapTo(0f)
            contentOffsetAnim.snapTo(0f)
        }

        state.status = RefreshStatus.IDLE
        state.dragProgress = 0f
        state.rawDragOffset = 0f
        state.indicatorOffset = 0f
        state.contentOffset = 0f
        completionIconProgress = 0f
        onRefreshResultConsumed()
    }

    // ── Refreshing timeout watchdog ──
    LaunchedEffect(state.status) {
        if (state.status == RefreshStatus.REFRESHING) {
            delay(PullToRefreshState.MAX_REFRESH_TIMEOUT_MS)
            if (state.status == RefreshStatus.REFRESHING) {
                // Auto-trigger error state via timeout result
                // The ViewModel should ideally handle this, but we have a safety net
                state.status = RefreshStatus.COMPLETING_ERROR
                performHaptic(view, HapticType.ERROR)
                delay(PullToRefreshState.ERROR_DISPLAY_MS)
                state.lastRefreshTimeMs = System.currentTimeMillis()
                
                if (!reducedMotion) {
                    val j1 = launch { indicatorScaleAnim.animateTo(0f, DismissSpring) }
                    val j2 = launch { indicatorAlphaAnim.animateTo(0f, tween(150)) }
                    val j3 = launch { contentOffsetAnim.animateTo(0f, DismissSpring) }
                    joinAll(j1, j2, j3)
                } else {
                    indicatorScaleAnim.snapTo(0f)
                    indicatorAlphaAnim.snapTo(0f)
                    contentOffsetAnim.snapTo(0f)
                }
                
                state.status = RefreshStatus.IDLE
                state.dragProgress = 0f
                onRefreshResultConsumed()
            }
        }
    }

    // ── Nested scroll connection — the heart of the gesture ──
    val nestedScrollConnection = remember(enabled) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // If we are pulling, allow releasing drag back up
                if (!enabled) return Offset.Zero
                if (state.status == RefreshStatus.REFRESHING || state.isCompleting()) return Offset.Zero

                if (state.status == RefreshStatus.PULLING || state.status == RefreshStatus.ARMED) {
                    if (available.y < 0) {
                        // User is scrolling up while pulling — retract
                        val newRaw = (state.rawDragOffset + available.y).coerceAtLeast(0f)
                        val consumed = newRaw - state.rawDragOffset
                        updateDragState(state, newRaw, thresholdPx, maxDragPx, view, lastHapticMilestone) { m -> lastHapticMilestone = m }

                        scope.launch {
                            contentOffsetAnim.snapTo(state.contentOffset)
                            indicatorScaleAnim.snapTo(0.4f + 0.6f * state.dragProgress.coerceIn(0f, 1f))
                            indicatorAlphaAnim.snapTo(state.dragProgress.coerceIn(0f, 1f))
                        }

                        return if (newRaw <= 0f) {
                            state.status = RefreshStatus.IDLE
                            lastHapticMilestone = 0
                            Offset(0f, consumed)
                        } else {
                            Offset(0f, consumed)
                        }
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (!enabled) return Offset.Zero
                if (state.status == RefreshStatus.REFRESHING || state.isCompleting()) return Offset.Zero

                // Only start pulling when content is at the top (consumed.y == 0) and dragging down
                if (available.y > 0 && source == NestedScrollSource.Drag) {
                    // Debounce check
                    val now = System.currentTimeMillis()
                    if (state.isIdle() && now - state.lastRefreshTimeMs < PullToRefreshState.DEBOUNCE_MS) {
                        return Offset.Zero
                    }

                    if (state.isIdle()) {
                        state.status = RefreshStatus.PULLING
                        lastHapticMilestone = 0
                    }

                    val newRaw = (state.rawDragOffset + available.y).coerceAtMost(maxDragPx / state.dampingFactor)
                    updateDragState(state, newRaw, thresholdPx, maxDragPx, view, lastHapticMilestone) { m -> lastHapticMilestone = m }

                    scope.launch {
                        contentOffsetAnim.snapTo(state.contentOffset)
                        indicatorScaleAnim.snapTo(0.4f + 0.6f * state.dragProgress.coerceIn(0f, 1f))
                        indicatorAlphaAnim.snapTo(state.dragProgress.coerceIn(0f, 1f))
                    }

                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (!enabled) return androidx.compose.ui.unit.Velocity.Zero
                if (state.status == RefreshStatus.REFRESHING || state.isCompleting()) return androidx.compose.ui.unit.Velocity.Zero

                if (state.status == RefreshStatus.ARMED) {
                    // User released while armed → trigger refresh
                    state.status = RefreshStatus.REFRESHING
                    state.refreshStartTimeMs = System.currentTimeMillis()
                    performHaptic(view, HapticType.CONTEXT_CLICK)

                    scope.launch {
                        contentOffsetAnim.animateTo(contentRestOffsetPx, SettleSpring)
                    }
                    scope.launch {
                        indicatorScaleAnim.animateTo(1f, SettleSpring)
                    }

                    onRefresh()
                    lastHapticMilestone = 0
                    return androidx.compose.ui.unit.Velocity(0f, available.y)
                } else if (state.status == RefreshStatus.PULLING) {
                    // Released before threshold — snap back
                    state.status = RefreshStatus.IDLE
                    state.rawDragOffset = 0f
                    state.dragProgress = 0f
                    lastHapticMilestone = 0

                    scope.launch { contentOffsetAnim.animateTo(0f, DismissSpring) }
                    scope.launch { indicatorScaleAnim.animateTo(0f, DismissSpring) }
                    scope.launch { indicatorAlphaAnim.animateTo(0f, tween(150)) }
                    return androidx.compose.ui.unit.Velocity(0f, available.y)
                }
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }

    // ── Colors ──
    val surfaceContainerHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val tertiaryContainer = MaterialTheme.colorScheme.tertiaryContainer
    val errorContainer = MaterialTheme.colorScheme.errorContainer
    val onErrorContainer = MaterialTheme.colorScheme.onErrorContainer
    val onTertiaryContainer = MaterialTheme.colorScheme.onTertiaryContainer
    val shadow = Color.Black.copy(alpha = 0.12f)

    // Determine container + spinner colors based on status
    val containerColor = when (state.status) {
        RefreshStatus.ARMED -> primaryContainer
        RefreshStatus.COMPLETING_SUCCESS -> tertiaryContainer
        RefreshStatus.COMPLETING_ERROR -> errorContainer
        else -> surfaceContainerHigh
    }
    val spinnerColor = when (state.status) {
        RefreshStatus.COMPLETING_SUCCESS -> onTertiaryContainer
        RefreshStatus.COMPLETING_ERROR -> onErrorContainer
        else -> primary
    }

    val animatedContainerColor by animateFloatAsState(
        targetValue = when (state.status) {
            RefreshStatus.ARMED -> 1f
            RefreshStatus.COMPLETING_SUCCESS -> 2f
            RefreshStatus.COMPLETING_ERROR -> 3f
            else -> 0f
        },
        animationSpec = tween(200),
        label = "containerColorAnim"
    )

    // ── Icon rotation proportional to drag ──
    val iconRotation = state.dragProgress * 360f

    // ── Removing custom morphingShape since user requested official LoadingIndicator ──

    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
    ) {
        // Content with vertical offset
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = contentOffsetAnim.value
                }
        ) {
            content()
        }

        // ── Indicator ──
        if (state.status != RefreshStatus.IDLE || indicatorAlphaAnim.value > 0.01f) {
            val indicatorCenterY = if (state.status == RefreshStatus.REFRESHING || state.isCompleting()) {
                indicatorRestOffsetPx
            } else {
                (state.contentOffset * 0.5f).coerceAtMost(indicatorRestOffsetPx)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset {
                        IntOffset(
                            x = 0,
                            y = indicatorCenterY.roundToInt()
                        )
                    }
                    .size(INDICATOR_SIZE)
                    .scale(indicatorScaleAnim.value)
                    .alpha(indicatorAlphaAnim.value)
                    .semantics {
                        contentDescription = when (state.status) {
                            RefreshStatus.PULLING -> "Pull to refresh. Swipe down to update content"
                            RefreshStatus.ARMED -> "Release to refresh"
                            RefreshStatus.REFRESHING -> "Refreshing"
                            RefreshStatus.COMPLETING_SUCCESS -> "Refreshed"
                            RefreshStatus.COMPLETING_ERROR -> "Refresh failed"
                            else -> ""
                        }
                    }
            ) {
                when {
                    // ── Completing: show checkmark or X ──
                    state.status == RefreshStatus.COMPLETING_SUCCESS -> {
                        SuccessCheckmark(
                            progress = completionIconProgress,
                            color = spinnerColor,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(SPINNER_ICON_SIZE)
                        )
                    }
                    state.status == RefreshStatus.COMPLETING_ERROR -> {
                        ErrorCross(
                            progress = completionIconProgress,
                            color = spinnerColor,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(SPINNER_ICON_SIZE)
                        )
                    }
                    // ── Refreshing: Indeterminate M3 LoadingIndicator ──
                    state.status == RefreshStatus.REFRESHING -> {
                        androidx.compose.material3.LoadingIndicator(
                            color = spinnerColor,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(SPINNER_ICON_SIZE)
                        )
                    }
                    // ── Pulling / Armed: rotating refresh arrow arc ──
                    else -> {
                        androidx.compose.material3.ContainedLoadingIndicator(
                            progress = { state.dragProgress.coerceIn(0f, 1f) },
                            indicatorColor = spinnerColor,
                            containerColor = Color.Transparent,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(SPINNER_ICON_SIZE)
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  Internal helpers
// ══════════════════════════════════════════════════════════════════

/**
 * Update drag offsets and status transitions during a pull gesture.
 */
private fun updateDragState(
    state: PullToRefreshState,
    rawOffset: Float,
    thresholdPx: Float,
    maxDragPx: Float,
    view: View,
    lastMilestone: Int,
    setMilestone: (Int) -> Unit
) {
    state.rawDragOffset = rawOffset
    val effectiveDrag = rawOffset * state.dampingFactor
    state.dragProgress = effectiveDrag / thresholdPx
    state.contentOffset = min(effectiveDrag, maxDragPx)
    state.indicatorOffset = min(effectiveDrag * 0.5f, maxDragPx * 0.5f)

    // Haptic milestones at 25%, 50%, 75%
    val milestone = when {
        state.dragProgress >= 0.75f -> 3
        state.dragProgress >= 0.50f -> 2
        state.dragProgress >= 0.25f -> 1
        else -> 0
    }
    if (milestone > lastMilestone) {
        setMilestone(milestone)
        performHaptic(view, HapticType.CLOCK_TICK)
    } else if (milestone < lastMilestone) {
        setMilestone(milestone)
    }

    // Threshold transitions
    val wasArmed = state.status == RefreshStatus.ARMED
    if (state.dragProgress >= 1f && !wasArmed) {
        state.status = RefreshStatus.ARMED
        performHaptic(view, HapticType.CONFIRM)
    } else if (state.dragProgress < 1f && wasArmed) {
        state.status = RefreshStatus.PULLING
        performHaptic(view, HapticType.CLOCK_TICK)
    }
}

// ─── Haptic helpers ─────────────────────────────────────────────

private enum class HapticType { CLOCK_TICK, CONFIRM, CONTEXT_CLICK, SUCCESS, ERROR }

private fun performHaptic(view: View, type: HapticType) {
    val constant = when (type) {
        HapticType.CLOCK_TICK -> HapticFeedbackConstants.CLOCK_TICK
        HapticType.CONFIRM -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        HapticType.CONTEXT_CLICK -> HapticFeedbackConstants.CONTEXT_CLICK
        HapticType.SUCCESS -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        HapticType.ERROR -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    }
    view.performHapticFeedback(constant)
}

// ══════════════════════════════════════════════════════════════════
//  Drawing composables for the indicator content
// ══════════════════════════════════════════════════════════════════

/**
 * Arc that rotates proportionally to drag progress — representing the
 * pull-down arrow indicator before refresh starts.
 */
@Composable
private fun RefreshArrowArc(
    rotation: Float,
    progress: Float,
    color: Color,
    strokeWidth: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val sweepAngle = 270f * progress
        val padding = strokeWidth
        rotate(rotation) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(padding, padding),
                size = Size(size.width - padding * 2, size.height - padding * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            // Small arrowhead at the end of the arc
            if (progress > 0.1f) {
                val arrowSize = strokeWidth * 2.5f
                val endAngle = Math.toRadians((-90.0 + sweepAngle))
                val radius = (size.width - padding * 2) / 2f
                val cx = size.width / 2f
                val cy = size.height / 2f
                val ex = cx + radius * kotlin.math.cos(endAngle).toFloat()
                val ey = cy + radius * kotlin.math.sin(endAngle).toFloat()

                // Two small lines forming an arrow tip
                val angle1 = endAngle + Math.toRadians(150.0)
                val angle2 = endAngle + Math.toRadians(210.0)
                drawLine(
                    color = color,
                    start = Offset(ex, ey),
                    end = Offset(
                        ex + arrowSize * kotlin.math.cos(angle1).toFloat(),
                        ey + arrowSize * kotlin.math.sin(angle1).toFloat()
                    ),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(ex, ey),
                    end = Offset(
                        ex + arrowSize * kotlin.math.cos(angle2).toFloat(),
                        ey + arrowSize * kotlin.math.sin(angle2).toFloat()
                    ),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/**
 * Indeterminate circular spinner — variable speed Google Photos style.
 */
@Composable
private fun IndeterminateSpinner(
    color: Color,
    strokeWidth: Float,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "spinnerTransition")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Canvas(modifier = modifier) {
        val padding = strokeWidth
        // Two arcs that chase each other
        val sweep1 = 90f + 50f * kotlin.math.sin(Math.toRadians(rotation * 2.0)).toFloat()
        val start1 = rotation
        val start2 = rotation + 180f
        val sweep2 = 90f + 50f * kotlin.math.sin(Math.toRadians(rotation * 2.0 + 180.0)).toFloat()

        drawArc(
            color = color,
            startAngle = start1,
            sweepAngle = sweep1,
            useCenter = false,
            topLeft = Offset(padding, padding),
            size = Size(size.width - padding * 2, size.height - padding * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        drawArc(
            color = color.copy(alpha = 0.4f),
            startAngle = start2,
            sweepAngle = sweep2,
            useCenter = false,
            topLeft = Offset(padding, padding),
            size = Size(size.width - padding * 2, size.height - padding * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}

/**
 * Animated checkmark — drawn on from 0→1 [progress].
 */
@Composable
private fun SuccessCheckmark(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val sw = 3.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val s = size.minDimension * 0.35f

        // Checkmark: two lines — short left leg, longer right leg
        val p1 = Offset(cx - s * 0.5f, cy + s * 0.05f)
        val p2 = Offset(cx - s * 0.1f, cy + s * 0.45f)
        val p3 = Offset(cx + s * 0.55f, cy - s * 0.4f)

        val leg1End = if (progress < 0.4f) {
            val t = progress / 0.4f
            Offset(p1.x + (p2.x - p1.x) * t, p1.y + (p2.y - p1.y) * t)
        } else p2

        if (progress > 0f) {
            drawLine(color, p1, leg1End, sw, StrokeCap.Round)
        }
        if (progress > 0.4f) {
            val t = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f)
            val leg2End = Offset(p2.x + (p3.x - p2.x) * t, p2.y + (p3.y - p2.y) * t)
            drawLine(color, p2, leg2End, sw, StrokeCap.Round)
        }
    }
}

/**
 * Animated X cross — drawn on from 0→1 [progress].
 */
@Composable
private fun ErrorCross(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val sw = 3.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val s = size.minDimension * 0.3f

        // First diagonal
        if (progress > 0f) {
            val t1 = (progress / 0.5f).coerceIn(0f, 1f)
            drawLine(
                color,
                Offset(cx - s, cy - s),
                Offset(cx - s + 2 * s * t1, cy - s + 2 * s * t1),
                sw,
                StrokeCap.Round
            )
        }
        // Second diagonal
        if (progress > 0.5f) {
            val t2 = ((progress - 0.5f) / 0.5f).coerceIn(0f, 1f)
            drawLine(
                color,
                Offset(cx + s, cy - s),
                Offset(cx + s - 2 * s * t2, cy - s + 2 * s * t2),
                sw,
                StrokeCap.Round
            )
        }
    }
}
