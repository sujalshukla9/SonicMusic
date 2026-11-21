package com.sonicmusic.app.domain.audio.resolver

import com.sonicmusic.app.domain.audio.models.AudioResolutionResult
import com.sonicmusic.app.domain.audio.quality.AudioQualityInspector
import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.features.player.audio.AudioQualitySelector
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAudioSourceSelector @Inject constructor(
    private val qualityInspector: AudioQualityInspector,
    private val qualitySelector: AudioQualitySelector = AudioQualitySelector()
) : AudioSourceSelector {

    override fun selectBestSource(
        innertubeCandidates: List<AudioStreamCandidate>,
        requestedQuality: StreamQuality,
        excludeUrls: Set<String>,
    ): AudioResolutionResult {
        val selection = qualitySelector.selectBestCandidate(innertubeCandidates, requestedQuality, excludeUrls)
            ?: return AudioResolutionResult.Failure(
                failureReason = "No matching, playable audio candidates available.",
                recoverable = true
            )
        val evidence = qualityInspector.inspect(selection.candidate)
        return AudioResolutionResult.Success(
            selectedCandidate = selection.candidate.copy(
                qualityClassification = evidence.classification,
                verificationStatus = evidence.verificationStatus
            ),
            fallbackCandidates = selection.allValidCandidates.filter { it.url != selection.candidate.url },
            selectionReason = selection.rationale,
            verificationStatus = evidence.verificationStatus
        )
    }

    override fun selectFallbackCandidate(
        allCandidates: List<AudioStreamCandidate>,
        failedUrls: Set<String>,
        requestedQuality: StreamQuality
    ): AudioStreamCandidate? =
        qualitySelector.selectFallbackCandidate(allCandidates, failedUrls, requestedQuality)
}
