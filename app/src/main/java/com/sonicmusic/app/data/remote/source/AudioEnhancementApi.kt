package com.sonicmusic.app.data.remote.source

import com.sonicmusic.app.domain.model.EnhancedStream
import com.sonicmusic.app.domain.model.TranscodeRequest
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit interface for the FFmpeg backend transcoding API.
 * 
 * The backend runs FFmpeg to transcode Opus/WebM audio streams 
 * to M4A/ALAC lossless format for enhanced playback quality.
 */
interface AudioEnhancementApi {
    
    /**
     * Submit a transcoding request to the FFmpeg backend.
     * 
     * @param request Source stream URL and desired output configuration
     * @return Enhanced stream with M4A/ALAC URL and metadata
     */
    @POST("api/v1/transcode")
    suspend fun transcode(@Body request: TranscodeRequest): EnhancedStream
}
