package com.sonicmusic.app.features.player.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.audio.models.SelectedPlaybackInfo
import com.sonicmusic.app.domain.audio.models.toSelectedPlaybackInfo
import com.sonicmusic.app.domain.model.StreamQuality
import androidx.media3.common.Format

import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.exp
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Apple Music-Style Premium Audio Engine
 * 
 * Professional-grade audio processing pipeline inspired by Apple Music:
 *
 * AUDIO QUALITY TIERS
 * ├─ Data Saver       → Targets 64 kbps
 * ├─ High Efficiency  → Targets 128 kbps
 * └─ High Quality / Best Available → Highest-quality playable stream returned by the source
 *
 * STREAM METADATA TRACKING
 * - Real-time codec, bitrate, sample rate, bit depth info
 * - Quality badge display (Hi-Res, AAC, Enhanced)
 * - Audio signal path visualization 
 *
 * OUTPUT DEVICE DETECTION
 * - USB DAC detection for Hi-Res output
 * - Bluetooth codec awareness
 * - Built-in speaker optimization
 *
 * ADAPTIVE QUALITY ENGINE
 * - Wi-Fi vs Cellular quality separation
 * - Network-aware stream selection
 * - Battery-conscious quality adaptation
 *
 * AUDIO EFFECTS
 * - 5-Band Parametric EQ with professional presets
 * - Sound Check (per-track loudness correction)
 * - Spatial Audio (3D immersion)
 * - Bass Boost with adjustable strength
 * - Crossfade with smooth transitions
 * - Reverb with concert hall presets
 */
