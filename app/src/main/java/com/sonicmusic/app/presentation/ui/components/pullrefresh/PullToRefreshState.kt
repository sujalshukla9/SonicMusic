package com.sonicmusic.app.presentation.ui.components.pullrefresh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * State machine status for the M3 Expressive Pull-to-Refresh.
 *
 * ```
 * IDLE → PULLING → ARMED → REFRESHING → COMPLETING_SUCCESS / COMPLETING_ERROR → IDLE
 * ```
 */
enum class RefreshStatus {
    /** Content at rest, no interaction. */
    IDLE,

    /** User is dragging downward but hasn't crossed the activation threshold. */
    PULLING,

    /** Drag has crossed the activation threshold; releasing will trigger a refresh. */
    ARMED,

    /** Refresh is in progress. */
    REFRESHING,

    /** Refresh completed successfully — showing checkmark before dismissal. */
    COMPLETING_SUCCESS,

    /** Refresh failed — showing error icon before dismissal. */
    COMPLETING_ERROR
}

/** Outcome of a refresh operation. */
enum class RefreshResult {
    SUCCESS,
    ERROR,
    TIMEOUT
}

/**
 * Encapsulates the mutable state that drives [M3ExpressivePullToRefresh].
 *
 * @param threshold        Effective drag distance that arms the refresh (default 80 dp).
 * @param maxDragDistance   Maximum visual drag displacement (default 160 dp).
 * @param dampingFactor    Resistance factor applied to raw drag (0–1; default 0.5).
 */
@Stable
class PullToRefreshState(
    val threshold: Dp = 80.dp,
    val maxDragDistance: Dp = 160.dp,
    val dampingFactor: Float = 0.5f
) {
    /** Current lifecycle status. */
    var status: RefreshStatus by mutableStateOf(RefreshStatus.IDLE)
        internal set

    /** Normalised progress 0 → 1+ while pulling (0 = idle, 1 = threshold). */
    var dragProgress: Float by mutableFloatStateOf(0f)
        internal set

    /** Current raw drag offset in pixels. */
    var rawDragOffset: Float by mutableFloatStateOf(0f)
        internal set

    /** Visual offset applied to the indicator (px). */
    var indicatorOffset: Float by mutableFloatStateOf(0f)
        internal set

    /** Visual offset applied to the content (px). */
    var contentOffset: Float by mutableFloatStateOf(0f)
        internal set

    /** Timestamp of the last completed refresh (for debouncing). */
    internal var lastRefreshTimeMs: Long = 0L

    /** Timestamp when the refreshing state started (for minimum display & timeout). */
    internal var refreshStartTimeMs: Long = 0L

    // ── Public helpers ──

    fun isIdle() = status == RefreshStatus.IDLE
    fun isPulling() = status == RefreshStatus.PULLING
    fun isArmed() = status == RefreshStatus.ARMED
    fun isRefreshing() = status == RefreshStatus.REFRESHING
    fun isCompleting() = status == RefreshStatus.COMPLETING_SUCCESS || status == RefreshStatus.COMPLETING_ERROR

    companion object {
        /** Minimum time the refreshing indicator stays visible. */
        const val MIN_REFRESH_DISPLAY_MS = 1_000L

        /** Auto-dismiss timeout. */
        const val MAX_REFRESH_TIMEOUT_MS = 15_000L

        /** Minimum gap between two consecutive refreshes. */
        const val DEBOUNCE_MS = 2_000L

        /** Duration the success/error state is shown before dismiss. */
        const val SUCCESS_DISPLAY_MS = 600L
        const val ERROR_DISPLAY_MS = 800L
    }
}

/**
 * Remember a [PullToRefreshState] scoped to the composition.
 */
@Composable
fun rememberPullToRefreshState(
    threshold: Dp = 80.dp,
    maxDragDistance: Dp = 160.dp,
    dampingFactor: Float = 0.5f
): PullToRefreshState = remember {
    PullToRefreshState(threshold, maxDragDistance, dampingFactor)
}
