package com.sonicmusic.app.domain.audio.models

import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.LicenseType

/**
 * Objective classification of audio stream fidelity.
 */
enum class QualityClassification(val displayName: String) {
    VERIFIED_LOSSLESS("Verified Lossless"),
    VERIFIED_LOSSY("Verified Lossy"),
    UNKNOWN("Unknown Quality")
}

/**
 * Technical verification status for quality claims.
 */
enum class VerificationStatus(val displayName: String) {
    VERIFIED("Verified"),
    UNVERIFIED("Unverified"),
    INSUFFICIENT_EVIDENCE("Insufficient Evidence")
}

/**
 * Recording version type to guard against version mismatches.
 */
enum class VersionType(val displayName: String) {
    STUDIO("Studio"),
    LIVE("Live Performance"),
    REMIX("Remix"),
    ACOUSTIC("Acoustic"),
    INSTRUMENTAL("Instrumental"),
    KARAOKE("Karaoke"),
    DJ_EDIT("DJ / Radio Edit"),
    COVER("Cover / Tribute"),
    UNKNOWN("Standard / Unknown")
}

/**
 * Detailed technical evidence collected by AudioQualityInspector.
 */
data class QualityEvidence(
    val containerFormat: String,
    val codec: String,
    val classification: QualityClassification,
    val bitrateBps: Long? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
    val provenance: String,
    val verificationStatus: VerificationStatus,
    val isHiRes: Boolean = false,
    val isTranscodeSuspected: Boolean = false,
    val notes: String = ""
)

/**
 * Rationale and comparison outcome between audio source candidates.
 */
data class QualityComparison(
    val winner: AudioStreamCandidate,
    val reason: String,
    val isMaterialImprovement: Boolean
)

/**
 * Sealed result type representing the outcome of intelligent audio resolution.
 */
sealed class AudioResolutionResult {
    data class Success(
        val selectedCandidate: AudioStreamCandidate,
        val fallbackCandidates: List<AudioStreamCandidate> = emptyList(),
        val selectionReason: String,
        val qualityComparison: QualityComparison? = null,
        val verificationStatus: VerificationStatus = VerificationStatus.VERIFIED
    ) : AudioResolutionResult()

    data class Failure(
        val failureReason: String,
        val recoverable: Boolean = true
    ) : AudioResolutionResult()
}

/**
 * Convert successful resolution to [SelectedPlaybackInfo].
 */
fun AudioResolutionResult.Success.toSelectedPlaybackInfo(songId: String): SelectedPlaybackInfo {
    return SelectedPlaybackInfo.fromCandidate(
        songId = songId,
        candidate = selectedCandidate,
        selectionReason = selectionReason
    )
}

/**
 * Convert [AudioStreamInfo] to [SelectedPlaybackInfo].
 */
fun AudioStreamInfo.toSelectedPlaybackInfo(songId: String): SelectedPlaybackInfo {
    val isFlac = codec.equals("FLAC", true)
    return SelectedPlaybackInfo(
        songId = songId,
        selectedSource = sourceType,
        sourceName = if (isLocal) "Local Storage" else "YouTube Music",
        playbackUri = "",
        container = containerFormat,
        codec = codec,
        bitrateKbps = bitrate.takeIf { it > 0 },
        sampleRateHz = sampleRate.takeIf { it > 0 },
        bitDepth = bitDepth.takeIf { it > 0 } ?: (if (isFlac) 16 else null),
        channelCount = if (channelCount > 0) channelCount else 2,
        qualityClassification = qualityClassification,
        verificationStatus = verificationStatus,
        qualityBadge = verifiedQualityBadge,
        identityConfidence = 1.0f,
        licenseType = LicenseType.STREAMING,
        selectionReason = "Innertube playback",
        isFallback = false,
        isHiRes = isHighRes
    )
}
