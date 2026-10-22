package com.sonicmusic.app.features.player.audio

import androidx.media3.common.Format
import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.StreamQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playback lifecycle status reported truthfully in diagnostics and UI.
 */
enum class PlaybackStatus(val displayName: String) {
    IDLE("Idle"),
    PREPARING("Preparing"),
    PLAYING("Playing"),
    BUFFERING("Buffering"),
    RECOVERING("Recovering"),
    ERROR("Error"),
    OFFLINE("Offline")
}

/**
 * UI State representing the verified runtime audio quality and playback diagnostics.
 */
data class AudioQualityUiState(
    val songId: String = "",
    val codec: String = "",
    val sourceBitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channelCount: Int? = null,
    val container: String = "",
    val itag: Int? = null,
    val qualityTier: StreamQuality = StreamQuality.BEST,
    val qualitySelectionMode: String = "Automatic (Best Available)",
    val playbackStatus: PlaybackStatus = PlaybackStatus.IDLE,
    val decoderName: String? = null,
    val playingFormatSummary: String? = null,
    val playingSampleRate: Int? = null,
    val playingChannelCount: Int? = null,
    val isLocal: Boolean = false,
    val loudnessDb: Double? = null,
    val contentLength: Long? = null,
    val sourceName: String = "",
    val errorDetails: String? = null,
) {
    val sampleRate: Int? get() = sampleRateHz
    val status: PlaybackStatus get() = playbackStatus
    val statusMessage: String? get() = errorDetails
    val selectionMode: String get() = qualitySelectionMode

    /**
     * Truthful formatted string: e.g. "AAC · 256 kbps · 48 kHz · Stereo" or "Opus · Bitrate unavailable"
     */
    val formattedDescription: String
        get() {
            val parts = mutableListOf<String>()
            if (codec.isNotBlank() && codec != "Unknown") {
                parts.add(codec.uppercase())
            }
            if (sourceBitrateKbps != null && sourceBitrateKbps > 0) {
                parts.add("$sourceBitrateKbps kbps")
            } else {
                parts.add("Bitrate unavailable")
            }
            if (sampleRateHz != null && sampleRateHz > 0) {
                parts.add("${sampleRateHz / 1000f}".removeSuffix(".0") + " kHz")
            }
            if (channelCount != null && channelCount > 0) {
                when (channelCount) {
                    1 -> parts.add("Mono")
                    2 -> parts.add("Stereo")
                    else -> parts.add("${channelCount}ch")
                }
            }
            return if (parts.isEmpty()) "Audio Stream" else parts.joinToString(" · ")
        }

    /**
     * Convert to standard AudioStreamInfo for backward compatibility with existing components.
     */
    fun toAudioStreamInfo(): AudioStreamInfo {
        return AudioStreamInfo(
            codec = codec,
            bitrate = sourceBitrateKbps ?: 0,
            sampleRate = sampleRateHz ?: 0,
            bitDepth = 0,
            qualityTier = qualityTier,
            containerFormat = container,
            channelCount = channelCount ?: 0,
            isEnhanced = false,
            isLocal = isLocal,
            itag = itag,
            loudnessDb = loudnessDb,
            contentLength = contentLength
        )
    }
}

/**
 * Tracks the actual runtime playback diagnostics separately:
 * - Selected stream candidate (from quality selector)
 * - Resolved media format (used to create media source)
 * - Actual playing format (reported by Media3 decoder)
 * - Hardware/Software audio decoder name
 * - Playback status (Preparing, Playing, Buffering, Recovering, Error)
 */
@Singleton
class PlaybackDiagnostics @Inject constructor() {

    private val _uiState = MutableStateFlow<AudioQualityUiState?>(null)
    val uiState: StateFlow<AudioQualityUiState?> = _uiState.asStateFlow()

    private var currentSongId: String = ""
    private var selectedCandidate: AudioStreamCandidate? = null
    private var playingFormat: Format? = null
    private var activeDecoder: String? = null
    private var status: PlaybackStatus = PlaybackStatus.IDLE
    private var lastError: String? = null
    private var isLocalSource: Boolean = false

    @Synchronized
    fun onSongChanged(songId: String, isLocal: Boolean = false) {
        if (songId != currentSongId) {
            currentSongId = songId
            isLocalSource = isLocal
            selectedCandidate = null
            playingFormat = null
            activeDecoder = null
            lastError = null
            status = if (isLocal) PlaybackStatus.PLAYING else PlaybackStatus.PREPARING
            publishState()
        }
    }

    @Synchronized
    fun onCandidateSelected(candidate: AudioStreamInfo) {
        onCandidateSelected(currentSongId, candidate)
    }

