package com.sonicmusic.app.domain.model

import com.google.gson.annotations.SerializedName

/**
 * Response from the FFmpeg backend transcoding API.
 * 
 * The backend accepts an Opus/WebM stream URL and returns a transcoded 
 * M4A/ALAC lossless stream URL with enhanced audio metadata.
 */
data class EnhancedStream(
    @SerializedName("enhanced_url")
    val enhancedUrl: String,
    
    @SerializedName("codec")
    val codec: String = "ALAC",
    
    @SerializedName("bitrate")
    val bitrate: Int = 1411,
    
    @SerializedName("sample_rate")
    val sampleRate: Int = 44100,
    
    @SerializedName("bit_depth")
    val bitDepth: Int = 16,
    
    @SerializedName("expires_at")
    val expiresAt: Long = 0,
    
    @SerializedName("container")
    val container: String = "M4A",
)

/**
 * Request body sent to the FFmpeg backend API.
 */
data class TranscodeRequest(
    @SerializedName("source_url")
    val sourceUrl: String,
    
    @SerializedName("output_format")
    val outputFormat: String = "m4a",
    
    @SerializedName("codec")
    val codec: String = "alac",
    
    @SerializedName("quality")
    val quality: String = "lossless",
)