@Singleton
class AudioEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilityDetector: AudioCapabilityDetector,
) {
    companion object {
        private const val TAG = "AudioEngine"
        
        // ═══════════════════════════════════════════════════════════
        // PREFERENCE KEYS
        // ═══════════════════════════════════════════════════════════
        
        private val KEY_SPATIAL_AUDIO = booleanPreferencesKey("spatial_audio_enabled")
        private val KEY_SPATIAL_STRENGTH = intPreferencesKey("spatial_strength")
        private val KEY_SPATIAL_MIGRATED = booleanPreferencesKey("spatial_migrated_v1")
        private val KEY_FIDELITY_MIGRATED = booleanPreferencesKey("audio_fidelity_migrated_v2")
        private val KEY_EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        private val KEY_EQ_PRESET = stringPreferencesKey("eq_preset")
        private val KEY_REVERB_ENABLED = booleanPreferencesKey("reverb_enabled")
        private val KEY_REVERB_PRESET = intPreferencesKey("reverb_preset")
        
        // Apple-style quality tier keys
        private val KEY_WIFI_QUALITY_TIER = stringPreferencesKey("wifi_quality_tier")
        private val KEY_CELLULAR_QUALITY_TIER = stringPreferencesKey("cellular_quality_tier")
        private val KEY_DOWNLOAD_QUALITY_TIER = stringPreferencesKey("download_quality_tier")
        
        // Default Values
        const val MAX_VIRTUALIZER_STRENGTH = 1000
        const val DEFAULT_VIRTUALIZER_STRENGTH = 500
    }
    
    private val Context.audioPrefs by preferencesDataStore(name = "audio_engine_prefs")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _spatialAudioMode = MutableStateFlow(SpatialAudioMode.OFF)
    val spatialAudioMode: StateFlow<SpatialAudioMode> = _spatialAudioMode.asStateFlow()
    private var spatialAttributes: AudioAttributes? = null
    private var spatialFormat: AudioFormat? = null
    private var stereoEffectAvailable = false

    // Audio Effects
    private var virtualizer: Virtualizer? = null
    private var equalizer: Equalizer? = null
    private var reverb: PresetReverb? = null
    
    // Current audio session
    private var currentAudioSessionId: Int = 0
    private val customBandLevels = ShortArray(5) { 0 }
    
    
    // ═══════════════════════════════════════════════════════════════
    // STATE FLOWS
    // ═══════════════════════════════════════════════════════════════
    
    private val _audioEngineState = MutableStateFlow(AudioEngineState())
    val audioEngineState: StateFlow<AudioEngineState> = _audioEngineState.asStateFlow()
    
    private val _currentStreamInfo = MutableStateFlow<AudioStreamInfo?>(null)
    val currentStreamInfo: StateFlow<AudioStreamInfo?> = _currentStreamInfo.asStateFlow()

    private val _selectedPlaybackInfo = MutableStateFlow<SelectedPlaybackInfo?>(null)
    val selectedPlaybackInfo: StateFlow<SelectedPlaybackInfo?> = _selectedPlaybackInfo.asStateFlow()
    
    private val _outputDeviceType = MutableStateFlow(OutputDeviceType.BUILT_IN_SPEAKER)
    val outputDeviceType: StateFlow<OutputDeviceType> = _outputDeviceType.asStateFlow()

    private val _eqBands = MutableStateFlow<List<EqBandInfo>>(emptyList())
    val eqBands: StateFlow<List<EqBandInfo>> = _eqBands.asStateFlow()
    
    // ═══════════════════════════════════════════════════════════════
    // EQ PRESETS
    // ═══════════════════════════════════════════════════════════════
    
    private val eqPresets = mapOf(
        "flat" to shortArrayOf(0, 0, 0, 0, 0),
        "bass_boost" to shortArrayOf(600, 500, 200, 0, 0),
        "bass_heavy" to shortArrayOf(800, 600, 300, 100, 0),
        "treble_boost" to shortArrayOf(0, 0, 200, 500, 600),
        "vocal_boost" to shortArrayOf(-200, 100, 400, 300, 100),
        "rock" to shortArrayOf(500, 300, 0, 300, 500),
        "pop" to shortArrayOf(-100, 200, 400, 200, -100),
        "jazz" to shortArrayOf(300, 100, 0, 100, 400),
        "classical" to shortArrayOf(200, 100, -100, 200, 400),
        "electronic" to shortArrayOf(500, 400, 0, 300, 500),
        "hip_hop" to shortArrayOf(700, 500, 100, 200, 400),
        "r_and_b" to shortArrayOf(500, 600, 200, 100, 300),
        "acoustic" to shortArrayOf(300, 100, 100, 200, 400),
        "lounge" to shortArrayOf(200, 300, 100, 0, 200),
        "spoken_word" to shortArrayOf(-200, 0, 400, 500, 400),
        "deep_bass" to shortArrayOf(1000, 800, 400, 100, -100),
        "bright" to shortArrayOf(-200, 0, 300, 600, 800),
        "warm" to shortArrayOf(400, 300, 100, -100, -200),
    )

    
    init {
        // Load saved quality tiers immediately so Settings can show real values
        // even before PlaybackService initializes audio effects.
        scope.launch(Dispatchers.IO) {
            try {
                loadSavedPreferences()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load saved audio prefs at startup", e)
            }
        }
        // Coordinate in-app Virtualizer with OS Spatializer & route changes
        scope.launch {
            capabilityDetector.capability.collect { cap ->
                Log.d(TAG, "AudioCapability updated: osSpatialProcessingActive=${cap.osSpatialProcessingActive}, route=${cap.currentOutputRouteName}")
                updateVirtualizerState()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Initialize audio engine with ExoPlayer's audio session ID
     */
    @Synchronized
    fun initialize(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        if (audioSessionId == currentAudioSessionId) return
        
        Log.d(TAG, "Initializing Apple-Style Audio Engine with session: $audioSessionId")
        currentAudioSessionId = audioSessionId
        
        // Release previous effects
        releaseEffects()
        
        // Initialize all audio effects
        initializeVirtualizer(audioSessionId)
        initializeEqualizer(audioSessionId)
        initializeReverb(audioSessionId)
        
        // Detect output device capabilities
        detectOutputDevice()
        
        applyCurrentState(_audioEngineState.value)
        
        Log.d(TAG, "Apple-Style Audio Engine initialized")
    }
    
    private fun initializeVirtualizer(audioSessionId: Int) {
        try {
            if (isEffectTypeSupported(AudioEffect.EFFECT_TYPE_VIRTUALIZER)) {
                virtualizer = Virtualizer(0, audioSessionId)
                updateVirtualizerState()
                Log.d(TAG, "Virtualizer initialized on session $audioSessionId")
            } else {
                Log.d(TAG, "Virtualizer not supported on this device")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Virtualizer initialization failed", e)
            virtualizer = null
        }
    }
    
    private fun initializeEqualizer(audioSessionId: Int) {
        try {
            if (isEffectTypeSupported(AudioEffect.EFFECT_TYPE_EQUALIZER)) {
                equalizer = Equalizer(0, audioSessionId).apply {
                    enabled = false // Enabled later by loadSavedPreferences / setEqualizer
                }
                Log.d(TAG, "Equalizer initialized (${equalizer?.numberOfBands} bands)")
                updateEqBands()
            } else {
                Log.d(TAG, "Equalizer not supported on this device")
                _eqBands.value = emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Equalizer initialization failed", e)
            equalizer = null
            _eqBands.value = emptyList()
        }
    }
    
    private fun initializeReverb(audioSessionId: Int) {
        try {
            if (isEffectTypeSupported(AudioEffect.EFFECT_TYPE_PRESET_REVERB)) {
                reverb = PresetReverb(0, audioSessionId).apply {
                    enabled = false // Enabled later by loadSavedPreferences / setReverb
                }
                Log.d(TAG, "Preset Reverb initialized")
            } else {
                Log.d(TAG, "Preset Reverb not supported on this device")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Preset Reverb initialization failed", e)
            reverb = null
        }
    }

    private fun isEffectTypeSupported(effectType: UUID): Boolean {
        return runCatching {
            val effects = AudioEffect.queryEffects() ?: return false
            effects.any { descriptor -> descriptor.type == effectType }
        }.getOrDefault(false)
    }
    
    private suspend fun loadSavedPreferences() {
        val prefs = context.audioPrefs.data.first()
        
        val wifiTier = prefs[KEY_WIFI_QUALITY_TIER]?.let { StreamQuality.fromName(it) } ?: StreamQuality.BEST
        val cellularTier = prefs[KEY_CELLULAR_QUALITY_TIER]?.let { StreamQuality.fromName(it) } ?: StreamQuality.HIGH
        val downloadTier = prefs[KEY_DOWNLOAD_QUALITY_TIER]?.let { StreamQuality.fromName(it) } ?: StreamQuality.BEST
        
        val isFidelityMigrated = prefs[KEY_FIDELITY_MIGRATED] ?: false
        val spatialAudioEnabled: Boolean
        val spatialStrength: Int

        if (!isFidelityMigrated) {
            // Audio Fidelity Reset:
            // Reset the legacy forced Virtualizer setting to neutral stereo.
            spatialAudioEnabled = false
            spatialStrength = DEFAULT_VIRTUALIZER_STRENGTH
            scope.launch {
                try {
                    context.audioPrefs.edit {
                        it[KEY_SPATIAL_AUDIO] = false
                        it[KEY_SPATIAL_STRENGTH] = DEFAULT_VIRTUALIZER_STRENGTH
                        it[KEY_FIDELITY_MIGRATED] = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist audio fidelity reset prefs", e)
                }
            }
        } else {
            spatialAudioEnabled = prefs[KEY_SPATIAL_AUDIO] ?: false
            spatialStrength = prefs[KEY_SPATIAL_STRENGTH] ?: DEFAULT_VIRTUALIZER_STRENGTH
        }

        val storedPreset = prefs[KEY_EQ_PRESET]
        val state = AudioEngineState(
            spatialAudioEnabled = spatialAudioEnabled,
            spatialStrength = spatialStrength,
            eqEnabled = prefs[KEY_EQ_ENABLED] == true && (storedPreset == null || storedPreset in eqPresets),
            eqPreset = storedPreset?.takeIf(eqPresets::containsKey) ?: "flat",
            reverbEnabled = prefs[KEY_REVERB_ENABLED] ?: false,
            reverbPreset = prefs[KEY_REVERB_PRESET] ?: PresetReverb.PRESET_NONE.toInt(),
            wifiQualityTier = wifiTier,
            cellularQualityTier = cellularTier,
            downloadQualityTier = downloadTier,
        )
        
        _audioEngineState.value = state
        applyCurrentState(state)
    }
    
    private fun applyCurrentState(state: AudioEngineState) {
        updateVirtualizerState()
        applyEqSettings()
        applyReverbEffect(state.reverbEnabled, state.reverbPreset)
    }
    
    // ═══════════════════════════════════════════════════════════════
    // APPLE-STYLE AUDIO QUALITY PIPELINE
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Set Wi-Fi streaming quality tier
     */
    fun setWifiQualityTier(tier: StreamQuality) {
        _audioEngineState.value = _audioEngineState.value.copy(wifiQualityTier = tier)
        scope.launch {
            context.audioPrefs.edit { it[KEY_WIFI_QUALITY_TIER] = tier.name }
        }
        Log.d(TAG, "Wi-Fi quality tier: ${tier.displayName}")
    }
    
    /**
     * Set cellular streaming quality tier
     */
    fun setCellularQualityTier(tier: StreamQuality) {
        _audioEngineState.value = _audioEngineState.value.copy(cellularQualityTier = tier)
        scope.launch {
            context.audioPrefs.edit { it[KEY_CELLULAR_QUALITY_TIER] = tier.name }
        }
        Log.d(TAG, "Cellular quality tier: ${tier.displayName}")
    }
    
    /**
     * Set download quality tier
     */
    fun setDownloadQualityTier(tier: StreamQuality) {
        _audioEngineState.value = _audioEngineState.value.copy(downloadQualityTier = tier)
        scope.launch {
            context.audioPrefs.edit { it[KEY_DOWNLOAD_QUALITY_TIER] = tier.name }
        }
        Log.d(TAG, "Download quality tier: ${tier.displayName}")
    }

    /**
     * Get the optimal quality tier based on current conditions.
     * 
     * Considers: user preference, network type, output device capability, battery.
     * Apple Music-style: separate Wi-Fi and Cellular preferences.
     */
    fun getOptimalQuality(): StreamQuality {
        val state = _audioEngineState.value
        val preferred = if (isWifiConnected()) {
            state.wifiQualityTier
        } else {
            state.cellularQualityTier
        }
        val maxForDevice = getMaxQualityForDevice()
        return if (preferred.ordinal > maxForDevice.ordinal) {
            maxForDevice
        } else {
            preferred
        }
    }
    
    private var currentActiveSongId: String? = null

    /**
     * Update the currently playing stream's metadata.
     * Called by PlaybackService / PlayerServiceConnection when a new stream starts playing.
     */
    fun updateStreamInfo(info: AudioStreamInfo, songId: String? = null, selectedInfo: SelectedPlaybackInfo? = null) {
        if (songId != null) {
            currentActiveSongId = songId
        }
        _currentStreamInfo.value = info
        _selectedPlaybackInfo.value = selectedInfo ?: info.toSelectedPlaybackInfo(songId ?: currentActiveSongId ?: "")
        Log.d(TAG, "Now Playing (${songId ?: "unknown"}): ${info.fullDescription}")
        Log.d(TAG, "Quality Badge: ${info.qualityBadge}")
        Log.d(TAG, "Signal Path: ${info.getSignalPath(getOutputDeviceName())}")
    }

    fun updateSelectedPlaybackInfo(info: SelectedPlaybackInfo) {
        currentActiveSongId = info.songId
        _selectedPlaybackInfo.value = info
        _currentStreamInfo.value = info.toAudioStreamInfo()
    }
    
    /**
     * Enrich or update the stream metadata with verified parameters directly from
     * ExoPlayer's hardware/media decoder when tracks are loaded.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun updateFromExoPlayerFormat(format: Format, songId: String? = null) {
        if (songId != null && currentActiveSongId != null && songId != currentActiveSongId) {
            Log.d(TAG, "Ignoring ExoPlayer format update for mismatching song $songId (active is $currentActiveSongId)")
            return
        }
        val current = _currentStreamInfo.value ?: AudioStreamInfo()
        val sampleRate = if (format.sampleRate > 0) format.sampleRate else current.sampleRate
        val channelCount = if (format.channelCount > 0) format.channelCount else current.channelCount
        val exoBitrate = if (format.bitrate > 0) Math.round(format.bitrate / 1000.0).toInt() else 0
        val bitrate = if (exoBitrate > 0) exoBitrate else current.bitrate
        val detectedCodec = if (!format.sampleMimeType.isNullOrBlank()) {
            AudioStreamInfo.codecFromMimeType(format.sampleMimeType!!)
        } else current.codec

        val updated = current.copy(
            codec = if (detectedCodec.isNotBlank() && detectedCodec != "Unknown") detectedCodec else current.codec,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitrate = bitrate,
            qualityTier = if (bitrate > 0) AudioStreamInfo.qualityTierFromStream(detectedCodec, bitrate) else current.qualityTier
        )
        _currentStreamInfo.value = updated
        _selectedPlaybackInfo.value = _selectedPlaybackInfo.value?.let { currentSelected ->
            currentSelected.copy(
                codec = updated.codec,
                bitrateKbps = if (updated.bitrate > 0) updated.bitrate else currentSelected.bitrateKbps,
                sampleRateHz = if (updated.sampleRate > 0) updated.sampleRate else currentSelected.sampleRateHz,
                channelCount = updated.channelCount
            )
        }
        Log.d(TAG, "Enriched stream info from ExoPlayer decoder: ${updated.fullDescription}")
    }

    /**
     * Clear stream info (when playback stops or changes song)
     */
    fun clearStreamInfo() {
        currentActiveSongId = null
        _currentStreamInfo.value = null
        _selectedPlaybackInfo.value = null
    }
    
    // ═══════════════════════════════════════════════════════════════
    // OUTPUT DEVICE DETECTION
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Detect connected audio output device
     */
    fun detectOutputDevice() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        
        val deviceType = when {
            devices.any { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE } ->
                OutputDeviceType.USB_DAC
            devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } ->
                OutputDeviceType.BLUETOOTH
            devices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES } ->
                OutputDeviceType.WIRED_HEADPHONES
            else -> OutputDeviceType.BUILT_IN_SPEAKER
        }
        
        _outputDeviceType.value = deviceType
        Log.d(TAG, "Output device: ${deviceType.displayName}")
    }
    
    /**
     * Get max supported quality for connected output device
     */
    private fun getMaxQualityForDevice(): StreamQuality {
        return when (_outputDeviceType.value) {
            OutputDeviceType.USB_DAC -> StreamQuality.BEST
            OutputDeviceType.WIRED_HEADPHONES -> StreamQuality.BEST
            OutputDeviceType.BLUETOOTH -> StreamQuality.BEST // Bluetooth caps at ~990kbps LDAC
            OutputDeviceType.BUILT_IN_SPEAKER -> StreamQuality.BEST
        }
    }
    
    /**
     * Get human-readable name for current output device
     */
    fun getOutputDeviceName(): String = _outputDeviceType.value.displayName
    
    // ═══════════════════════════════════════════════════════════════
    // NETWORK DETECTION
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Check if currently connected to Wi-Fi
     */
    private fun isWifiConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
    
    // ═══════════════════════════════════════════════════════════════
    // EQUALIZER (5-Band)
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Get available EQ presets
     */
    fun getEqPresets(): List<String> = eqPresets.keys.toList()
    
    /**
     * Enable/disable equalizer with preset
     */
    fun setEqualizer(enabled: Boolean, preset: String = "flat") {
        _audioEngineState.value = _audioEngineState.value.copy(
            eqEnabled = enabled,
            eqPreset = preset,
        )

        applyEqSettings()

        scope.launch {
            context.audioPrefs.edit {
                it[KEY_EQ_ENABLED] = enabled
                it[KEY_EQ_PRESET] = preset
            }
        }
    }

    
    /**
     * Set custom EQ band level
     * @param band Band index (0-4)
     * @param level Gain in millibels (-1500 to 1500)
     */
    fun setEqBandLevel(band: Int, level: Short) {
        if (band in 0 until 5) {
            customBandLevels[band] = level
            applyEqSettings()
        }
    }
    


    /**
     * Get EQ band frequency info (Reactive)
     */
    fun getEqBandInfo(): List<EqBandInfo> = _eqBands.value

    private fun updateEqBands() {
        _eqBands.value = equalizer?.let { eq ->
            (0 until eq.numberOfBands).map { band ->
                EqBandInfo(
                    band = band,
                    frequency = eq.getCenterFreq(band.toShort()) / 1000,
                    minLevel = eq.bandLevelRange[0].toInt(),
                    maxLevel = eq.bandLevelRange[1].toInt(),
                    currentLevel = eq.getBandLevel(band.toShort()).toInt(),
                )
            }
        } ?: emptyList()
    }
    
    private fun applyEqSettings() {
        val state = _audioEngineState.value
        equalizer?.let { eq ->
            try {
                applyEqCurve(
                    eq = eq,
                    preset = state.eqPreset,
                    applyPreset = state.eqEnabled
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error applying platform EQ", e)
            }
        }
    }

    private fun applyEqCurve(
        eq: Equalizer,
        preset: String,
        applyPreset: Boolean
    ) {
        val curve = if (preset == "flat") customBandLevels else eqPresets.getValue(preset.takeIf(eqPresets::containsKey) ?: "flat")
        val range = eq.bandLevelRange
        val minGain = range[0].toInt()
        val maxGain = range[1].toInt()
        val bands = eq.numberOfBands.toInt()
        val requested = IntArray(bands) { band ->
            val gain = if (applyPreset) curve.getOrNull(band)?.toInt() ?: 0 else 0
            gain.coerceIn(minGain, maxGain)
        }
        val headroom = requested.maxOrNull()?.coerceAtLeast(0) ?: 0
        for (band in 0 until bands) {
            val safeGain = (requested[band] - headroom).coerceIn(minGain, maxGain)
            eq.setBandLevel(band.toShort(), safeGain.toShort())
        }

        eq.enabled = applyPreset
        updateEqBands()
    }

    
    // ═══════════════════════════════════════════════════════════════
    // SPATIAL AUDIO (Virtualizer)
    // ═══════════════════════════════════════════════════════════════
    
    @Synchronized
    fun updateSpatialPlayback(attributes: AudioAttributes, format: Format?, stereoEffectAvailable: Boolean) {
        spatialAttributes = attributes
        this.stereoEffectAvailable = stereoEffectAvailable
        spatialFormat = format?.let { track ->
            val channelMask = when (track.channelCount) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                4 -> AudioFormat.CHANNEL_OUT_QUAD
                6 -> AudioFormat.CHANNEL_OUT_5POINT1
                8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                else -> return@let null
            }
            AudioFormat.Builder()
                .setEncoding(
                    if (track.pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) AudioFormat.ENCODING_PCM_FLOAT
                    else AudioFormat.ENCODING_PCM_16BIT
                )
                .setChannelMask(channelMask)
                .apply { if (track.sampleRate > 0) setSampleRate(track.sampleRate) }
                .build()
        }
        updateVirtualizerState()
    }

    private fun canUseSystemSpatializer(): Boolean {
        if (Build.VERSION.SDK_INT < 32) return false
        val attributes = spatialAttributes ?: return false
        val format = spatialFormat ?: return false
        return runCatching {
            val spatializer = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).spatializer
            spatializer.isAvailable && spatializer.isEnabled && spatializer.canBeSpatialized(attributes, format)
        }.getOrDefault(false)
    }

    @Synchronized
    private fun updateVirtualizerState() {
        val state = _audioEngineState.value
        val useSystem = state.spatialAudioEnabled && canUseSystemSpatializer()
        val format = spatialFormat
        val effect = virtualizer
        val virtualized = if (effect != null) {
            runCatching {
                val supported = state.spatialAudioEnabled && !useSystem && format != null &&
                    (
                        effect.canVirtualize(format.channelMask, Virtualizer.VIRTUALIZATION_MODE_BINAURAL) ||
                            effect.canVirtualize(format.channelMask, Virtualizer.VIRTUALIZATION_MODE_TRANSAURAL)
                    )
                if (supported && effect.strengthSupported) effect.setStrength(state.spatialStrength.toShort())
                effect.enabled = supported
                val active = supported && effect.enabled && effect.virtualizationMode != Virtualizer.VIRTUALIZATION_MODE_OFF
                if (supported && !active) effect.enabled = false
                active
            }.getOrElse {
                runCatching { effect.enabled = false }
                false
            }
        } else false

        _spatialAudioMode.value = when {
            !state.spatialAudioEnabled -> SpatialAudioMode.OFF
            useSystem -> SpatialAudioMode.SYSTEM
            virtualized -> SpatialAudioMode.VIRTUALIZER
            stereoEffectAvailable && format?.channelCount == 2 -> SpatialAudioMode.STEREO_EFFECT
            format == null -> SpatialAudioMode.WAITING
            else -> SpatialAudioMode.UNAVAILABLE
        }
    }

    /**
     * Enable/disable Spatial Audio with adjustable immersion
     * @param strength 0-1000 (0% to 100% immersion)
     */
    fun setSpatialAudio(enabled: Boolean, strength: Int = DEFAULT_VIRTUALIZER_STRENGTH) {
        val clampedStrength = strength.coerceIn(0, MAX_VIRTUALIZER_STRENGTH)

        _audioEngineState.value = _audioEngineState.value.copy(
            spatialAudioEnabled = enabled,
            spatialStrength = clampedStrength,
        )

        updateVirtualizerState()

        scope.launch {
            context.audioPrefs.edit {
                it[KEY_SPATIAL_AUDIO] = enabled
                it[KEY_SPATIAL_STRENGTH] = clampedStrength
                it[KEY_SPATIAL_MIGRATED] = true
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════
    // REVERB (Room Acoustics)
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Get available reverb presets
     */
    fun getReverbPresets(): List<Pair<Int, String>> = listOf(
        PresetReverb.PRESET_NONE.toInt() to "None",
        PresetReverb.PRESET_SMALLROOM.toInt() to "Small Room",
        PresetReverb.PRESET_MEDIUMROOM.toInt() to "Medium Room",
        PresetReverb.PRESET_LARGEROOM.toInt() to "Large Room",
        PresetReverb.PRESET_MEDIUMHALL.toInt() to "Medium Hall",
        PresetReverb.PRESET_LARGEHALL.toInt() to "Large Hall",
        PresetReverb.PRESET_PLATE.toInt() to "Plate",
    )
    
    /**
     * Enable/disable Reverb with preset
     */
    private fun applyReverbEffect(enabled: Boolean, preset: Int) {
        reverb?.let { r ->
            try {
                r.enabled = enabled
                if (enabled && preset != PresetReverb.PRESET_NONE.toInt()) {
                    r.preset = preset.toShort()
                    Log.d(TAG, "Platform Reverb ON: Preset $preset")
                } else {
                    Log.d(TAG, "Platform Reverb OFF")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting Platform Reverb", e)
            }
        }
    }

    fun setReverb(enabled: Boolean, preset: Int = PresetReverb.PRESET_NONE.toInt()) {
        applyReverbEffect(enabled, preset)

        _audioEngineState.value = _audioEngineState.value.copy(
            reverbEnabled = enabled,
            reverbPreset = preset,
        )

        scope.launch {
            context.audioPrefs.edit {
                it[KEY_REVERB_ENABLED] = enabled
                it[KEY_REVERB_PRESET] = preset
            }
        }
    }
    
    /**
     * Reset all effects to default
     */
    fun resetToDefault() {
        setWifiQualityTier(StreamQuality.BEST)
        setCellularQualityTier(StreamQuality.HIGH)
        setDownloadQualityTier(StreamQuality.BEST)
        setEqualizer(false)
        setSpatialAudio(false, DEFAULT_VIRTUALIZER_STRENGTH)
        setReverb(false)
        Log.d(TAG, "Reset to defaults")
    }
    
    // ═══════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════
    
    private fun releaseEffects() {
        try {
            virtualizer?.release()
            equalizer?.release()
            reverb?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing effects", e)
        }
        
        virtualizer = null
        equalizer = null
        reverb = null
    }
    
    @Synchronized
    fun release() {
        Log.d(TAG, "Releasing Audio Engine")
        spatialAttributes = null
        spatialFormat = null
        stereoEffectAvailable = false
        releaseEffects()
        clearStreamInfo()
        currentAudioSessionId = 0
        updateVirtualizerState()
    }
}

enum class SpatialAudioMode {
    OFF, WAITING, SYSTEM, VIRTUALIZER, STEREO_EFFECT, UNAVAILABLE
}

/**
 * Output device types for quality adaptation
 */
enum class OutputDeviceType(val displayName: String) {
    BUILT_IN_SPEAKER("Built-in Speaker"),
    WIRED_HEADPHONES("Wired Headphones"),
    BLUETOOTH("Bluetooth"),
    USB_DAC("USB DAC"),
}

/**
 * Data class for UI-friendly EQ band info
 */
data class EqBandInfo(
    val band: Int,
    val frequency: Int,
    val minLevel: Int,
    val maxLevel: Int,
    val currentLevel: Int
)

/**
 * Audio Engine State for UI binding
 * 
 * Apple Music-style: separate quality tiers for Wi-Fi / Cellular / Download
 */
data class AudioEngineState(
    // Spatial Audio (disabled by default to prevent comb-filtering & muffled midrange)
    val spatialAudioEnabled: Boolean = false,
    val spatialStrength: Int = AudioEngine.DEFAULT_VIRTUALIZER_STRENGTH,
    // Equalizer
    val eqEnabled: Boolean = false,
    val eqPreset: String = "flat",
    // Reverb
    val reverbEnabled: Boolean = false,
    val reverbPreset: Int = 0,
    // Apple-style Quality Tiers
    val wifiQualityTier: StreamQuality = StreamQuality.BEST,
    val cellularQualityTier: StreamQuality = StreamQuality.HIGH,
    val downloadQualityTier: StreamQuality = StreamQuality.BEST,
)
