package com.sonicmusic.app.domain.audio.resolver

import com.sonicmusic.app.domain.audio.models.TrackIdentity
import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.StreamQuality

/**
 * Main coordinator for audio discovery, quality inspection,
 * selection policy enforcement, and stream resolution.
 */
interface AudioResolver {
    /**
     * Resolves the highest quality verifiable audio stream for a track.
     */
    suspend fun resolveStream(
        track: TrackIdentity,
        quality: StreamQuality = StreamQuality.BEST,
        forceRefresh: Boolean = false,
        excludeUrls: Set<String> = emptySet(),
        onUpgradeAvailable: ((AudioStreamCandidate, AudioStreamInfo) -> Unit)? = null
    ): Result<Pair<AudioStreamCandidate, AudioStreamInfo>>
}
