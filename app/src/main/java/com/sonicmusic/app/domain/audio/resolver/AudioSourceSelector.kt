package com.sonicmusic.app.domain.audio.resolver

import com.sonicmusic.app.domain.audio.models.AudioResolutionResult
import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.domain.model.StreamQuality

/**
 * Dedicated source selector enforcing the quality-selection policy.
 */
interface AudioSourceSelector {
    /**
     * Selects the best audio source from available Innertube candidates.
     */
    fun selectBestSource(
        innertubeCandidates: List<AudioStreamCandidate>,
        requestedQuality: StreamQuality = StreamQuality.BEST,
        excludeUrls: Set<String> = emptySet(),
    ): AudioResolutionResult

    /**
     * Selects a fallback candidate when the primary source encounters a playback failure.
     */
    fun selectFallbackCandidate(
        allCandidates: List<AudioStreamCandidate>,
        failedUrls: Set<String>,
        requestedQuality: StreamQuality = StreamQuality.BEST
    ): AudioStreamCandidate?
}
