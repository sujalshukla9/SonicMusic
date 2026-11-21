package com.sonicmusic.app.domain.audio.provider

import com.sonicmusic.app.domain.audio.models.TrackIdentity
import com.sonicmusic.app.domain.model.AudioStreamCandidate

/**
 * Audio candidate provider representing an external audio source.
 */
interface AudioSourceProvider {
    /**
     * Discovers all eligible audio stream candidates for a track.
     */
    suspend fun discoverCandidates(
        track: TrackIdentity,
        forceRefresh: Boolean = false
    ): List<AudioStreamCandidate>
}
