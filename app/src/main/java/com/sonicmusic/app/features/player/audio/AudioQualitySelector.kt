package com.sonicmusic.app.features.player.audio

import android.media.MediaCodecList
import android.util.Log
import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.domain.model.StreamQuality
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates, filters, and ranks audio stream candidates based on genuine acoustic fidelity,
 * device decoder capability, stream completeness, and user preferences.
 *
 * Evaluation Principles:
 * 1. Discover and validate candidate streams (reject empty, malformed, or expired streams).
 * 2. Verify device decoder compatibility before selection.
 * 3. Fair codec evaluation: Evaluates AAC, Opus, and lossless sources based on actual reported
 *    bitrate, sample rate, channels, and codec efficiency — never blindly forces a single codec.
 * 4. Audio-only streams are strongly preferred over muxed video streams to conserve bandwidth
 *    and prevent playback stalls.
 * 5. Provides fallback candidate ranking for error recovery.
 */
@Singleton
class AudioQualitySelector @Inject constructor() {

    companion object {
        private const val TAG = "AudioQualitySelector"

        // Standard Android supported audio MIME types
        private val STANDARD_SUPPORTED_AUDIO_MIMES = setOf(
            "audio/opus",
            "audio/webm",
            "audio/mp4a-latm",
            "audio/mp4",
            "audio/aac",
            "audio/mpeg",
            "audio/flac",
            "audio/ogg",
            "audio/vorbis",
            "audio/x-matroska",
            "audio/wav",
            "audio/x-wav"
        )
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

        // 2. Rank candidates using objective acoustic fidelity scoring
        val ranked = validCandidates.sortedWith(
            compareByDescending<AudioStreamCandidate> { computeCandidateScore(it, requestedQuality) }
                .thenByDescending { it.isAudioOnly }
                .thenByDescending { it.bitrateKbps }
                .thenByDescending { it.sampleRate }
                .thenByDescending { it.channelCount }
        )

        val best = ranked.firstOrNull() ?: return null

        val rationale = buildString {
            append("Selected itag=${best.itag ?: "N/A"} (${best.codec} ")
            if (best.bitrateKbps > 0) append("${best.bitrateKbps}kbps, ")
            if (best.sampleRate > 0) append("${best.sampleRate}Hz, ")
            if (best.channelCount > 0) append("${best.channelCount}ch, ")
            append(if (best.isAudioOnly) "audio-only" else "muxed")
            append(") from ${best.sourceName} among ${validCandidates.size} candidates for tier ${requestedQuality.displayName}.")
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
            Log.d(TAG, "Discarding expired stream candidate: itag=${candidate.itag}, url=${candidate.url.take(40)}...")
            return false
        }

        // Check decoder compatibility
        if (!isCodecSupported(candidate)) {
            Log.w(TAG, "Discarding candidate with unsupported codec: ${candidate.codec} (${candidate.mimeType})")
            return false
        }

        return true
    }

    /**
     * Checks if the device has a compatible decoder for the given candidate stream.
     */
    fun isCodecSupported(candidate: AudioStreamCandidate): Boolean {
        val mime = candidate.mimeType.substringBefore(";").trim().lowercase()
        val codecName = candidate.codec.uppercase()

        // Standard modern Android supported formats (Android 8.0+)
        if (codecName in setOf("OPUS", "AAC", "AAC-LC", "AAC-HE", "MP3", "FLAC", "ALAC", "VORBIS", "WAV")) {
            return true
        }

        if (STANDARD_SUPPORTED_AUDIO_MIMES.contains(mime)) {
            return true
        }

        // Query Android system MediaCodecList if available
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val supported = codecList.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { type ->
                    type.equals(mime, ignoreCase = true)
                }
            }
            supported
        } catch (_: Throwable) {
            // In unit tests or restricted environments where MediaCodecList isn't mocked,
            // default to true if it matches an audio/ MIME prefix.
            mime.startsWith("audio/")
        }
    }

    /**
     * Computes an objective perceptual acoustic fidelity score for a candidate.
     *
     * Principles:
     * - Opus achieves fullband 20kHz transparent audio at 48kHz sample rate at ~128–160 kbps,
     *   demonstrably outperforming standard 128 kbps AAC which applies a 15.5kHz lowpass filter.
     * - AAC at ≥256 kbps (e.g. itag 141) achieves top-tier acoustic fidelity.
     * - Both codecs are fairly compared by genuine technical capability rather than arbitrary bias.
     * - Audio-only streams receive priority over muxed video to avoid unnecessary bandwidth & buffering.
     * - User quality tiers (LOW for Data Saver, MEDIUM for cellular balance, HIGH/BEST for maximum fidelity)
     *   are strictly respected.
     */
    fun computeCandidateScore(candidate: AudioStreamCandidate, requestedQuality: StreamQuality): Long {
        val isOpus = candidate.isOpus()
        val isAac = candidate.isAac()
        val isAacHe = candidate.codec.equals("AAC-HE", true)
        val bitrate = candidate.bitrateKbps
        val isAudioOnly = candidate.isAudioOnly

        return when (requestedQuality) {
            StreamQuality.LOW -> {
                // Data Saver: prefer low bitrate streams (~50-70 kbps)
                val distance = kotlin.math.abs(bitrate - requestedQuality.bitrate)
                val base = (10_000L - distance * 100L).coerceAtLeast(0L)
                val codecBonus = if (isOpus) 500L else if (isAacHe) 300L else 0L
                val audioBonus = if (isAudioOnly) 5_000L else 0L
                base + codecBonus + audioBonus
            }
            StreamQuality.MEDIUM -> {
                // Balanced: prefer streams closest to 128 kbps
                val distance = kotlin.math.abs(bitrate - requestedQuality.bitrate)
                val base = (10_000L - distance * 100L).coerceAtLeast(0L)
                val codecBonus = if (isOpus) 500L else 0L
                val audioBonus = if (isAudioOnly) 5_000L else 0L
                base + codecBonus + audioBonus
            }
            StreamQuality.HIGH, StreamQuality.BEST -> {
                // Highest available acoustic fidelity
                // Weighting model based on psychoacoustic bit-rate efficiency:
                // - AAC at ≥256 kbps: Top tier (256 * 1250 = 320,000)
                // - Opus at 160 kbps: (160 * 1400 = 224,000) -> Higher than 128k AAC (128 * 1000 = 128,000)
                // - Opus at 128 kbps: (128 * 1400 = 179,200) -> Higher than 128k AAC
                // - AAC at 128 kbps: (128 * 1000 = 128,000)
                val effectiveBitrate = when {
                    isAac && bitrate >= 256 -> (bitrate.toLong() * 1_250L)
                    isOpus && bitrate >= 120 -> (bitrate.toLong() * 1_400L)
                    isOpus -> (bitrate.toLong() * 1_300L)
                    isAac && !isAacHe -> (bitrate.toLong() * 1_000L)
                    isAacHe -> (bitrate.toLong() * 850L)
                    else -> (bitrate.toLong() * 900L)
                }

                val sampleRateBonus = candidate.sampleRate.toLong().coerceAtLeast(0L)
                val channelBonus = if (candidate.channelCount >= 2) 1_000L else 0L
                val audioOnlyBonus = if (isAudioOnly) 50_000L else 0L

                effectiveBitrate + sampleRateBonus + channelBonus + audioOnlyBonus
            }
        }
    }
}
