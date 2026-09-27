package com.sonicmusic.app.features.player.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.StreamQuality

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
import kotlin.math.roundToInt
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Apple Music-Style Premium Audio Engine
 * 
 * Professional-grade audio processing pipeline inspired by Apple Music:
 *
 * AUDIO QUALITY TIERS (AAC-First, Apple Music-Style)
 * ├─ Data Saver       → ≤64 kbps, any codec
 * ├─ High Efficiency  → AAC 128 kbps · cellular-optimised
 * ├─ High Quality     → AAC 256 kbps (Apple Music standard)
 * └─ Best Available   → AAC 256 kbps (primary); OPUS fallback if no AAC
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
 * - Sound Check (LUFS loudness normalization)
 * - Spatial Audio (Dolby Atmos-like 3D immersion)
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
        
        private val KEY_CROSSFADE_ENABLED = booleanPreferencesKey("crossfade_enabled")
        private val KEY_CROSSFADE_DURATION = intPreferencesKey("crossfade_duration_ms")
        private val KEY_SOUND_CHECK = booleanPreferencesKey("sound_check_enabled")
        private val KEY_LOUDNESS_TARGET = floatPreferencesKey("loudness_target_db")

        private val KEY_SPATIAL_AUDIO = booleanPreferencesKey("spatial_audio_enabled")
        private val KEY_SPATIAL_STRENGTH = intPreferencesKey("spatial_strength")
        private val KEY_EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        private val KEY_EQ_PRESET = stringPreferencesKey("eq_preset")
        private val KEY_REVERB_ENABLED = booleanPreferencesKey("reverb_enabled")
        private val KEY_REVERB_PRESET = intPreferencesKey("reverb_preset")
        
        // Apple-style quality tier keys
        private val KEY_WIFI_QUALITY_TIER = stringPreferencesKey("wifi_quality_tier")
        private val KEY_CELLULAR_QUALITY_TIER = stringPreferencesKey("cellular_quality_tier")
        private val KEY_DOWNLOAD_QUALITY_TIER = stringPreferencesKey("download_quality_tier")
        
        // Default Values
        const val DEFAULT_CROSSFADE_DURATION = 3000
        const val DEFAULT_LOUDNESS_TARGET = -16f  // Apple Music standard (-16 LUFS)
        const val MAX_VIRTUALIZER_STRENGTH = 1000
        private const val JAMES_DSP_MUD_CUT_MAX_MB = 180
        private const val JAMES_DSP_PRESENCE_RECOVERY_MAX_MB = 90
        private const val JAMES_DSP_HEADROOM_MAX_MB = 260
    }
    
    private val Context.audioPrefs by preferencesDataStore(name = "audio_engine_prefs")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        // Observe capability detector changes dynamically to initialize Dolby DSP when detected
        scope.launch {
            capabilityDetector.capability.collect { cap ->
                Log.d(TAG, "Dolby capability changed: ${cap.dolbyLabel} (descriptor=${cap.dolbyEffectDescriptor})")
                val deviceSupportsDolby = cap.supportsDolbyAtmos
                val shouldEnableDolby = deviceSupportsDolby && currentStreamHasDolbyAtmos
                val sessionId = currentAudioSessionId
                if (sessionId != 0 && cap.dolbyEffectDescriptor != null) {
                    initializeDolbyDsp(sessionId)
                } else {
                    // Release in-app Dolby effect if descriptor absent, but preserve device Dolby capability
                    dolbyEffect?.release()
                    dolbyEffect = null
                    _audioEngineState.value = _audioEngineState.value.copy(
                        dolbyDspAvailable = deviceSupportsDolby,
                        dolbyDspEnabled = shouldEnableDolby
                    )
                }
            }
        }
    }
    
    // Audio Effects
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var virtualizer: Virtualizer? = null
    private var equalizer: Equalizer? = null
    private var reverb: PresetReverb? = null
    private var dolbyEffect: AudioEffect? = null
    
    // Current audio session
    private var currentAudioSessionId: Int = 0
    private val customBandLevels = ShortArray(5) { 0 }
    
    // Per-song Dolby Atmos state: true only when the current song's InnerTube
    // metadata confirms Dolby Atmos audio streams are available.
    private var currentStreamHasDolbyAtmos: Boolean = false
    
    // ═══════════════════════════════════════════════════════════════
    // STATE FLOWS
    // ═══════════════════════════════════════════════════════════════
    
    private val _audioEngineState = MutableStateFlow(AudioEngineState())
    val audioEngineState: StateFlow<AudioEngineState> = _audioEngineState.asStateFlow()
    
    private val _currentStreamInfo = MutableStateFlow<AudioStreamInfo?>(null)
    val currentStreamInfo: StateFlow<AudioStreamInfo?> = _currentStreamInfo.asStateFlow()
    
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
    
    // ═══════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Initialize audio engine with ExoPlayer's audio session ID
     */
    fun initialize(audioSessionId: Int) {
        if (audioSessionId == 0) return
        if (audioSessionId == currentAudioSessionId) return
        
        Log.d(TAG, "Initializing Apple-Style Audio Engine with session: $audioSessionId")
        currentAudioSessionId = audioSessionId
        
        // Release previous effects
        releaseEffects()
        
        // Initialize all audio effects
        initializeLoudnessEnhancer(audioSessionId)
        initializeVirtualizer(audioSessionId)
        initializeEqualizer(audioSessionId)
        initializeReverb(audioSessionId)
        initializeDolbyDsp(audioSessionId)
        
        // Detect output device capabilities
        detectOutputDevice()
        
        // Load saved preferences
        scope.launch {
            loadSavedPreferences()
        }
        
        Log.d(TAG, "Apple-Style Audio Engine initialized")
    }
    
    private fun initializeLoudnessEnhancer(audioSessionId: Int) {
        try {
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                enabled = false // Enabled later by loadSavedPreferences / setSoundCheck
            }
            Log.d(TAG, "Loudness Enhancer initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Loudness Enhancer not available on this device", e)
            loudnessEnhancer = null
        }
    }
    
    private fun initializeVirtualizer(audioSessionId: Int) {
        try {
            if (isEffectTypeSupported(AudioEffect.EFFECT_TYPE_VIRTUALIZER)) {
                virtualizer = Virtualizer(0, audioSessionId).apply {
                    enabled = false // Enabled later by loadSavedPreferences / setSpatialAudio
                }
                Log.d(TAG, "Virtualizer (Spatial Audio) initialized")
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
        
        val state = AudioEngineState(
            crossfadeEnabled = prefs[KEY_CROSSFADE_ENABLED] ?: false,
            crossfadeDuration = prefs[KEY_CROSSFADE_DURATION] ?: DEFAULT_CROSSFADE_DURATION,
            soundCheckEnabled = prefs[KEY_SOUND_CHECK] ?: true,
            loudnessTarget = prefs[KEY_LOUDNESS_TARGET] ?: DEFAULT_LOUDNESS_TARGET,

            spatialAudioEnabled = prefs[KEY_SPATIAL_AUDIO] ?: false,
            spatialStrength = prefs[KEY_SPATIAL_STRENGTH] ?: 800,
            eqEnabled = prefs[KEY_EQ_ENABLED] ?: false,
            eqPreset = prefs[KEY_EQ_PRESET] ?: "flat",
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
        setSoundCheck(state.soundCheckEnabled, state.loudnessTarget)

        setSpatialAudio(state.spatialAudioEnabled, state.spatialStrength)
        setEqualizer(state.eqEnabled, state.eqPreset)
        setReverb(state.reverbEnabled, state.reverbPreset)
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
    
    /**
     * Update the currently playing stream's metadata.
     * Called by PlaybackService when a new stream starts playing.
     */
    fun updateStreamInfo(info: AudioStreamInfo) {
        _currentStreamInfo.value = info
        Log.d(TAG, "Now Playing: ${info.fullDescription}")
        Log.d(TAG, "Quality Badge: ${info.qualityBadge}")
        Log.d(TAG, "Signal Path: ${info.getSignalPath(getOutputDeviceName())}")

        // ── Per-song Dolby Atmos decision ──────────────────────────
        // Enable Dolby Atmos DSP ONLY when:
        //   1. The device supports Dolby Atmos (the existing detector positively reports Dolby Atmos support)
        //   2. The current song's InnerTube metadata confirms Dolby Atmos
        //      selected stream is explicitly labelled Atmos (hasDolbyAtmos == true)
        //
        // If the song doesn't have Dolby Atmos data, we keep normal
        // stereo/audio processing — no false Atmos badge is shown.
        currentStreamHasDolbyAtmos = info.hasDolbyAtmos
        val deviceSupportsDolby = capabilityDetector.capability.value.supportsDolbyAtmos
        val shouldEnableDolby = deviceSupportsDolby && info.hasDolbyAtmos
        Log.d(TAG, "Dolby Atmos per-song check: streamHasAtmos=${info.hasDolbyAtmos}, deviceSupportsDolby=$deviceSupportsDolby, shouldEnable=$shouldEnableDolby")

        _audioEngineState.value = _audioEngineState.value.copy(
            dolbyDspAvailable = deviceSupportsDolby,
            dolbyDspEnabled = shouldEnableDolby
        )

        dolbyEffect?.let { effect ->
            try {
                effect.enabled = shouldEnableDolby
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update Dolby Atmos effect status", e)
            }
        }

        // Spatial audio handling for Atmos
        if (shouldEnableDolby) {
            if (dolbyEffect != null && virtualizer?.enabled == true) {
                Log.w(TAG, "Dolby DSP effect active, disabling built-in Virtualizer to avoid double spatialization")
                virtualizer?.enabled = false
                _audioEngineState.value = _audioEngineState.value.copy(
                    spatialAudioEnabled = false
                )
            } else if (dolbyEffect == null && virtualizer != null) {
                // Device supports Dolby via HAL/system service (e.g. Motorola/Samsung).
                // Engage virtualizer for spatial sound immersion.
                Log.d(TAG, "Engaging spatial sound immersion for Dolby Atmos stream")
                virtualizer?.enabled = true
                virtualizer?.setStrength(800.toShort())
                _audioEngineState.value = _audioEngineState.value.copy(
                    spatialAudioEnabled = true
                )
            }
        } else {
            // When Dolby is disabled for this song, restore user's saved Spatial Audio preference
            scope.launch {
                try {
                    val prefs = context.audioPrefs.data.first()
                    val savedSpatial = prefs[KEY_SPATIAL_AUDIO] ?: false
                    val savedStrength = prefs[KEY_SPATIAL_STRENGTH] ?: 800
                    setSpatialAudio(savedSpatial, savedStrength)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore spatial audio preference", e)
                }
            }
        }
    }
    
    /**
     * Clear stream info (when playback stops or changes song)
     */
    fun clearStreamInfo() {
        _currentStreamInfo.value = null
        currentStreamHasDolbyAtmos = false
        val deviceSupportsDolby = capabilityDetector.capability.value.supportsDolbyAtmos
        dolbyEffect?.let { effect ->
            try {
                effect.enabled = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disable Dolby Atmos on clear", e)
            }
        }
        _audioEngineState.value = _audioEngineState.value.copy(
            dolbyDspAvailable = deviceSupportsDolby,
            dolbyDspEnabled = false
        )
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
    
    // ═══════════════════════════════════════════════════════════════
    // SOUND CHECK (Loudness Normalization)
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Enable/disable Sound Check (like Apple Music)
     * Normalizes volume across all tracks to prevent loud/quiet jumps
     */
    fun setSoundCheck(enabled: Boolean, targetLufs: Float = DEFAULT_LOUDNESS_TARGET) {
        loudnessEnhancer?.let { enhancer ->
            try {
                if (enabled) {
                    val gainMb = ((targetLufs + 14) * 100).toInt().coerceIn(-1000, 3000)
                    enhancer.setTargetGain(gainMb)
                    enhancer.enabled = true
                    Log.d(TAG, "Platform Sound Check ON: ${targetLufs}dB LUFS (gain: ${gainMb}mB)")
                } else {
                    enhancer.enabled = false
                    Log.d(TAG, "Platform Sound Check OFF")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting Platform Sound Check", e)
            }
        }

        _audioEngineState.value = _audioEngineState.value.copy(
            soundCheckEnabled = enabled,
            loudnessTarget = targetLufs,
        )

        scope.launch {
            context.audioPrefs.edit {
                it[KEY_SOUND_CHECK] = enabled
                it[KEY_LOUDNESS_TARGET] = targetLufs
            }
        }
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
        val presetGains = eqPresets[preset] ?: eqPresets["flat"]!!
        val numBands = minOf(eq.numberOfBands.toInt(), presetGains.size)

        for (band in 0 until numBands) {
            val baseGainMb = if (applyPreset && preset == "flat") {
                customBandLevels[band].toInt()
            } else if (applyPreset) {
                presetGains[band].toInt()
            } else {
                0
            }
            val finalGain = baseGainMb
                .coerceIn(eq.bandLevelRange[0].toInt(), eq.bandLevelRange[1].toInt())
            eq.setBandLevel(band.toShort(), finalGain.toShort())
        }

        eq.enabled = applyPreset
        updateEqBands()
    }
    
    // ═══════════════════════════════════════════════════════════════
    // SPATIAL AUDIO (Dolby Atmos-like experience)
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Enable/disable Spatial Audio with adjustable immersion
     * @param strength 0-1000 (0% to 100% immersion)
     */
    fun setSpatialAudio(enabled: Boolean, strength: Int = 800) {
        val actualEnabled = if (enabled && dolbyEffect != null && dolbyEffect?.enabled == true) {
            Log.w(TAG, "Dolby DSP is active, disabling app's built-in Spatial Audio")
            false
        } else {
            enabled
        }
        val clampedStrength = strength.coerceIn(0, MAX_VIRTUALIZER_STRENGTH)

        virtualizer?.let { v ->
            try {
                v.enabled = actualEnabled
                if (actualEnabled) {
                    v.setStrength(clampedStrength.toShort())
                    Log.d(TAG, "Platform Spatial Audio ON: ${(clampedStrength / 10)}% immersion")
                } else {
                    Log.d(TAG, "Platform Spatial Audio OFF")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting Platform Spatial Audio", e)
            }
        }

        _audioEngineState.value = _audioEngineState.value.copy(
            spatialAudioEnabled = actualEnabled,
            spatialStrength = clampedStrength,
        )

        scope.launch {
            context.audioPrefs.edit {
                it[KEY_SPATIAL_AUDIO] = actualEnabled
                it[KEY_SPATIAL_STRENGTH] = clampedStrength
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
    fun setReverb(enabled: Boolean, preset: Int = PresetReverb.PRESET_NONE.toInt()) {
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
    
    // ═══════════════════════════════════════════════════════════════
    // CROSSFADE
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Enable/disable crossfade with duration control
     * @param durationMs Crossfade duration in milliseconds (1000-12000)
     */
    fun setCrossfade(enabled: Boolean, durationMs: Int = DEFAULT_CROSSFADE_DURATION) {
        val clampedDuration = durationMs.coerceIn(1000, 12000)
        
        _audioEngineState.value = _audioEngineState.value.copy(
            crossfadeEnabled = enabled,
            crossfadeDuration = clampedDuration,
        )
        
        scope.launch {
            context.audioPrefs.edit {
                it[KEY_CROSSFADE_ENABLED] = enabled
                it[KEY_CROSSFADE_DURATION] = clampedDuration
            }
        }
        
        Log.d(TAG, if (enabled) "Crossfade ON: ${clampedDuration}ms" else "Crossfade OFF")
    }
    
    private fun initializeDolbyDsp(audioSessionId: Int) {
        if (audioSessionId == 0) return
        val deviceSupportsDolby = capabilityDetector.capability.value.supportsDolbyAtmos
        val shouldEnableDolby = deviceSupportsDolby && currentStreamHasDolbyAtmos
        val descriptor = capabilityDetector.capability.value.dolbyEffectDescriptor

        if (descriptor == null) {
            Log.d(TAG, "Dolby effect descriptor is null (system/HAL Dolby), deviceSupportsDolby=$deviceSupportsDolby, shouldEnable=$shouldEnableDolby")
            dolbyEffect?.release()
            dolbyEffect = null
            _audioEngineState.value = _audioEngineState.value.copy(
                dolbyDspAvailable = deviceSupportsDolby,
                dolbyDspEnabled = shouldEnableDolby
            )
            if (shouldEnableDolby && virtualizer != null) {
                virtualizer?.enabled = true
                virtualizer?.setStrength(800.toShort())
                _audioEngineState.value = _audioEngineState.value.copy(spatialAudioEnabled = true)
            }
            return
        }

        if (dolbyEffect != null) {
            try {
                if (dolbyEffect?.enabled != shouldEnableDolby) {
                    dolbyEffect?.enabled = shouldEnableDolby
                    _audioEngineState.value = _audioEngineState.value.copy(
                        dolbyDspAvailable = deviceSupportsDolby,
                        dolbyDspEnabled = shouldEnableDolby
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update existing dolby effect status", e)
            }
            return
        }

        try {
            Log.d(TAG, "Instantiating Dolby DSP effect via reflection: type=${descriptor.type}, uuid=${descriptor.uuid}")
            val constructor = AudioEffect::class.java.getDeclaredConstructor(
                java.util.UUID::class.java,
                java.util.UUID::class.java,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE
            ).apply {
                isAccessible = true
            }
            val effect = constructor.newInstance(descriptor.type, descriptor.uuid, 0, audioSessionId) as AudioEffect
            effect.enabled = shouldEnableDolby
            dolbyEffect = effect
            val isEnabled = effect.enabled
            Log.d(TAG, "Dolby Atmos DSP initialized successfully: enabled=$isEnabled")
            
            _audioEngineState.value = _audioEngineState.value.copy(
                dolbyDspAvailable = deviceSupportsDolby,
                dolbyDspEnabled = isEnabled
            )

            // Conflict resolution: disable built-in Virtualizer
            if (isEnabled && virtualizer?.enabled == true) {
                Log.w(TAG, "Dolby DSP is active, auto-disabling app's built-in Virtualizer to avoid double spatialization")
                virtualizer?.enabled = false
                _audioEngineState.value = _audioEngineState.value.copy(
                    spatialAudioEnabled = false
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Dolby Atmos DSP on session $audioSessionId", e)
            dolbyEffect = null
            _audioEngineState.value = _audioEngineState.value.copy(
                dolbyDspAvailable = deviceSupportsDolby,
                dolbyDspEnabled = false
            )
        }
    }

    /**
     * Reset all effects to default
     */
    fun resetToDefault() {
        setSoundCheck(true, DEFAULT_LOUDNESS_TARGET)
        setWifiQualityTier(StreamQuality.BEST)
        setCellularQualityTier(StreamQuality.HIGH)
        setDownloadQualityTier(StreamQuality.BEST)
        setEqualizer(false)
        setSpatialAudio(false)
        setReverb(false)
        setCrossfade(false)
        try {
            if (dolbyEffect != null) {
                // On reset, respect per-song Dolby Atmos detection
                val shouldEnableDolby = capabilityDetector.capability.value.supportsDolbyAtmos && currentStreamHasDolbyAtmos
                dolbyEffect?.enabled = shouldEnableDolby
                _audioEngineState.value = _audioEngineState.value.copy(dolbyDspEnabled = shouldEnableDolby)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update dolby effect in resetToDefault", e)
        }
        Log.d(TAG, "Reset to defaults")
    }
    
    // ═══════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════
    
    private fun releaseEffects() {
        try {
            loudnessEnhancer?.release()
            virtualizer?.release()
            equalizer?.release()
            reverb?.release()
            dolbyEffect?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing effects", e)
        }
        
        loudnessEnhancer = null
        virtualizer = null
        equalizer = null
        reverb = null
        dolbyEffect = null
    }
    
    fun release() {
        Log.d(TAG, "Releasing Audio Engine")
        releaseEffects()
        clearStreamInfo()
        currentAudioSessionId = 0
    }
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
    // Crossfade
    val crossfadeEnabled: Boolean = false,
    val crossfadeDuration: Int = AudioEngine.DEFAULT_CROSSFADE_DURATION,
    // Sound Check
    val soundCheckEnabled: Boolean = true,
    val loudnessTarget: Float = AudioEngine.DEFAULT_LOUDNESS_TARGET,

    // Spatial Audio
    val spatialAudioEnabled: Boolean = false,
    val spatialStrength: Int = 800,
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
    // Dolby Atmos DSP
    val dolbyDspAvailable: Boolean = false,
    val dolbyDspEnabled: Boolean = false,
)
