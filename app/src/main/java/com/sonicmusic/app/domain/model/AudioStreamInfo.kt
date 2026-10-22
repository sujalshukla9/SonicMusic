package com.sonicmusic.app.domain.model

/**
 * Rich metadata about the currently playing audio stream.
 * 
 * Inspired by Apple Music-style quality badges.
 * Tracks codec, bitrate, sample rate, bit depth, and quality tier.
 */
data class AudioStreamInfo(
    val codec: String = "",
    val bitrate: Int = 0,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val qualityTier: StreamQuality = StreamQuality.BEST,
    val containerFormat: String = "",
    val channelCount: Int = 0,
    val isEnhanced: Boolean = false,
    val isLocal: Boolean = false,
    val itag: Int? = null,
    val loudnessDb: Double? = null,
    val contentLength: Long? = null,
) {
    /**
     * Whether this stream qualifies as genuine high-res audio.
     * Only verified lossless uncompressed/losslessly-compressed formats (FLAC, ALAC, WAV)
     * exceeding CD standard (sample rate > 48kHz or bit depth > 16-bit) qualify as Hi-Res.
     * Lossy streams (YouTube AAC/Opus) never qualify as Hi-Res.
     */
    val isHighRes: Boolean
        get() = (codec.equals("FLAC", true) || codec.equals("ALAC", true) || codec.equals("WAV", true)) &&
                (sampleRate > 48000 || bitDepth > 16)

    /**
     * Human-readable quality badge.
     * Displays only verified properties (e.g. "OPUS 128", "AAC 130", "AAC 256", "Lossless FLAC").
     * Never assigns fake bitrate values or labels lossy streams as Hi-Res or lossless.
     */
    val qualityBadge: String
        get() = when {
            isEnhanced && isHighRes -> "Enhanced · Hi-Res"
            isEnhanced -> "Enhanced"
            isLocal -> if (codec.isNotBlank() && codec != "Unknown") "Local · ${codec.uppercase()}" else "Local"
            codec.equals("FLAC", true) -> if (isHighRes) "Hi-Res Lossless" else "Lossless FLAC"
            codec.equals("ALAC", true) -> if (isHighRes) "Hi-Res Lossless" else "Lossless ALAC"
            codec.equals("WAV", true) -> if (isHighRes) "Hi-Res WAV" else "WAV"
            codec.equals("OPUS", true) -> if (bitrate > 0) "OPUS $bitrate" else "OPUS"
            codec.equals("AAC-HE", true) -> if (bitrate > 0) "HE-AAC $bitrate" else "HE-AAC"
            codec.contains("AAC", true) -> if (bitrate > 0) "AAC $bitrate" else "AAC"
            codec.isNotBlank() && codec != "Unknown" -> if (bitrate > 0) "${codec.uppercase()} $bitrate" else codec.uppercase()
            bitrate > 0 -> "$bitrate kbps"
            else -> "Audio"
        }

    /**
     * Full quality description for settings/info display.
     * Formats only verified fields, omitting unverified or absent metadata.
     * e.g., "OPUS · 128 kbps · 48 kHz · Stereo"
     */
    val fullDescription: String
        get() {
            val parts = mutableListOf<String>()
            if (codec.isNotBlank() && codec != "Unknown") {
                parts.add(codec.uppercase())
            }
            if (bitrate > 0) {
                parts.add("$bitrate kbps")
            }
            if (sampleRate > 0) {
                parts.add("${sampleRate / 1000f}".removeSuffix(".0") + " kHz")
            }
            if (bitDepth > 0) {
                parts.add("${bitDepth}-bit")
            }
            if (channelCount > 0) {
                when (channelCount) {
                    1 -> parts.add("Mono")
                    2 -> parts.add("Stereo")
                    else -> parts.add("${channelCount}ch")
                }
            }
            if (parts.isEmpty()) {
                return "Audio Stream"
            }
            return parts.joinToString(" · ")
        }

    /**
     * Compact signal path description
     * e.g., "OPUS 128kbps → AudioEngine → DAC"
     */
    fun getSignalPath(outputDevice: String = "Speaker"): String {
        val source = if (bitrate > 0 && codec.isNotBlank() && codec != "Unknown") {
            "${codec.uppercase()} ${bitrate}kbps"
        } else if (codec.isNotBlank() && codec != "Unknown") {
            codec.uppercase()
        } else if (bitrate > 0) {
            "${bitrate}kbps"
        } else {
            "Audio"
        }
        return "$source → AudioEngine → $outputDevice"
    }

    companion object {
        /**
         * Detect codec from MIME type or codec string
         */
        fun codecFromMimeType(mimeType: String): String = when {
            mimeType.contains("opus", true) -> "OPUS"
            mimeType.contains("mp4a.40.2", true) -> "AAC-LC"
            mimeType.contains("mp4a.40.5", true) -> "AAC-HE"
            mimeType.contains("mp4a", true) || mimeType.contains("aac", true) -> "AAC"
            mimeType.contains("flac", true) -> "FLAC"
            mimeType.contains("alac", true) -> "ALAC"
            mimeType.contains("vorbis", true) -> "Vorbis"
            mimeType.contains("mp3", true) || mimeType.contains("mpeg", true) -> "MP3"
            mimeType.contains("wav", true) -> "WAV"
            else -> "Unknown"
        }

        /**
         * Detect container format from MIME type
         */
        fun containerFromMimeType(mimeType: String): String = when {
            mimeType.contains("webm", true) -> "WebM"
            mimeType.contains("mp4", true) || mimeType.contains("m4a", true) -> "M4A"
            mimeType.contains("ogg", true) -> "OGG"
            mimeType.contains("flac", true) -> "FLAC"
            mimeType.contains("wav", true) -> "WAV"
            mimeType.contains("mp3", true) || mimeType.contains("mpeg", true) -> "MP3"
            else -> "Unknown"
        }

        /**
         * Standard YouTube nominal bitrates in kbps for known itags when manifest bitrate is omitted.
         */
        fun nominalBitrateForItag(itag: Int?): Int? = when (itag) {
            251 -> 160
            141, 338 -> 256
            22 -> 192
            140, 171 -> 128
            18 -> 96
            250 -> 70
            249 -> 50
            600 -> 35
            599 -> 32
            else -> null
        }

        /**
         * Standard YouTube sample rates in Hz for known itags when manifest sample rate is omitted.
         */
        fun nominalSampleRateForItag(itag: Int?): Int? = when (itag) {
            251, 250, 249, 600 -> 48000
            141, 140, 171, 338, 22, 18 -> 44100
            599 -> 22050
            else -> null
        }

        /**
         * Determine quality tier from verified stream parameters.
         *
         * AAC ≥256kbps → BEST
         * OPUS ≥128kbps → BEST (48kHz fullband, high acoustic transparency)
         * OPUS ≥96kbps  → HIGH
         * AAC ≥120kbps  → HIGH
         * others        → MEDIUM / LOW by bitrate
         */
        fun qualityTierFromStream(codec: String, bitrate: Int): StreamQuality = when {
            bitrate <= 0 -> StreamQuality.BEST
            codec.contains("AAC", true) && bitrate >= 256 -> StreamQuality.BEST
            codec.equals("OPUS", true) && bitrate >= 125 -> StreamQuality.BEST
            codec.equals("OPUS", true) && bitrate >= 96 -> StreamQuality.HIGH
            codec.contains("AAC", true) && bitrate >= 120 -> StreamQuality.HIGH
            codec.equals("AAC-HE", true) && bitrate >= 64 -> StreamQuality.HIGH
            bitrate >= 200 -> StreamQuality.BEST
            bitrate >= 120 -> StreamQuality.HIGH
            bitrate >= 80 -> StreamQuality.MEDIUM
            else -> StreamQuality.LOW
        }
    }
}
