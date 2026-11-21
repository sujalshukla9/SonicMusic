package com.sonicmusic.app.domain.audio.models

import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.LicenseType
import com.sonicmusic.app.domain.model.SourceType
import com.sonicmusic.app.domain.model.StreamQuality

/**
 * Technical snapshot of the explicitly chosen, active audio playback candidate.
 *
 * Keeps playback source properties decoupled from high-level catalog metadata ([TrackMetadata]).
 * The player UI and diagnostics derive their technical telemetry solely from this model,
 * preventing cross-source contamination.
 */
data class SelectedPlaybackInfo(
    val songId: String,
    val selectedSource: SourceType,
    val sourceName: String,
    val playbackUri: String,
    val container: String = "",
    val codec: String = "",
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int = 2,
    val qualityClassification: QualityClassification = QualityClassification.UNKNOWN,
    val verificationStatus: VerificationStatus = VerificationStatus.VERIFIED,
    val qualityBadge: String = "",
    val identityConfidence: Float = 1.0f,
    val licenseType: LicenseType = LicenseType.STREAMING,
    val selectionReason: String = "",
    val isFallback: Boolean = false,
    val isHiRes: Boolean = false,
    val candidate: AudioStreamCandidate? = null
) {
    val isLocal: Boolean get() = selectedSource == SourceType.LOCAL_FILE
    val isInnertube: Boolean get() = selectedSource == SourceType.INNERTUBE

    fun toAudioStreamInfo(qualityTier: StreamQuality = StreamQuality.BEST): AudioStreamInfo {
        val br = bitrateKbps ?: 0
        return AudioStreamInfo(
            codec = codec,
            bitrate = br,
            sampleRate = sampleRateHz ?: 0,
            bitDepth = bitDepth ?: if (codec.equals("FLAC", true)) 16 else 0,
            qualityTier = if (br > 0) AudioStreamInfo.qualityTierFromStream(codec, br) else qualityTier,
            containerFormat = container,
            channelCount = channelCount,
            isEnhanced = false,
            isLocal = isLocal,
            isArchiveSource = false,
            sourceType = selectedSource,
            itag = candidate?.itag,
            loudnessDb = candidate?.loudnessDb,
            contentLength = candidate?.contentLength,
            qualityClassification = qualityClassification,
            verificationStatus = verificationStatus
        )
    }

    companion object {
        fun fromCandidate(
            songId: String,
            candidate: AudioStreamCandidate,
            selectionReason: String = "",
            isFallback: Boolean = false
        ): SelectedPlaybackInfo {
            val isFlac = candidate.isFlac()
            val isHiRes = isFlac && ((candidate.sampleRate > 48000) || (candidate.bitDepth > 16))
            val badge = when {
                isHiRes -> "Hi-Res Lossless — Verified"
                isFlac -> "Lossless — Verified"
                candidate.bitrateKbps >= 256 -> "High Quality — Verified"
                candidate.bitrateKbps > 0 -> "Standard Quality"
                else -> "Quality Unknown"
            }

            return SelectedPlaybackInfo(
                songId = songId,
                selectedSource = candidate.sourceType,
                sourceName = candidate.sourceName,
                playbackUri = candidate.playbackUri,
                container = candidate.container,
                codec = candidate.codec,
                bitrateKbps = candidate.bitrateKbps.takeIf { it > 0 },
                sampleRateHz = candidate.sampleRate.takeIf { it > 0 },
                bitDepth = candidate.bitDepth.takeIf { it > 0 } ?: (if (isFlac) 16 else null),
                channelCount = if (candidate.channelCount > 0) candidate.channelCount else 2,
                qualityClassification = candidate.qualityClassification,
                verificationStatus = candidate.verificationStatus,
                qualityBadge = badge,
                identityConfidence = candidate.identityConfidence,
                licenseType = candidate.licenseType,
                selectionReason = selectionReason,
                isFallback = isFallback,
                isHiRes = isHiRes,
                candidate = candidate
            )
        }
    }
}
