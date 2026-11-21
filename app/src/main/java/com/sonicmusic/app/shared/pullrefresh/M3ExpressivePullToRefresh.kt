@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.sonicmusic.app.shared.components.pullrefresh

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.sonicmusic.app.theme.shouldReduceMotion
import com.sonicmusic.app.theme.MotionTokens
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlin.math.min
import kotlin.math.roundToInt

// ─── Indicator sizing / offsets (dp → converted to px inside composable) ──
private val INDICATOR_SIZE = 44.dp
private val INDICATOR_RESTING_OFFSET = 36.dp   // centre-Y while refreshing
private val CONTENT_RESTING_OFFSET = 72.dp     // content pushed down while refreshing
private val SPINNER_STROKE = 2.5.dp
private val SPINNER_ICON_SIZE = 24.dp

/**
 * CompositionLocal providing the current pull-down offset (in pixels) of the sheet.
 * Consuming components (such as headers) can use this to adaptively collapse redundant top insets.
 */
val LocalPullToRefreshOffset = compositionLocalOf<State<Float>> { mutableFloatStateOf(0f) }

/**
 * Material Design 3 Expressive pull-to-refresh wrapper inspired by Google Photos.
 *
 * Emulates the Google Photos sheet-reveal interaction:
 * - Content acts as a foreground sheet with dynamic rounded top corners and surface elevation.
 * - The refresh indicator and backdrop trench sit BEHIND the sheet, naturally uncovered as the sheet moves down.
 * - Physics-based drag resistance with zero-drag upward retraction.
 * - Non-blocking upward scroll from refreshing/pulled-down state.
 * - Adaptive thresholds and offsets calibrated to diverse screen sizes and aspect ratios.
 *
 * @param isRefreshing   driven by the ViewModel — `true` while the network call is in flight.
 * @param onRefresh      called once when the user releases in the ARMED state.
 * @param refreshResult  set by the ViewModel after the refresh completes (`SUCCESS` / `ERROR`).
 * @param onRefreshResultConsumed called after the completing animation finishes.
 * @param enabled        set to `false` to disable the gesture entirely.
 * @param indicatorPadding insets (e.g. status bars) to ensure indicator clears cutouts.
 * @param sheetBackgroundColor background color of the foreground content sheet.
 * @param sheetElevation maximum shadow elevation applied to the sheet when pulled down.
 * @param sheetMaxCornerRadius maximum top corner radius applied when sheet is pulled down.
 * @param backdropColor color of the reveal trench behind the sheet.
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
    sheetBackgroundColor: Color = MaterialTheme.colorScheme.background,
    sheetElevation: Dp = 8.dp,
    sheetMaxCornerRadius: Dp = 28.dp,
    backdropColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val requestRefresh by rememberUpdatedState(onRefresh)
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp.dp

    // ── Screen-adaptive measurements ──
    val adaptiveThresholdDp = (screenHeightDp * 0.11f).coerceIn(72.dp, 96.dp)
    val adaptiveMaxDragDp = (screenHeightDp * 0.22f).coerceIn(140.dp, 200.dp)
    val adaptiveContentRestDp = (screenHeightDp * 0.09f).coerceIn(64.dp, 84.dp)

    val state = remember(adaptiveThresholdDp, adaptiveMaxDragDp) {
        PullToRefreshState(
            threshold = adaptiveThresholdDp,
            maxDragDistance = adaptiveMaxDragDp,
            dampingFactor = 0.5f
        )
    }

    // Px conversions
    val topPaddingPx = with(density) { indicatorPadding.calculateTopPadding().toPx() }
    val thresholdPx = with(density) { adaptiveThresholdDp.toPx() }
    val maxDragPx = with(density) { adaptiveMaxDragDp.toPx() }
    val contentRestOffsetPx = with(density) { adaptiveContentRestDp.toPx() } + topPaddingPx
    val cornerMaxOffsetPx = with(density) { 56.dp.toPx() }

    // ── Animatable values ──
    val contentOffsetAnim = remember { Animatable(0f) }
    val indicatorScaleAnim = remember { Animatable(0f) }
    val indicatorAlphaAnim = remember { Animatable(0f) }

    // Track active return/dismiss animation so offset transitions smoothly to 0 without 1-frame glitches
    var isAnimatingBack by remember { mutableStateOf(false) }

    // ── Track haptic milestones fired during current gesture ──
    var lastHapticMilestone by remember { mutableIntStateOf(0) }

    // ── Completion icon progress (0→1 for draw-on anim) ──
    val completionIconAnim = remember { Animatable(0f) }

    val reducedMotion = shouldReduceMotion()

    // ── React to external isRefreshing changes ──
    LaunchedEffect(isRefreshing) {
        if (isRefreshing && state.status != RefreshStatus.REFRESHING) {
            state.status = RefreshStatus.REFRESHING
            state.refreshStartTimeMs = System.currentTimeMillis()
            if (!reducedMotion) {
                launch { contentOffsetAnim.animateTo(contentRestOffsetPx, MotionTokens.PanelSpring) }
                launch { indicatorScaleAnim.animateTo(1f, MotionTokens.PanelSpring) }
                launch { indicatorAlphaAnim.animateTo(1f, MotionTokens.effects()) }
            } else {
                contentOffsetAnim.snapTo(contentRestOffsetPx)
                indicatorScaleAnim.snapTo(1f)
                indicatorAlphaAnim.snapTo(1f)
            }
        } else if (!isRefreshing && state.status == RefreshStatus.REFRESHING && refreshResult == null) {
            val elapsed = System.currentTimeMillis() - state.refreshStartTimeMs
            val remaining = PullToRefreshState.MIN_REFRESH_DISPLAY_MS - elapsed
            if (remaining > 0) delay(remaining)

            state.lastRefreshTimeMs = System.currentTimeMillis()
            if (!reducedMotion) {
                val j1 = launch { indicatorScaleAnim.animateTo(0f, MotionTokens.PanelSpring) }
                val j2 = launch { indicatorAlphaAnim.animateTo(0f, MotionTokens.quickEffects()) }
                val j3 = launch { contentOffsetAnim.animateTo(0f, MotionTokens.PanelSpring) }
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
            completionIconAnim.snapTo(0f)
        }
    }

    // ── React to refreshResult (success / error) ──
    LaunchedEffect(refreshResult) {
        if (refreshResult == null || (state.status != RefreshStatus.REFRESHING && !state.isCompleting())) return@LaunchedEffect

        val elapsed = System.currentTimeMillis() - state.refreshStartTimeMs
        val remaining = PullToRefreshState.MIN_REFRESH_DISPLAY_MS - elapsed
        if (remaining > 0) delay(remaining)

        val success = refreshResult == RefreshResult.SUCCESS
        completionIconAnim.snapTo(if (reducedMotion) 1f else 0f)
        state.status = if (success) RefreshStatus.COMPLETING_SUCCESS else RefreshStatus.COMPLETING_ERROR
        performHaptic(view, if (success) HapticType.SUCCESS else HapticType.ERROR)
        if (!reducedMotion) {
            launch { completionIconAnim.animateTo(1f, MotionTokens.effects()) }
        }
        delay(if (success) PullToRefreshState.SUCCESS_DISPLAY_MS else PullToRefreshState.ERROR_DISPLAY_MS)

        // Dismiss
        state.lastRefreshTimeMs = System.currentTimeMillis()
        if (!reducedMotion) {
            val j1 = launch { indicatorScaleAnim.animateTo(0f, MotionTokens.PanelSpring) }
            val j2 = launch { indicatorAlphaAnim.animateTo(0f, MotionTokens.quickEffects()) }
            val j3 = launch { contentOffsetAnim.animateTo(0f, MotionTokens.PanelSpring) }
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
        completionIconAnim.snapTo(0f)
        onRefreshResultConsumed()
    }

    // ── Refreshing timeout watchdog ──
    LaunchedEffect(state.status) {
        if (state.status == RefreshStatus.REFRESHING) {
            delay(PullToRefreshState.MAX_REFRESH_TIMEOUT_MS)
            if (state.status == RefreshStatus.REFRESHING) {
                state.status = RefreshStatus.COMPLETING_ERROR
                performHaptic(view, HapticType.ERROR)
                delay(PullToRefreshState.ERROR_DISPLAY_MS)
                state.lastRefreshTimeMs = System.currentTimeMillis()

                if (!reducedMotion) {
                    val j1 = launch { indicatorScaleAnim.animateTo(0f, MotionTokens.PanelSpring) }
                    val j2 = launch { indicatorAlphaAnim.animateTo(0f, MotionTokens.quickEffects()) }
                    val j3 = launch { contentOffsetAnim.animateTo(0f, MotionTokens.PanelSpring) }
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
                onRefreshResultConsumed()
            }
        }
    }

    // ── Nested scroll connection ──
    val nestedScrollConnection = remember(state, view, enabled, thresholdPx, maxDragPx, contentRestOffsetPx, reducedMotion) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!enabled) return Offset.Zero

                // Smooth upward scroll while REFRESHING: collapse the resting gap naturally
                if (state.status == RefreshStatus.REFRESHING) {
                    if (available.y < 0 && contentOffsetAnim.value > 0f) {
                        val current = contentOffsetAnim.value
                        val newOffset = (current + available.y).coerceAtLeast(0f)
                        val consumedY = newOffset - current
                        scope.launch { contentOffsetAnim.snapTo(newOffset) }
                        return Offset(0f, consumedY)
                    }
                    return Offset.Zero
                }

                if (state.isCompleting()) return Offset.Zero

                // User scrolling up while pulling — retract fluidly without lag
                if (state.status == RefreshStatus.PULLING || state.status == RefreshStatus.ARMED) {
                    if (available.y < 0) {
                        val newRaw = (state.rawDragOffset + available.y).coerceAtLeast(0f)
                        val consumedY = newRaw - state.rawDragOffset
                        updateDragState(state, newRaw, thresholdPx, maxDragPx, view, lastHapticMilestone) { m -> lastHapticMilestone = m }

                        if (newRaw <= 0f) {
                            state.status = RefreshStatus.IDLE
                            lastHapticMilestone = 0
                            state.contentOffset = 0f
                            state.dragProgress = 0f
                            state.rawDragOffset = 0f
                            state.indicatorOffset = 0f
                            isAnimatingBack = false
                            scope.launch {
                                contentOffsetAnim.snapTo(0f)
                                indicatorScaleAnim.snapTo(0f)
                                indicatorAlphaAnim.snapTo(0f)
                            }
                        }
                        return Offset(0f, consumedY)
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

                // Pulling down while REFRESHING: expand resting gap back up to resting offset
                if (state.status == RefreshStatus.REFRESHING) {
                    if (available.y > 0 && contentOffsetAnim.value < contentRestOffsetPx && source == NestedScrollSource.UserInput) {
                        val current = contentOffsetAnim.value
                        val newOffset = (current + available.y).coerceAtMost(contentRestOffsetPx)
                        val consumedY = newOffset - current
                        scope.launch { contentOffsetAnim.snapTo(newOffset) }
                        return Offset(0f, consumedY)
                    }
                    return Offset.Zero
                }

                if (state.isCompleting()) return Offset.Zero

                // Start pulling only when:
                // 1. Content is already at the top (consumed.y == 0) and dragging down
                // 2. Not spilling over from an active scroll-up stroke
                if (available.y > 0 && source == NestedScrollSource.UserInput && consumed.y == 0f) {
                    if (state.isIdle()) {
                        state.status = RefreshStatus.PULLING
                        lastHapticMilestone = 0
                    }

                    val maxRaw = maxDragPx / 0.35f
                    val newRaw = (state.rawDragOffset + available.y).coerceAtMost(maxRaw)
                    updateDragState(state, newRaw, thresholdPx, maxDragPx, view, lastHapticMilestone) { m -> lastHapticMilestone = m }

                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (!enabled) return androidx.compose.ui.unit.Velocity.Zero

                if (state.status == RefreshStatus.REFRESHING) {
                    if (available.y < 0 && contentOffsetAnim.value > 0f) {
                        scope.launch { contentOffsetAnim.animateTo(0f, MotionTokens.PanelSpring) }
                        return androidx.compose.ui.unit.Velocity.Zero
                    }
                    return androidx.compose.ui.unit.Velocity.Zero
                }

                if (state.isCompleting()) return androidx.compose.ui.unit.Velocity.Zero

                if (state.status == RefreshStatus.ARMED) {
                    val startOffset = state.contentOffset
                    state.status = RefreshStatus.REFRESHING
                    state.refreshStartTimeMs = System.currentTimeMillis()
                    performHaptic(view, HapticType.CONTEXT_CLICK)

                    if (!reducedMotion) {
                        scope.launch {
                            contentOffsetAnim.snapTo(startOffset)
                            contentOffsetAnim.animateTo(contentRestOffsetPx, MotionTokens.PanelSpring)
                        }
                        scope.launch {
                            indicatorScaleAnim.snapTo(1f)
                            indicatorScaleAnim.animateTo(1f, MotionTokens.PanelSpring)
                        }
                        scope.launch {
                            indicatorAlphaAnim.snapTo(1f)
                            indicatorAlphaAnim.animateTo(1f, MotionTokens.quickEffects())
                        }
                    } else {
                        contentOffsetAnim.snapTo(contentRestOffsetPx)
                        indicatorScaleAnim.snapTo(1f)
                        indicatorAlphaAnim.snapTo(1f)
                    }

                    requestRefresh()
                    lastHapticMilestone = 0
                    return androidx.compose.ui.unit.Velocity(0f, available.y)
                } else if (state.status == RefreshStatus.PULLING) {
                    val startOffset = state.contentOffset
                    val startScale = (0.4f + 0.6f * state.dragProgress).coerceIn(0f, 1f)
                    val startAlpha = state.dragProgress.coerceIn(0f, 1f)

                    isAnimatingBack = true
                    lastHapticMilestone = 0

                    if (!reducedMotion) {
                        scope.launch {
                            contentOffsetAnim.snapTo(startOffset)
                            indicatorScaleAnim.snapTo(startScale)
                            indicatorAlphaAnim.snapTo(startAlpha)
                            val j1 = launch { contentOffsetAnim.animateTo(0f, MotionTokens.PanelSpring) }
                            val j2 = launch { indicatorScaleAnim.animateTo(0f, MotionTokens.PanelSpring) }
                            val j3 = launch { indicatorAlphaAnim.animateTo(0f, MotionTokens.quickEffects()) }
                            joinAll(j1, j2, j3)

                            state.status = RefreshStatus.IDLE
                            state.rawDragOffset = 0f
                            state.dragProgress = 0f
                            state.contentOffset = 0f
                            state.indicatorOffset = 0f
                            isAnimatingBack = false
                        }
                    } else {
                        contentOffsetAnim.snapTo(0f)
                        indicatorScaleAnim.snapTo(0f)
                        indicatorAlphaAnim.snapTo(0f)
                        state.status = RefreshStatus.IDLE
                        state.rawDragOffset = 0f
                        state.dragProgress = 0f
                        state.contentOffset = 0f
                        state.indicatorOffset = 0f
                        isAnimatingBack = false
                    }
                    return androidx.compose.ui.unit.Velocity(0f, available.y)
                }
                return androidx.compose.ui.unit.Velocity.Zero
            }

            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity,
                available: androidx.compose.ui.unit.Velocity
            ): androidx.compose.ui.unit.Velocity {
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }

    // ── Colors ──
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val error = MaterialTheme.colorScheme.error

    val spinnerColor = when (state.status) {
        RefreshStatus.COMPLETING_SUCCESS -> tertiary
        RefreshStatus.COMPLETING_ERROR -> error
        else -> primary
    }

    val currentContentOffset = remember(state) {
        derivedStateOf {
            val offset = if (!isAnimatingBack && (state.isPulling() || state.isArmed())) {
                state.contentOffset
            } else {
                contentOffsetAnim.value
            }
            offset.coerceAtLeast(0f)
        }
    }
    val trenchOpenThresholdPx = with(density) { 10.dp.toPx() }
    val fullRevealDistancePx = contentRestOffsetPx - topPaddingPx - trenchOpenThresholdPx

    fun availableTrench() = (currentContentOffset.value - topPaddingPx).coerceAtLeast(0f)

    fun trenchProgress(): Float {
        if (fullRevealDistancePx <= 0f) return 0f
        return ((availableTrench() - trenchOpenThresholdPx) / fullRevealDistancePx).coerceIn(0f, 1f)
    }

    fun indicatorScale(): Float = (when {
        isAnimatingBack || state.status == RefreshStatus.REFRESHING || state.isCompleting() -> indicatorScaleAnim.value
        state.isPulling() || state.isArmed() -> 0.35f + 0.65f * trenchProgress()
        else -> 0f
    }).coerceIn(0f, 1f)

    fun indicatorAlpha(): Float = (when {
        isAnimatingBack || state.status == RefreshStatus.REFRESHING || state.isCompleting() -> indicatorAlphaAnim.value
        state.isPulling() || state.isArmed() -> trenchProgress()
        else -> 0f
    }).coerceIn(0f, 1f)

    val showTrench by remember(state) {
        derivedStateOf { currentContentOffset.value > 0.5f || state.status == RefreshStatus.REFRESHING || isAnimatingBack }
    }
    val showIndicator by remember(state, topPaddingPx, trenchOpenThresholdPx, fullRevealDistancePx) {
        derivedStateOf { indicatorAlpha() > 0.01f }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
    ) {
        // ══════════════════════════════════════════════════════════════════
        // LAYER 0: Backdrop Reveal Trench (BEHIND the sheet)
        // Rendered only when sheet is pulled down or actively refreshing
        // ══════════════════════════════════════════════════════════════════
        if (showTrench) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val trenchHeight = (currentContentOffset.value + topPaddingPx).coerceAtMost(size.height)
                        drawRect(backdropColor, size = Size(size.width, trenchHeight))
                    }
            ) {
                if (showIndicator) {
                    val indicatorSizePx = with(density) { INDICATOR_SIZE.toPx() }

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset {
                                val trenchHeight = if (state.status == RefreshStatus.REFRESHING || state.isCompleting()) {
                                    contentRestOffsetPx - topPaddingPx
                                } else {
                                    availableTrench()
                                }
                                val top = topPaddingPx + (trenchHeight - indicatorSizePx) / 2f
                                IntOffset(x = 0, y = top.roundToInt())
                            }
                            .size(INDICATOR_SIZE)
                            .graphicsLayer {
                                scaleX = indicatorScale()
                                scaleY = scaleX
                                alpha = indicatorAlpha()
                            }
                            .semantics {
                                contentDescription = when (state.status) {
                                    RefreshStatus.PULLING -> "Pull to refresh. Swipe down to update content"
                                    RefreshStatus.ARMED -> "Release to refresh"
                                    RefreshStatus.REFRESHING -> "Refreshing"
                                    RefreshStatus.COMPLETING_SUCCESS -> "Refreshed"
                                    RefreshStatus.COMPLETING_ERROR -> "Refresh failed"
                                    else -> ""
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            state.status == RefreshStatus.COMPLETING_SUCCESS -> {
                                SuccessCheckmark(
                                    animationProgress = completionIconAnim.asState(),
                                    color = spinnerColor,
                                    modifier = Modifier.size(SPINNER_ICON_SIZE)
                                )
                            }
                            state.status == RefreshStatus.COMPLETING_ERROR -> {
                                ErrorCross(
                                    animationProgress = completionIconAnim.asState(),
                                    color = spinnerColor,
                                    modifier = Modifier.size(SPINNER_ICON_SIZE)
                                )
                            }
                            else -> {
                                LoadingIndicator(
                                    modifier = Modifier.size(INDICATOR_SIZE),
                                    color = spinnerColor
                                )
                            }
                        }
                    }
                }
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // LAYER 1: Foreground Sheet (The Home Page / Content)
        // ══════════════════════════════════════════════════════════════════
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val offset = currentContentOffset.value
                    translationY = offset
                    if (offset > 0.5f) {
                        val cornerProgress = (offset / cornerMaxOffsetPx).coerceIn(0f, 1f)
                        val radius = sheetMaxCornerRadius * cornerProgress
                        shape = RoundedCornerShape(topStart = radius, topEnd = radius)
                        clip = true
                        shadowElevation = (sheetElevation * cornerProgress).toPx()
                    } else {
                        shape = RectangleShape
                        clip = false
                        shadowElevation = 0f
                    }
                }
                .background(sheetBackgroundColor)
        ) {
            CompositionLocalProvider(LocalPullToRefreshOffset provides currentContentOffset) {
                content()
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
    // Physics resistance: initial pull is responsive, tightens as user approaches max
    val effectiveDrag = if (rawOffset <= thresholdPx) {
        rawOffset * 0.65f
    } else {
        val extra = rawOffset - thresholdPx
        thresholdPx * 0.65f + extra * 0.35f
    }
    state.dragProgress = effectiveDrag / (thresholdPx * 0.65f)
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
    val rotation = if (com.sonicmusic.app.theme.shouldReduceMotion()) {
        null
    } else {
        rememberInfiniteTransition(label = "spinnerTransition").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = MotionTokens.linearTween(MotionTokens.ShimmerPeriod),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        )
    }

    Canvas(modifier = modifier) {
        val angle = rotation?.value ?: 0f
        val padding = strokeWidth
        // Two arcs that chase each other
        val sweep1 = 90f + 50f * kotlin.math.sin(Math.toRadians(angle * 2.0)).toFloat()
        val start1 = angle
        val start2 = angle + 180f
        val sweep2 = 90f + 50f * kotlin.math.sin(Math.toRadians(angle * 2.0 + 180.0)).toFloat()

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
 * Animated checkmark — drawn on from 0→1.
 */
@Composable
private fun SuccessCheckmark(
    animationProgress: State<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val progress = animationProgress.value
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
 * Animated X cross — drawn on from 0→1.
 */
@Composable
private fun ErrorCross(
    animationProgress: State<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val progress = animationProgress.value
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
