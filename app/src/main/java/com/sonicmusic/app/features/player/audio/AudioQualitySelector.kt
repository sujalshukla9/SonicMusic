package com.sonicmusic.app.features.player.audio

import android.os.Build
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import android.util.Log
import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.LicenseType
import com.sonicmusic.app.domain.model.SourceType
import com.sonicmusic.app.domain.model.StreamQuality
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ranks playable streams by the requested quality using the metadata reported by each source.
 * Compatible audio-only tracks take priority over muxed tracks that also carry video.
 */
@Singleton
class AudioQualitySelector @Inject constructor() {

    companion object {
        private const val TAG = "AudioQualitySelector"

        private val AAC_PROFILE = Regex("(?i)mp4a\\.40\\.[0-9]+")
    }

    /**
     * Represents the outcome of candidate selection with selection rationale.
     */
    data class SelectionResult(
        val candidate: AudioStreamCandidate,
        val rationale: String,
        val allValidCandidates: List<AudioStreamCandidate>
    )

    /**
     * Select the best audio candidate from a list of extracted streams.
     *
     * @param candidates All discovered audio stream descriptors.
     * @param requestedQuality The user's requested quality tier.
     * @param excludeUrls URLs to exclude (e.g. previously failed candidates during recovery).
     * @return SelectionResult with the chosen candidate and rationale, or null if none usable.
     */
    fun selectBestCandidate(
        candidates: List<AudioStreamCandidate>,
        requestedQuality: StreamQuality = StreamQuality.BEST,
        excludeUrls: Set<String> = emptySet(),
    ): SelectionResult? {
        if (candidates.isEmpty()) {
            Log.w(TAG, "Cannot select stream: Candidate list is empty")
            return null
        }

        // 1. Filter usable and unexcluded candidates
        val validCandidates = candidates.filter { candidate ->
            isCandidateUsable(candidate, excludeUrls)
        }

        if (validCandidates.isEmpty()) {
            Log.w(TAG, "No valid/unexcluded audio candidates remaining out of ${candidates.size}")
            return null
        }

        val preferBitrateTarget = !requestedQuality.prefersHighestQuality
        val ranked = validCandidates.sortedWith(
            compareByDescending<AudioStreamCandidate> { it.isAudioOnly }
                .thenByDescending { !preferBitrateTarget && it.isLossless() }
                .thenByDescending { !preferBitrateTarget && it.isExtendedQualityAudio() }
                .thenByDescending { preferBitrateTarget && it.bitrateBps > 0 }
                .thenByDescending { computeCandidateScore(it, requestedQuality) }
                .thenByDescending { it.bitrateBps }
                .thenByDescending { it.sampleRateHz }
                .thenByDescending { it.channelCount }
        )
        val best = ranked.first()

        val rationale = buildString {
            append("Selected itag=${best.itag ?: "N/A"} (${best.audioCodec} ")
            if (best.bitrateKbps > 0) append("${best.bitrateKbps}kbps, ")
            if (best.sampleRate > 0) append("${best.sampleRate}Hz, ")
            if (best.channelCount > 0) append("${best.channelCount}ch, ")
            append(if (best.isAudioOnly) "audio-only" else "muxed")
            append("), profile=${best.codecProfile ?: "unknown"}, source=${best.sourceName}. ")
            append("Ranked first of ${ranked.size} playable formats for ${requestedQuality.displayName}.")
        }

        Log.d(TAG, rationale)
        return SelectionResult(best, rationale, ranked)
    }

    /**
     * Select the next best fallback candidate when the primary candidate fails.
     */
    fun selectFallbackCandidate(
        candidates: List<AudioStreamCandidate>,
        failedUrls: Set<String>,
        requestedQuality: StreamQuality = StreamQuality.BEST,
    ): AudioStreamCandidate? {
        val result = selectBestCandidate(candidates, requestedQuality, excludeUrls = failedUrls)
        return result?.candidate
    }

    /**
     * Checks if a stream candidate is usable for playback on this device.
     */
    fun isCandidateUsable(candidate: AudioStreamCandidate, excludeUrls: Set<String>): Boolean {
        // Discard blank URLs or explicitly excluded URLs (failed streams)
        if (candidate.url.isBlank() || excludeUrls.contains(candidate.url)) {
            return false
        }

        // Discard expired URLs
        if (candidate.isExpired()) {
            Log.d(TAG, "Discarding expired stream candidate: itag=${candidate.itag}")
            return false
        }

        // Check decoder compatibility
        if (!isCodecSupported(candidate)) {
            Log.w(TAG, "Discarding candidate with unsupported codec: ${candidate.codec} (${candidate.mimeType})")
            return false
        }

        // Discard candidate with RESTRICTED license
        if (candidate.licenseType == LicenseType.RESTRICTED) {
            Log.w(TAG, "Discarding candidate with RESTRICTED license:")
            return false
        }

        // Discard external candidate if identity confidence is below threshold (< 0.75)
        if (candidate.sourceType != SourceType.INNERTUBE && candidate.sourceType != SourceType.LOCAL_FILE) {
            if (candidate.identityConfidence < 0.75f) {
                Log.d(TAG, "Discarding candidate with low identity confidence: ${candidate.identityConfidence} for '${candidate.originalTitle}'")
                return false
            }
        }

        return true
    }

