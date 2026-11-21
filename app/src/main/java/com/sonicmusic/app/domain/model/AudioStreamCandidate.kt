package com.sonicmusic.app.domain.model

import android.net.Uri
import com.sonicmusic.app.domain.audio.models.QualityClassification
import com.sonicmusic.app.domain.audio.models.TrackIdentity
import com.sonicmusic.app.domain.audio.models.VerificationStatus

/**
 * Technical descriptor of an audio stream candidate from an extraction source
 * (NewPipe Extractor, Innertube client personas, or local storage).
 *
 * Holds genuine, verified technical properties without fabricated or guessed fields.
 */
data class AudioStreamCandidate(
    val url: String,
    val mimeType: String,
    val itag: Int? = null,
    val codec: String = "",                  // e.g. "OPUS", "AAC-LC", "AAC-HE", "AAC", "FLAC"
    val codecProfile: String? = null,        // e.g. "mp4a.40.2"
    val container: String = "",              // e.g. "WebM", "M4A", "FLAC", "MP3"
    val bitrate: Long = 0L,                  // bits per second (raw reported)
    val bitrateKbps: Int = 0,                // kilobits per second
    val sampleRate: Int = 0,                 // Hz (0 if unknown / not reported)
    val channelCount: Int = 0,               // channels (0 if unknown / not reported)
    val bitDepth: Int = 0,                   // bit depth (e.g. 16, 24; 0 if lossy/unspecified)
    val isAudioOnly: Boolean = true,         // true for adaptive audio streams, false for muxed video
    val contentLength: Long? = null,
    val durationMs: Long? = null,
    val loudnessDb: Double? = null,
    val sourceName: String = "Innertube",    // "NewPipe", "Innertube-IOS", etc.
    val expiresAtMs: Long? = null,
    // Multi-source identity & verification fields
    val sourceType: SourceType = SourceType.INNERTUBE,
    val identityConfidence: Float = 1.0f,    // 0.0–1.0 match confidence
    val licenseType: LicenseType = LicenseType.STREAMING,
    val isVerifiedSource: Boolean = true,
    val originalTitle: String? = null,
    val originalArtist: String? = null,
    val trackIdentity: TrackIdentity? = null,
    val qualityClassification: QualityClassification = QualityClassification.UNKNOWN,
    val verificationStatus: VerificationStatus = VerificationStatus.VERIFIED,
    val provenance: String = "",
) {
    val playbackUri: String get() = url
    val bitrateBps: Long get() = if (bitrate > 0) bitrate else (bitrateKbps.toLong() * 1000L)
    val sampleRateHz: Int get() = sampleRate

    fun isOpus(): Boolean = codec.equals("OPUS", true)
    fun isAac(): Boolean = audioCodec.startsWith("AAC")
    fun isAacHe(): Boolean = audioCodec.startsWith("AAC-HE")

    val audioCodec: String
        get() = AudioStreamInfo.codecFromMimeType(codecProfile?.let { if (it.startsWith("40.")) "mp4a.$it" else it } ?: mimeType)
            .takeUnless { it == "Unknown" }
            ?: AudioStreamInfo.codecFromMimeType(codec).takeUnless { it == "Unknown" }
            ?: codec

    fun isFlac(): Boolean = codec.equals("FLAC", true)
    fun isLossless(): Boolean = isFlac() || codec.equals("ALAC", true) || codec.equals("WAV", true)

    /**
     * Check if the stream URL has expired or is nearing expiration within a safety margin.
     */
    fun isExpired(safetyMarginMs: Long = 60_000L): Boolean {
        val expiry = expiresAtMs ?: extractExpiryFromUrl(url) ?: return false
        return System.currentTimeMillis() + safetyMarginMs >= expiry
    }

    /**
     * Convert this technical candidate into the app's standard AudioStreamInfo model.
     */
    fun toAudioStreamInfo(qualityTier: StreamQuality = StreamQuality.BEST): AudioStreamInfo {
        return AudioStreamInfo(
            codec = audioCodec,
            bitrate = bitrateKbps,
            sampleRate = sampleRate,
            bitDepth = if (bitDepth > 0) bitDepth else if (isFlac()) 16 else 0,
            qualityTier = if (bitrateKbps > 0) AudioStreamInfo.qualityTierFromStream(codec, bitrateKbps) else qualityTier,
            containerFormat = container,
            channelCount = channelCount,
            isEnhanced = false,
            isLocal = sourceType == SourceType.LOCAL_FILE,
            isArchiveSource = false,
            sourceType = sourceType,
            itag = itag,
            loudnessDb = loudnessDb,
            contentLength = contentLength,
            qualityClassification = qualityClassification,
            verificationStatus = verificationStatus
        )
    }

    companion object {
        /**
         * Safely extract the expiration timestamp in epoch milliseconds from a YouTube CDN URL.
         * YouTube CDN URLs contain query parameter `expire=<seconds_since_epoch>`.
         */
        fun extractExpiryFromUrl(url: String): Long? {
            if (url.isBlank()) return null
            val match = Regex("[?&]expire=(\\d+)").find(url)
            if (match != null) {
                val sec = match.groupValues[1].toLongOrNull()
                if (sec != null && sec > 0) return sec * 1000L
            }
            return try {
                val uri = Uri.parse(url)
                val expireSec = uri.getQueryParameter("expire")?.toLongOrNull()
                if (expireSec != null && expireSec > 0) {
                    expireSec * 1000L
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Provenance / source provider of the audio candidate.
 */
enum class SourceType(val displayName: String) {
    INNERTUBE("YouTube Music"),
    LOCAL_FILE("Local Audio")
}

/**
 * Licensing / playback permission type of an audio stream candidate.
 */
enum class LicenseType(val displayName: String) {
    STREAMING("Streaming"),
    PUBLIC_DOMAIN("Public Domain"),
    CREATIVE_COMMONS("Creative Commons"),
    UNKNOWN("Unknown"),
    RESTRICTED("Restricted")
}
