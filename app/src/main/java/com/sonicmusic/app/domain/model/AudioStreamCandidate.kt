package com.sonicmusic.app.domain.model

import android.net.Uri

/**
 * Technical descriptor of an audio stream candidate from an extraction source
 * (NewPipe Extractor or Innertube client personas).
 *
 * Holds genuine, verified technical properties without fabricated or guessed fields.
 */
data class AudioStreamCandidate(
    val url: String,
    val mimeType: String,
    val itag: Int? = null,
    val codec: String = "",                  // e.g. "OPUS", "AAC-LC", "AAC-HE", "AAC", "FLAC"
    val codecProfile: String? = null,        // e.g. "mp4a.40.2"
    val container: String = "",              // e.g. "WebM", "M4A"
    val bitrate: Long = 0L,                  // bits per second (raw reported)
    val bitrateKbps: Int = 0,                // kilobits per second
    val sampleRate: Int = 0,                 // Hz (0 if unknown / not reported)
    val channelCount: Int = 0,               // channels (0 if unknown / not reported)
    val isAudioOnly: Boolean = true,         // true for adaptive audio streams, false for muxed video
    val contentLength: Long? = null,
    val durationMs: Long? = null,
    val loudnessDb: Double? = null,
    val sourceName: String = "Innertube",    // "NewPipe", "Innertube-IOS", "Innertube-WebRemix", etc.
    val expiresAtMs: Long? = null,
) {
    fun isOpus(): Boolean = codec.equals("OPUS", true)
    fun isAac(): Boolean = codec.contains("AAC", true)
    fun isAacHe(): Boolean = codec.equals("AAC-HE", true) || codec.equals("HE-AAC", true) || codec.contains("HE", true) || codecProfile?.contains("40.5") == true
    fun isFlac(): Boolean = codec.equals("FLAC", true)

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
            codec = codec,
            bitrate = bitrateKbps,
            sampleRate = sampleRate,
            bitDepth = 0, // Lossy streams have no integer bit depth
            qualityTier = if (bitrateKbps > 0) AudioStreamInfo.qualityTierFromStream(codec, bitrateKbps) else qualityTier,
            containerFormat = container,
            channelCount = channelCount,
            isEnhanced = false,
            isLocal = false,
            itag = itag,
            loudnessDb = loudnessDb,
            contentLength = contentLength,
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