    /**
     * Checks if the device has a compatible decoder for the given candidate stream.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun isCodecSupported(candidate: AudioStreamCandidate): Boolean {
        val mime = MimeTypes.getAudioMediaMimeType(candidate.codecProfile ?: candidate.mimeType)
            ?: when (candidate.audioCodec.uppercase()) {
                "AAC", "AAC-LC", "AAC-HE", "AAC-HEV2" -> MimeTypes.AUDIO_AAC
                "OPUS" -> MimeTypes.AUDIO_OPUS
                "MP3" -> MimeTypes.AUDIO_MPEG
                "FLAC" -> MimeTypes.AUDIO_FLAC
                "VORBIS" -> MimeTypes.AUDIO_VORBIS
                "ALAC" -> MimeTypes.AUDIO_ALAC
                "WAV" -> return true
                else -> return false
            }
        // Plain JVM tests have no platform codec registry.
        if (Build.VERSION.SDK_INT == 0) return true

        val profile = candidate.codecProfile?.let { if (it.startsWith("40.")) "mp4a.$it" else it }
            ?: if (candidate.isAac()) AAC_PROFILE.find(candidate.mimeType)?.value else null
        val format = Format.Builder()
            .setSampleMimeType(mime)
            .setCodecs(profile)
            .setSampleRate(candidate.sampleRateHz.takeIf { it > 0 } ?: Format.NO_VALUE)
            .setChannelCount(candidate.channelCount.takeIf { it > 0 } ?: Format.NO_VALUE)
            .build()
        return try {
            MediaCodecUtil.getDecoderInfos(mime, false, false).any { it.isFormatSupported(format) }
        } catch (e: MediaCodecUtil.DecoderQueryException) {
            Log.w(TAG, "Unable to query decoders for $mime", e)
            false
        }
    }

    /**
     * Scores formats by bitrate target or estimated fidelity, depending on the quality setting.
     */
    fun computeCandidateScore(candidate: AudioStreamCandidate, requestedQuality: StreamQuality): Long {
        val isOpus = candidate.audioCodec.equals("OPUS", ignoreCase = true)
        val isAac = candidate.isAac()
        val isAacHe = candidate.isAacHe()
        val isLossless = candidate.isLossless()
        val bitrate = (candidate.bitrateBps / 1_000L).takeIf { it > 0 }
            ?: AudioStreamInfo.nominalBitrateForItag(candidate.itag)?.toLong()
                ?.takeIf { requestedQuality.prefersHighestQuality && it == 256L }
            ?: 0L
        val isAudioOnly = candidate.isAudioOnly

        val baseScore = when (requestedQuality) {
            StreamQuality.LOW -> {
                // Data Saver: prefer low bitrate streams (~50-70 kbps)
                val distance = kotlin.math.abs(bitrate - requestedQuality.bitrate)
                val base = 10_000L - distance * 100L
                val codecBonus = if (isOpus) 500L else if (isAacHe) 300L else 0L
                val audioBonus = if (isAudioOnly) 5_000L else 0L
                base + codecBonus + audioBonus
            }
            StreamQuality.MEDIUM -> {
                // Balanced: prefer streams closest to 128 kbps
                val distance = kotlin.math.abs(bitrate - requestedQuality.bitrate)
                val base = 10_000L - distance * 100L
                val codecBonus = if (isOpus) 500L else 0L
                val audioBonus = if (isAudioOnly) 5_000L else 0L
                base + codecBonus + audioBonus
            }
            StreamQuality.HIGH, StreamQuality.BEST -> {
                // Heuristic weights for comparing formats with different codec efficiencies.
                val effectiveBitrate = when {
                    isLossless -> 320_000L + bitrate.coerceIn(500L, 1_500L) * 50L
                    isOpus -> bitrate * 1_300L
                    isAacHe -> bitrate * 850L
                    isAac -> bitrate * 1_100L
                    else -> bitrate * 900L
                }

                val sampleRateBonus = candidate.sampleRate.coerceIn(0, 48_000) / 100L
                val channelBonus = if (candidate.channelCount >= 2) 1_000L else 0L
                val audioOnlyBonus = if (isAudioOnly) 50_000L else 0L

                effectiveBitrate + sampleRateBonus + channelBonus + audioOnlyBonus
            }
        }

        // Multi-source adjustments:
        // Local files get highest reliability bonus (offline, zero latency)
        // Innertube gets strong reliability bonus (official release, licensed CDN)
        val identityBonus = (candidate.identityConfidence * 10_000L).toLong()
        val sourceReliabilityBonus = when (candidate.sourceType) {
            SourceType.LOCAL_FILE -> 30_000L
            SourceType.INNERTUBE -> 20_000L
        }
        val licenseBonus = when (candidate.licenseType) {
            LicenseType.RESTRICTED -> -500_000L
            LicenseType.PUBLIC_DOMAIN -> 3_000L
            LicenseType.CREATIVE_COMMONS -> 2_000L
            else -> 0L
        }
        val verifiedBonus = if (candidate.isVerifiedSource) 5_000L else 0L

        return baseScore + identityBonus + sourceReliabilityBonus + licenseBonus + verifiedBonus
    }
}
