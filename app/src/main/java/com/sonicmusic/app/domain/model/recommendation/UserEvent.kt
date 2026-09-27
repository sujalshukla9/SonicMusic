package com.sonicmusic.app.domain.model.recommendation

/**
 * A single user behavior event that feeds the recommendation engine.
 * Immutable record — once created, it never changes.
 */
data class UserEvent(
    val signalType: SignalType,
    val songId: String,
    val artistId: String?,
    val artistName: String,
    val genre: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    /** Completion percentage [0..1] for playback events */
    val completionPct: Float? = null
) {
    companion object {
        /**
         * Determine the correct signal type from a playback completion percentage.
         */
        fun signalFromCompletion(completionPct: Float, durationSec: Int, playDurationSec: Int): SignalType {
            val isCompleted = completionPct >= 0.9f || (durationSec > 0 && playDurationSec >= durationSec * 0.9f)
            return when {
                isCompleted -> SignalType.SONG_COMPLETED
                completionPct >= 0.5f -> SignalType.COMPLETION_PARTIAL
                completionPct >= 0.3f -> SignalType.COMPLETION_LOW
                playDurationSec < 10 || completionPct < 0.1f -> SignalType.SKIP_EARLY
                completionPct < 0.3f -> SignalType.SKIP_MID
                else -> SignalType.PLAY_STARTED
            }
        }
    }
}
