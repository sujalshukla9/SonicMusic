package com.sonicmusic.app.data.repository

import com.sonicmusic.app.domain.audio.models.TrackIdentity
import com.sonicmusic.app.domain.audio.resolver.AudioResolver
import com.sonicmusic.app.domain.audio.resolver.IntelligentAudioResolver
import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.PlaybackStreamFormat
import com.sonicmusic.app.domain.model.StreamQuality
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter delegating to [AudioResolver] for backward compatibility.
 */
@Singleton
class IntelligentSourceResolver @Inject constructor(
    private val intelligentAudioResolver: IntelligentAudioResolver
) : AudioResolver {

    suspend fun resolveStream(
        videoId: String,
        title: String? = null,
        artist: String? = null,
        durationSec: Int? = null,
        quality: StreamQuality = StreamQuality.BEST,
        forceRefresh: Boolean = false,
        excludeUrls: Set<String> = emptySet(),
        excludeFormats: Set<PlaybackStreamFormat> = emptySet(),
        onUpgradeAvailable: ((AudioStreamCandidate, AudioStreamInfo) -> Unit)? = null
    ): Result<Pair<AudioStreamCandidate, AudioStreamInfo>> {
        val track = TrackIdentity.from(
            trackId = videoId,
            title = title.orEmpty(),
            artist = artist,
            durationSec = durationSec
        )
        return intelligentAudioResolver.resolveStream(
            track, quality, forceRefresh, excludeUrls, excludeFormats, onUpgradeAvailable
        )
    }

    override suspend fun resolveStream(
        track: TrackIdentity,
        quality: StreamQuality,
        forceRefresh: Boolean,
        excludeUrls: Set<String>,
        excludeFormats: Set<PlaybackStreamFormat>,
        onUpgradeAvailable: ((AudioStreamCandidate, AudioStreamInfo) -> Unit)?
    ): Result<Pair<AudioStreamCandidate, AudioStreamInfo>> =
        intelligentAudioResolver.resolveStream(track, quality, forceRefresh, excludeUrls, excludeFormats, onUpgradeAvailable)

    fun cancelResolution(videoId: String) {
        intelligentAudioResolver.cancelResolution(videoId)
    }

    fun cancelAllResolutions() {
        intelligentAudioResolver.cancelAllResolutions()
    }
}
