package com.sonicmusic.app.features.player.audio

import android.util.Log
import androidx.media3.common.Format
import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.LicenseType
import com.sonicmusic.app.domain.model.SourceType
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.audio.models.QualityClassification
import com.sonicmusic.app.domain.audio.models.VerificationStatus
import com.sonicmusic.app.domain.audio.models.SelectedPlaybackInfo
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
    val sourceType: SourceType = SourceType.INNERTUBE,
    val identityConfidence: Float = 1.0f,
    val licenseType: LicenseType = LicenseType.STREAMING,
    val qualityClassification: QualityClassification = QualityClassification.UNKNOWN,
    val verificationStatus: VerificationStatus = VerificationStatus.VERIFIED,
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
            isArchiveSource = false,
            sourceType = sourceType,
            itag = itag,
            loudnessDb = loudnessDb,
            contentLength = contentLength,
            qualityClassification = qualityClassification,
            verificationStatus = verificationStatus
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

    private val resolvedStreams = android.util.LruCache<Pair<String, String>, AudioStreamInfo>(128)

    fun rememberResolvedStream(songId: String, url: String, info: AudioStreamInfo) {
        resolvedStreams.put(songId to url, info)
    }

    fun resolvedStreamInfo(songId: String, url: String): AudioStreamInfo? = resolvedStreams.get(songId to url)

    @Synchronized
    fun onRendererFormat(songId: String, url: String, format: Format) {
        onSongChanged(songId)
        val expected = resolvedStreamInfo(songId, url)
        if (expected != null) onCandidateSelected(songId, expected)
        onPlayingFormatChanged(format)

        val actualCodec = AudioStreamInfo.codecFromMimeType(format.codecs ?: format.sampleMimeType.orEmpty())
        val bitrate = format.averageBitrate.takeIf { it > 0 } ?: format.peakBitrate.takeIf { it > 0 }
        val urlItag = ITAG.find(url)?.groupValues?.get(1)?.toIntOrNull()
        val details = "song=$songId selectedItag=${expected?.itag} playingUrlItag=$urlItag " +
            "selectedCodec=${expected?.codec} rendererCodec=$actualCodec profile=${format.codecs} " +
            "selectedKbps=${expected?.bitrate} rendererBps=$bitrate " +
            "sampleRate=${format.sampleRate} channels=${format.channelCount}"
        val codecMismatch = expected != null && actualCodec != "Unknown" &&
            actualCodec.substringBefore("-") != expected.codec.substringBefore("-")
        val lowerSampleRate = expected != null && format.sampleRate > 0 && format.sampleRate < expected.sampleRate
        val fewerChannels = expected != null && format.channelCount > 0 && format.channelCount < expected.channelCount
        if (codecMismatch || lowerSampleRate || fewerChannels ||
            (expected?.itag != null && urlItag != null && expected.itag != urlItag)
        ) {
            Log.w("SonicAudioFormat", "Unexpected playback format: $details")
        } else {
            Log.i("SonicAudioFormat", details)
        }
    }

    private companion object {
        val ITAG = Regex("[?&]itag=(\\d+)")
    }

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
    fun onSelectedPlaybackInfo(info: SelectedPlaybackInfo) {
        if (info.songId == currentSongId || currentSongId.isEmpty()) {
            currentSongId = info.songId
            selectedCandidate = info.candidate ?: AudioStreamCandidate(
                url = info.playbackUri,
                mimeType = "",
                codec = info.codec,
                container = info.container,
                bitrate = (info.bitrateKbps ?: 0) * 1000L,
                bitrateKbps = info.bitrateKbps ?: 0,
                sampleRate = info.sampleRateHz ?: 0,
                bitDepth = info.bitDepth ?: 0,
                channelCount = info.channelCount,
                isAudioOnly = true,
                sourceName = info.sourceName,
                sourceType = info.selectedSource,
                identityConfidence = info.identityConfidence,
                licenseType = info.licenseType,
                qualityClassification = info.qualityClassification,
                verificationStatus = info.verificationStatus
            )
            isLocalSource = info.isLocal
            publishState()
        }
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
                sourceName = if (info.isLocal) "Local" else "Stream",
                sourceType = info.sourceType
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
            fmt?.sampleMimeType != null -> AudioStreamInfo.codecFromMimeType(fmt.codecs ?: fmt.sampleMimeType!!)
            cand != null && cand.codec.isNotBlank() && cand.codec != "Unknown" -> cand.codec
            isLocalSource -> "Local Audio"
            else -> ""
        }

        val sourceBitrate = cand?.bitrateKbps?.takeIf { it > 0 }
            ?: fmt?.bitrate?.takeIf { it > 0 }?.let { Math.round(it / 1000.0).toInt() }

        val sampleRate = cand?.sampleRate?.takeIf { it > 0 }
            ?: fmt?.sampleRate?.takeIf { it > 0 }

        val channels = cand?.channelCount?.takeIf { it > 0 }
            ?: fmt?.channelCount?.takeIf { it > 0 }

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
            errorDetails = lastError,
            sourceType = cand?.sourceType ?: if (isLocalSource) SourceType.LOCAL_FILE else SourceType.INNERTUBE,
            identityConfidence = cand?.identityConfidence ?: 1.0f,
            licenseType = cand?.licenseType ?: LicenseType.STREAMING,
            qualityClassification = cand?.qualityClassification ?: if (cand?.isFlac() == true) QualityClassification.VERIFIED_LOSSLESS else QualityClassification.UNKNOWN,
            verificationStatus = cand?.verificationStatus ?: VerificationStatus.VERIFIED
        )
    }
}