    @Synchronized
    fun onCandidateSelected(songId: String, candidate: AudioStreamCandidate) {
        if (songId == currentSongId || currentSongId.isEmpty()) {
            currentSongId = songId
            selectedCandidate = candidate
            isLocalSource = false
            publishState()
        }
    }

    @Synchronized
    fun onCandidateSelected(songId: String, info: AudioStreamInfo) {
        if (songId == currentSongId || currentSongId.isEmpty()) {
            currentSongId = songId
            selectedCandidate = AudioStreamCandidate(
                url = "",
                mimeType = "",
                itag = info.itag,
                codec = info.codec,
                container = info.containerFormat,
                bitrate = (info.bitrate * 1000L),
                bitrateKbps = info.bitrate,
                sampleRate = info.sampleRate,
                channelCount = info.channelCount,
                isAudioOnly = true,
                contentLength = info.contentLength,
                loudnessDb = info.loudnessDb,
                sourceName = if (info.isLocal) "Local" else "Stream"
            )
            isLocalSource = info.isLocal
            publishState()
        }
    }

    @Synchronized
    fun onPlaybackStatusChanged(newStatus: PlaybackStatus, errorMsg: String? = null) {
        status = newStatus
        if (errorMsg != null) {
            lastError = errorMsg
        }
        publishState()
    }

    @Synchronized
    fun onDecoderInitialized(decoderName: String) {
        activeDecoder = decoderName
        publishState()
    }

    @Synchronized
    fun onPlayingFormatChanged(format: Format) {
        playingFormat = format
        publishState()
    }

    @Synchronized
    fun clear(songId: String? = null) {
        if (songId == null || songId == currentSongId) {
            currentSongId = ""
            selectedCandidate = null
            playingFormat = null
            activeDecoder = null
            status = PlaybackStatus.IDLE
            lastError = null
            isLocalSource = false
            _uiState.value = null
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun publishState() {
        if (currentSongId.isBlank()) {
            _uiState.value = null
            return
        }

        val cand = selectedCandidate
        val fmt = playingFormat

        val codec = when {
            cand != null && cand.codec.isNotBlank() && cand.codec != "Unknown" -> cand.codec
            fmt?.sampleMimeType != null -> AudioStreamInfo.codecFromMimeType(fmt.sampleMimeType!!)
            isLocalSource -> "Local Audio"
            else -> ""
        }

        val sourceBitrate = cand?.bitrateKbps?.takeIf { it > 0 }
            ?: (cand?.itag?.let { AudioStreamInfo.nominalBitrateForItag(it) })
            ?: fmt?.bitrate?.takeIf { it > 0 }?.let { Math.round(it / 1000.0).toInt() }

        val sampleRate = cand?.sampleRate?.takeIf { it > 0 }
            ?: (cand?.itag?.let { AudioStreamInfo.nominalSampleRateForItag(it) })
            ?: fmt?.sampleRate?.takeIf { it > 0 }

        val channels = cand?.channelCount?.takeIf { it > 0 }
            ?: fmt?.channelCount?.takeIf { it > 0 } ?: 2

        val container = cand?.container ?: if (isLocalSource) "Local" else ""

        val playingSummary = if (fmt != null) {
            val parts = mutableListOf<String>()
            fmt.sampleMimeType?.let { parts.add(it) }
            if (fmt.sampleRate > 0) parts.add("${fmt.sampleRate}Hz")
            if (fmt.channelCount > 0) parts.add("${fmt.channelCount}ch")
            parts.joinToString(" · ")
        } else null

        val tier = if (sourceBitrate != null && sourceBitrate > 0) {
            AudioStreamInfo.qualityTierFromStream(codec, sourceBitrate)
        } else {
            StreamQuality.BEST
        }

        _uiState.value = AudioQualityUiState(
            songId = currentSongId,
            codec = codec,
            sourceBitrateKbps = sourceBitrate,
            sampleRateHz = sampleRate,
            channelCount = channels,
            container = container,
            itag = cand?.itag,
            qualityTier = tier,
            qualitySelectionMode = "Automatic (Best Available)",
            playbackStatus = status,
            decoderName = activeDecoder,
            playingFormatSummary = playingSummary,
            playingSampleRate = fmt?.sampleRate?.takeIf { it > 0 },
            playingChannelCount = fmt?.channelCount?.takeIf { it > 0 },
            isLocal = isLocalSource,
            loudnessDb = cand?.loudnessDb,
            contentLength = cand?.contentLength,
            sourceName = cand?.sourceName ?: "",
            errorDetails = lastError
        )
    }
}
