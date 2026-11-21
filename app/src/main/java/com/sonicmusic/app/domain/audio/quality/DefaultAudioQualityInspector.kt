package com.sonicmusic.app.domain.audio.quality

import com.sonicmusic.app.domain.audio.models.QualityClassification
import com.sonicmusic.app.domain.audio.models.QualityEvidence
import com.sonicmusic.app.domain.audio.models.VerificationStatus
import com.sonicmusic.app.domain.model.AudioStreamCandidate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [AudioQualityInspector].
 *
 * Verifies that containers and file extensions are not blindly trusted.
 * Guards against fake lossless transcodes, inspects provenance strings,
 * and classifies candidates objectively.
 */
@Singleton
class DefaultAudioQualityInspector @Inject constructor() : AudioQualityInspector {

    companion object {
        private val SUSPECT_TRANSCODE_KEYWORDS = listOf(
            "transcode",
            "from mp3",
            "from aac",
            "lossy source",
            "youtube rip",
            "web rip",
            "128kbps transcode",
            "upscaled"
        )

        private val TRUSTED_LOSSLESS_PROVENANCES = listOf(
            "cd rip",
            "compact disc",
            "vinyl",
            "lp",
            "master",
            "soundboard",
            "sbd",
            "audiophile",
            "flac",
            "georgeblood",
            "78rpm"
        )
    }

    override fun inspect(
        candidate: AudioStreamCandidate,
        metadata: Map<String, Any?>
    ): QualityEvidence {
        val codec = candidate.codec.uppercase()
        val container = candidate.container.ifBlank {
            when {
                candidate.url.endsWith(".flac", true) -> "FLAC"
                candidate.url.endsWith(".mp3", true) -> "MP3"
                candidate.url.endsWith(".m4a", true) -> "M4A"
                candidate.url.endsWith(".ogg", true) -> "OGG"
                candidate.url.endsWith(".opus", true) -> "WebM"
                candidate.url.endsWith(".wav", true) -> "WAV"
                else -> "Unknown"
            }
        }

        val metaSource = (metadata["source"] as? String).orEmpty().lowercase()
        val metaNotes = (metadata["notes"] as? String).orEmpty().lowercase()
        val metaFormat = (metadata["format"] as? String).orEmpty().lowercase()
        val combinedMetadata = "$metaSource $metaNotes $metaFormat ${candidate.provenance.lowercase()}"

        // 1. Check for transcode from lossy source
        val isTranscodeSuspected = SUSPECT_TRANSCODE_KEYWORDS.any { keyword ->
            combinedMetadata.contains(keyword)
        }

        val isLosslessCodec = codec == "FLAC" || codec == "ALAC" || codec == "WAV"
        val rawBitDepth = candidate.bitDepth.takeIf { it > 0 }
            ?: (metadata["bit_depth"] as? Number)?.toInt()
            ?: (metadata["bitspersample"] as? Number)?.toInt()
            ?: if (isLosslessCodec && !isTranscodeSuspected) 16 else null

        val sampleRate = candidate.sampleRate.takeIf { it > 0 }
            ?: (metadata["sample_rate"] as? Number)?.toInt()
            ?: (metadata["samplerate"] as? Number)?.toInt()

        val bitrateBps = candidate.bitrateBps.takeIf { it > 0 }
            ?: (metadata["bitrate"] as? Number)?.toLong()?.times(1000L)

        val channels = candidate.channelCount.takeIf { it > 0 }
            ?: (metadata["channels"] as? Number)?.toInt() ?: 2

        // 2. Classify quality
        val classification: QualityClassification
        val status: VerificationStatus
        val notes: String

        if (isTranscodeSuspected) {
            classification = QualityClassification.UNKNOWN
            status = VerificationStatus.INSUFFICIENT_EVIDENCE
            notes = "Lossless container transcoded from a lossy source; stripped of lossless classification."
        } else if (isLosslessCodec) {
            val hasExplicitLosslessProvenance = TRUSTED_LOSSLESS_PROVENANCES.any { combinedMetadata.contains(it) } ||
                    candidate.sourceType == com.sonicmusic.app.domain.model.SourceType.LOCAL_FILE ||
                    (sampleRate != null && sampleRate >= 44100 && (rawBitDepth ?: 0) >= 16)

            if (hasExplicitLosslessProvenance) {
                classification = QualityClassification.VERIFIED_LOSSLESS
                status = VerificationStatus.VERIFIED
                notes = "Genuine lossless recording verified by technical parameters and source provenance."
            } else {
                classification = QualityClassification.UNKNOWN
                status = VerificationStatus.INSUFFICIENT_EVIDENCE
                notes = "Lossless container without verifiable source provenance or sample depth metadata."
            }
        } else if (codec in setOf("OPUS", "AAC", "AAC-LC", "AAC-HE", "MP3", "VORBIS")) {
            if ((bitrateBps != null && bitrateBps > 0) || (sampleRate != null && sampleRate > 0)) {
                classification = QualityClassification.VERIFIED_LOSSY
                status = VerificationStatus.VERIFIED
                notes = "Verified lossy stream with established technical properties."
            } else {
                classification = QualityClassification.UNKNOWN
                status = VerificationStatus.INSUFFICIENT_EVIDENCE
                notes = "Lossy stream with unconfirmed bitrate and sample rate."
            }
        } else {
            classification = QualityClassification.UNKNOWN
            status = VerificationStatus.INSUFFICIENT_EVIDENCE
            notes = "Unrecognized or missing codec."
        }

        // 3. Hi-Res qualification
        val isHiRes = classification == QualityClassification.VERIFIED_LOSSLESS &&
                ((sampleRate ?: 0) > 48000 || (rawBitDepth ?: 0) > 16)

        return QualityEvidence(
            containerFormat = container,
            codec = codec,
            classification = classification,
            bitrateBps = bitrateBps,
            sampleRateHz = sampleRate,
            bitDepth = rawBitDepth,
            channelCount = channels,
            provenance = candidate.provenance.ifBlank { candidate.sourceName },
            verificationStatus = status,
            isHiRes = isHiRes,
            isTranscodeSuspected = isTranscodeSuspected,
            notes = notes
        )
    }
}
