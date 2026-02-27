package com.sonicmusic.app.player.timer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Sleep Timer Manager
 * 
 * Manages sleep timer functionality for music playback.
 * Features:
 * - Preset durations (15m, 30m, 45m, 1h, 2h)
 * - Custom duration support
 * - Remaining time updates every second
 * - Fade out before stopping
 * - Cancel/restart capability
 */
@Singleton
class SleepTimerManager @Inject constructor() {

    companion object {
        // Preset durations
        val PRESET_15_MIN = 15.minutes
        val PRESET_30_MIN = 30.minutes
        val PRESET_45_MIN = 45.minutes
        val PRESET_1_HOUR = 60.minutes
        val PRESET_2_HOUR = 120.minutes

        val PRESETS = listOf(
            PRESET_15_MIN,
            PRESET_30_MIN,
            PRESET_45_MIN,
            PRESET_1_HOUR,
            PRESET_2_HOUR
        )

        // Fade out duration before stopping
        private val FADE_OUT_DURATION = 10.seconds
    }

    // Timer state
    private val _remainingTime = MutableStateFlow<Duration?>(null)
    val remainingTime: StateFlow<Duration?> = _remainingTime.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _isFadingOut = MutableStateFlow(false)
    val isFadingOut: StateFlow<Boolean> = _isFadingOut.asStateFlow()

    // Internal state
    private var timerJob: Job? = null
    private var onTimerComplete: (() -> Unit)? = null
    private var onFadeOut: ((Float) -> Unit)? = null

    /**
     * Start the sleep timer with the specified duration.
     *
     * @param duration Timer duration
     * @param scope Coroutine scope for the timer job
     * @param onComplete Callback when timer completes
     * @param onFade Callback during fade out with volume multiplier (1.0 -> 0.0)
     */
    fun startTimer(
        duration: Duration,
        scope: CoroutineScope,
        onComplete: () -> Unit,
        onFade: ((Float) -> Unit)? = null
    ) {
        // Cancel any existing timer
        cancelTimer()

        onTimerComplete = onComplete
        onFadeOut = onFade
        _isActive.value = true
        _remainingTime.value = duration
        _isFadingOut.value = false

        timerJob = scope.launch {
            var remaining = duration

            while (remaining > Duration.ZERO) {
                delay(1.seconds)
                remaining -= 1.seconds
                _remainingTime.value = remaining

                // Start fade out when approaching end
                if (remaining <= FADE_OUT_DURATION && !_isFadingOut.value) {
                    _isFadingOut.value = true
                }

                // Calculate fade volume during fade out period
                if (_isFadingOut.value && onFade != null) {
                    val fadeProgress = remaining / FADE_OUT_DURATION
                    onFade.invoke(fadeProgress.toFloat().coerceIn(0f, 1f))
                }
            }

            // Timer complete
            _remainingTime.value = Duration.ZERO
            _isActive.value = false
            _isFadingOut.value = false
            onTimerComplete?.invoke()
        }
    }

    /**
     * Cancel the active sleep timer.
     */
    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        _remainingTime.value = null
        _isActive.value = false
        _isFadingOut.value = false
        onTimerComplete = null
        onFadeOut = null
    }

    /**
     * Extend the current timer by the specified duration.
     *
     * IMPORTANT: Callbacks must be captured BEFORE calling [startTimer], because
     * [startTimer] invokes [cancelTimer] internally which nullifies
     * [onTimerComplete] and [onFadeOut].
     */
    fun extendTimer(extraDuration: Duration, scope: CoroutineScope) {
        val current = _remainingTime.value ?: return
        val newDuration = current + extraDuration

        // Capture before startTimer → cancelTimer nullifies these fields
        val savedOnComplete = onTimerComplete ?: return
        val savedOnFade = onFadeOut

        startTimer(newDuration, scope, savedOnComplete, savedOnFade)
    }

    /**
     * Add time to the active timer (convenience for +5 min buttons).
     */
    fun addFiveMinutes(scope: CoroutineScope) {
        extendTimer(5.minutes, scope)
    }

    /**
     * Format remaining time as a human-readable string.
     */
    fun formatRemainingTime(): String {
        val remaining = _remainingTime.value ?: return ""
        val totalSeconds = remaining.inWholeSeconds
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
            else -> String.format("%d:%02d", minutes, seconds)
        }
    }
}
