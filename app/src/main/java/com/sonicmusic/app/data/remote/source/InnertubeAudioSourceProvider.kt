package com.sonicmusic.app.data.remote.source

import com.sonicmusic.app.domain.audio.models.TrackIdentity
import com.sonicmusic.app.domain.audio.provider.AudioSourceProvider
import com.sonicmusic.app.domain.model.AudioStreamCandidate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AudioSourceProvider adapter for Innertube / NewPipe extraction.
 */
@Singleton
class InnertubeAudioSourceProvider @Inject constructor(
    private val audioStreamExtractor: AudioStreamExtractor
) : AudioSourceProvider {

    override suspend fun discoverCandidates(
        track: TrackIdentity,
        forceRefresh: Boolean
    ): List<AudioStreamCandidate> {
        if (track.trackId.isBlank()) return emptyList()
        return audioStreamExtractor.extractAllCandidates(track.trackId, forceRefresh).getOrElse { emptyList() }
    }
}
