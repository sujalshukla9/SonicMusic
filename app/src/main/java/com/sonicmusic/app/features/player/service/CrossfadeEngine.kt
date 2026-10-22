package com.sonicmusic.app.features.player.service

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Supported crossfade volume curve envelopes.
 */
enum class CrossfadeCurve {
    /**
     * Apple Music standard: Constant acoustic power across the entire transition.
     * V_out(t) = cos(pi * t / 2)
     * V_in(t)  = sin(pi * t / 2)
     * Midpoint gain is ~0.7071 for both tracks, preventing the perceived loudness drop of linear fading.
     */
    EQUAL_POWER,

    /**
     * Standard linear volume ramp:
     * V_out(t) = 1 - t
     * V_in(t)  = t
     */
    LINEAR
}

/**
 * Current state of the crossfade lifecycle.
 */
enum class CrossfadeState {
    IDLE,
    PREPARING_NEXT,
    READY_FOR_CROSSFADE,
    CROSSFADING,
    COMPLETED,
    CANCELLED
}

/**
 * Apple Music–Style True Audio Crossfade Engine.
 *
 * Coordinates dual Media3 [ExoPlayer] instances to achieve smooth, overlapping audio transitions:
 * 1. Pre-buffers upcoming track in background before the transition window.
 * 2. Confirms incoming media is [Player.STATE_READY] before commencing fade.
 * 3. Applies mathematically defined equal-power volume envelopes to both players concurrently.
 * 4. Dynamically adapts crossfade duration for short songs to avoid early fading.
 * 5. Handles repeat modes ([Player.REPEAT_MODE_ONE] and [Player.REPEAT_MODE_ALL]).
 * 6. Handles user interruptions (pause, skip, previous, seek) safely without audio clicks or stuck players.
 * 7. Manages seamless [MediaSession] handoff so system notifications and lock-screen controls remain in sync.
 */
class CrossfadeEngine(
    private val scope: CoroutineScope,
    private val player1: ExoPlayer,
    private val player2: ExoPlayer,
    private val mediaSessionProvider: () -> MediaSession?,
    private val onPlayerSwapped: (ExoPlayer) -> Unit
) {

    companion object {
        private const val TAG = "SonicCrossfade"

        /** Look-ahead window in ms before crossfade trigger to start pre-buffering incoming track */
        const val PRELOAD_AHEAD_MS = 4_000L

        /** Interval between volume envelope updates in milliseconds */
        const val FADE_STEP_INTERVAL_MS = 30L

        /**
         * Calculates outgoing track volume gain for a normalized progress t in [0.0, 1.0].
         */
        fun calculateOutgoingGain(progress: Float, curve: CrossfadeCurve = CrossfadeCurve.EQUAL_POWER): Float {
            val t = progress.coerceIn(0f, 1f)
            return when (curve) {
                CrossfadeCurve.EQUAL_POWER -> cos(Math.PI * t / 2.0).toFloat().coerceIn(0f, 1f)
                CrossfadeCurve.LINEAR -> (1f - t).coerceIn(0f, 1f)
            }
        }

        /**
         * Calculates incoming track volume gain for a normalized progress t in [0.0, 1.0].
         */
        fun calculateIncomingGain(progress: Float, curve: CrossfadeCurve = CrossfadeCurve.EQUAL_POWER): Float {
            val t = progress.coerceIn(0f, 1f)
            return when (curve) {
                CrossfadeCurve.EQUAL_POWER -> sin(Math.PI * t / 2.0).toFloat().coerceIn(0f, 1f)
                CrossfadeCurve.LINEAR -> t.coerceIn(0f, 1f)
            }
        }

        /**
         * Computes safe effective crossfade duration for a track.
         * Caps crossfade at 1/3 of the total track duration, and disables if track is too short (< 3s).
         */
        fun calculateEffectiveDurationMs(songDurationMs: Long, configuredDurationMs: Long): Long {
            if (configuredDurationMs <= 0L || songDurationMs <= 0L || songDurationMs == C.TIME_UNSET) {
                return 0L
            }
            val maxAllowedMs = songDurationMs / 3L
            if (maxAllowedMs < 1_000L) {
                return 0L
            }
            return minOf(configuredDurationMs, maxAllowedMs)
        }
    }

    var configuredDurationMs: Long = 0L
        private set

    var curve: CrossfadeCurve = CrossfadeCurve.EQUAL_POWER

    val isEnabled: Boolean
        get() = configuredDurationMs > 0L

    var state: CrossfadeState = CrossfadeState.IDLE
        private set

    val isCrossfading: Boolean
        get() = state == CrossfadeState.CROSSFADING

    var activePlayer: ExoPlayer = player1
        private set

    var standbyPlayer: ExoPlayer = player2
        private set

    private var crossfadeJob: Job? = null
    private var preparedMediaId: String? = null
    private var outgoingPlayerForActiveFade: ExoPlayer? = null
    private var incomingPlayerForActiveFade: ExoPlayer? = null

    /**
     * Updates the user's configured crossfade duration in milliseconds.
     */
    fun setDurationMs(durationMs: Long) {
        val safeMs = durationMs.coerceIn(0L, 12_000L)
        configuredDurationMs = safeMs
        if (safeMs <= 0L) {
            cancelCrossfade(keepIncoming = true)
            state = CrossfadeState.IDLE
        }
        Log.d(TAG, "Crossfade duration configured: ${safeMs}ms (enabled=$isEnabled)")
    }

    /**
     * Evaluates active player position and triggers pre-buffering or overlapping transition when appropriate.
     */
    fun onPositionUpdate(positionMs: Long, durationMs: Long) {
        if (!isEnabled || isCrossfading || !activePlayer.isPlaying) {
            return
        }
        if (durationMs <= 0L || durationMs == C.TIME_UNSET) {
            return
        }

        val effectiveDurationMs = calculateEffectiveDurationMs(durationMs, configuredDurationMs)
        if (effectiveDurationMs <= 0L) {
            return
        }

        val remainingMs = durationMs - positionMs

        // 1. Stage 1: Pre-buffer upcoming track into standby player
        val preloadThreshold = effectiveDurationMs + PRELOAD_AHEAD_MS
        if (remainingMs in (effectiveDurationMs + 1)..preloadThreshold) {
            prepareNextTrackIfNeeded(activePlayer, standbyPlayer)
            return
        }

        // 2. Stage 2: Trigger overlapping crossfade when reaching transition window
        if (remainingMs in 1..effectiveDurationMs) {
            triggerCrossfadeIfReady(activePlayer, standbyPlayer, effectiveDurationMs, remainingMs)
        }
    }

    /**
     * Pre-buffers the next track into the standby player without starting audio.
     */
    private fun prepareNextTrackIfNeeded(current: ExoPlayer, next: ExoPlayer) {
        if (state == CrossfadeState.PREPARING_NEXT || state == CrossfadeState.READY_FOR_CROSSFADE) {
            return
        }

        val nextItems = getUpcomingMediaItems(current)
        if (nextItems.isEmpty()) {
            return
        }

        val primaryNextItem = nextItems.first()
        if (preparedMediaId == primaryNextItem.mediaId && next.playbackState == Player.STATE_READY) {
            state = CrossfadeState.READY_FOR_CROSSFADE
            return
        }

        Log.d(TAG, "Pre-buffering incoming track '${primaryNextItem.mediaMetadata.title}' (${nextItems.size} items) into standby player...")
        state = CrossfadeState.PREPARING_NEXT
        preparedMediaId = primaryNextItem.mediaId

        next.volume = 0f
        next.playWhenReady = false
        next.repeatMode = current.repeatMode
        next.shuffleModeEnabled = current.shuffleModeEnabled
        next.setMediaItems(nextItems, 0, 0L)
        next.prepare()

        // Listen for readiness
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        if (state == CrossfadeState.PREPARING_NEXT) {
                            state = CrossfadeState.READY_FOR_CROSSFADE
                            Log.d(TAG, "Incoming track '${primaryNextItem.mediaMetadata.title}' is READY for crossfade")
                        }
                        next.removeListener(this)
                    }
                    Player.STATE_IDLE, Player.STATE_ENDED -> {
                        next.removeListener(this)
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.w(TAG, "Standby player preparation failed: ${error.message}. Aborting crossfade preparation.")
                state = CrossfadeState.IDLE
                preparedMediaId = null
                next.removeListener(this)
            }
        }
        next.addListener(listener)
    }

    /**
     * Commits to crossfade transition only when the incoming track is verified ready.
     */
    private fun triggerCrossfadeIfReady(
        outgoing: ExoPlayer,
        incoming: ExoPlayer,
        effectiveDurationMs: Long,
        remainingMs: Long
    ) {
        // Pre-buffer if not already triggered
        if (preparedMediaId == null) {
            prepareNextTrackIfNeeded(outgoing, incoming)
        }

        // Section 3 & 7 Rule: Only start if incoming player is verified ready!
        if (incoming.playbackState != Player.STATE_READY) {
            Log.d(TAG, "Incoming player not ready yet (${incoming.playbackState}). Outgoing player continues at full volume.")
            return
        }

        // Commit to overlapping crossfade
        val activeFadeDurationMs = remainingMs.coerceAtMost(effectiveDurationMs).coerceAtLeast(1_000L)
        executeCrossfade(outgoing, incoming, activeFadeDurationMs)
    }

    /**
     * Executes the true overlapping volume crossfade.
     */
    private fun executeCrossfade(outgoing: ExoPlayer, incoming: ExoPlayer, fadeDurationMs: Long) {
        if (isCrossfading) return

        state = CrossfadeState.CROSSFADING
        outgoingPlayerForActiveFade = outgoing
        incomingPlayerForActiveFade = incoming

        Log.d(TAG, "Commencing Apple Music equal-power crossfade over ${fadeDurationMs}ms...")

        crossfadeJob?.cancel()
        crossfadeJob = scope.launch(Dispatchers.Main) {
            try {
                // 1. Start incoming player at 0 volume
                incoming.volume = 0f
                incoming.playWhenReady = true
                incoming.play()

                // 2. Synchronize MediaSession to incoming player so UI/Notification updates smoothly
                val session = mediaSessionProvider()
                session?.setPlayer(incoming)
                activePlayer = incoming
                standbyPlayer = outgoing
                onPlayerSwapped(incoming)

                // 3. Step volume envelopes
                val steps = (fadeDurationMs / FADE_STEP_INTERVAL_MS).toInt().coerceIn(15, 400)
                val stepDelay = (fadeDurationMs / steps).coerceAtLeast(16L)

                for (step in 1..steps) {
                    if (!isActive) break

                    val progress = step.toFloat() / steps.toFloat()
                    val outGain = calculateOutgoingGain(progress, curve)
                    val inGain = calculateIncomingGain(progress, curve)

                    outgoing.volume = outGain
                    incoming.volume = inGain

                    delay(stepDelay)
                }

                // 4. Completed successfully
                Log.d(TAG, "Crossfade transition completed successfully.")
                finishTransition(outgoing, incoming, completedNormally = true)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Crossfade cancelled normally (user track switch or pause)")
                } else {
                    Log.e(TAG, "Crossfade interrupted or error occurred", e)
                }
                finishTransition(outgoing, incoming, completedNormally = false)
            }
        }
    }

    /**
     * Cleans up players upon crossfade completion or cancellation.
     */
    private fun finishTransition(outgoing: ExoPlayer, incoming: ExoPlayer, completedNormally: Boolean) {
        outgoing.volume = 0f
        outgoing.stop()
        outgoing.clearMediaItems()
        outgoing.volume = 1f // Reset volume for standby state

        incoming.volume = 1f

        outgoingPlayerForActiveFade = null
        incomingPlayerForActiveFade = null
        preparedMediaId = null
        state = if (completedNormally) CrossfadeState.COMPLETED else CrossfadeState.CANCELLED

        // Reset state back to IDLE after completion
        state = CrossfadeState.IDLE
    }

    /**
     * Resolves the upcoming MediaItems to load into incoming player based on current playback and repeat mode.
     */
    private fun getUpcomingMediaItems(player: ExoPlayer): List<MediaItem> {
        val currentItem = player.currentMediaItem ?: return emptyList()
        val currentIndex = player.currentMediaItemIndex
        val totalCount = player.mediaItemCount

        // 1. Repeat One: Loop current song into itself
        if (player.repeatMode == Player.REPEAT_MODE_ONE) {
            return listOf(currentItem)
        }

        // 2. Natural Queue: Songs from currentIndex + 1 onwards
        if (currentIndex + 1 < totalCount) {
            return (currentIndex + 1 until totalCount).map { player.getMediaItemAt(it) }
        }

        // 3. Repeat All: If at end of queue, loop to start
        if (player.repeatMode == Player.REPEAT_MODE_ALL && totalCount > 0) {
            return (0 until totalCount).map { player.getMediaItemAt(it) }
        }

        return emptyList()
    }

    /**
     * Cancels any active crossfade or preparation, safely resetting player states.
     */
    fun cancelCrossfade(keepIncoming: Boolean = true) {
        val job = crossfadeJob
        crossfadeJob = null
        job?.cancel()

        val outgoing = outgoingPlayerForActiveFade
        val incoming = incomingPlayerForActiveFade

        if (outgoing != null) {
            if (keepIncoming) {
                outgoing.volume = 0f
                outgoing.stop()
                outgoing.clearMediaItems()
                outgoing.volume = 1f
            } else {
                outgoing.volume = 1f
            }
        }

        if (incoming != null) {
            if (keepIncoming) {
                incoming.volume = 1f
            } else {
                incoming.volume = 0f
                incoming.stop()
                incoming.clearMediaItems()
                incoming.volume = 1f
            }
        }

        if (state == CrossfadeState.PREPARING_NEXT || state == CrossfadeState.READY_FOR_CROSSFADE) {
            standbyPlayer.stop()
            standbyPlayer.clearMediaItems()
            standbyPlayer.volume = 1f
        }

        outgoingPlayerForActiveFade = null
        incomingPlayerForActiveFade = null
        preparedMediaId = null
        state = CrossfadeState.IDLE
    }

    /**
     * Coordinated pause handling during active crossfade.
     */
    fun onUserPause() {
        if (isCrossfading) {
            outgoingPlayerForActiveFade?.pause()
            incomingPlayerForActiveFade?.pause()
        } else {
            activePlayer.pause()
        }
    }

    /**
     * Coordinated resume handling during active crossfade.
     */
    fun onUserResume() {
        if (isCrossfading) {
            outgoingPlayerForActiveFade?.play()
            incomingPlayerForActiveFade?.play()
        } else {
            activePlayer.play()
        }
    }

    /**
     * Handles manual skip during crossfade.
     */
    fun onUserSkip(): Boolean {
        if (isCrossfading) {
            Log.d(TAG, "User skipped track during active crossfade. Fast-forwarding to incoming player.")
            cancelCrossfade(keepIncoming = true)
            return true
        }
        return false
    }

    /**
     * Handles manual previous during crossfade.
     */
    fun onUserPrevious(): Boolean {
        if (isCrossfading) {
            Log.d(TAG, "User tapped previous during active crossfade. Reverting to outgoing player.")
            val outgoing = outgoingPlayerForActiveFade
            cancelCrossfade(keepIncoming = false)
            if (outgoing != null) {
                activePlayer = outgoing
                standbyPlayer = incomingPlayerForActiveFade ?: if (outgoing == player1) player2 else player1
                mediaSessionProvider()?.setPlayer(outgoing)
                onPlayerSwapped(outgoing)
                outgoing.seekTo(0L)
            }
            return true
        }
        return false
    }

    /**
     * Handles manual seek during crossfade.
     */
    fun onUserSeek() {
        if (isCrossfading) {
            Log.d(TAG, "User seeked during crossfade. Aborting crossfade.")
            cancelCrossfade(keepIncoming = true)
        }
    }

    /**
     * Release all crossfade resources.
     */
    fun release() {
        cancelCrossfade(keepIncoming = true)
    }
}
