package com.sonicmusic.app.domain.usecase

import android.media.audiofx.Equalizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Equalizer Manager
 *
 * Manages audio equalization using Android's AudioEffect API.
 * Features:
 * - 5-band or 10-band EQ (device dependent)
 * - Preset management
 * - Custom band adjustments
 * - Bass boost and Virtualizer support (planned)
 */
@Singleton
class EqualizerManager @Inject constructor() {

    companion object {
        private const val TAG = "EqualizerManager"
    }

    private var equalizer: Equalizer? = null
    
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _bands = MutableStateFlow<List<EqualizerBand>>(emptyList())
    val bands: StateFlow<List<EqualizerBand>> = _bands.asStateFlow()

    private val _presets = MutableStateFlow<List<String>>(emptyList())
    val presets: StateFlow<List<String>> = _presets.asStateFlow()

    private val _currentPreset = MutableStateFlow<String?>(null)
    val currentPreset: StateFlow<String?> = _currentPreset.asStateFlow()

    /**
     * Initialize equalizer with audio session ID from ExoPlayer.
     */
    fun init(audioSessionId: Int) {
        try {
            release()
            
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = _enabled.value
            }

            loadBands()
            loadPresets()
            
            Log.d(TAG, "Equalizer initialized for session: $audioSessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize equalizer", e)
        }
    }

    /**
     * Release equalizer resources.
     */
    fun release() {
        equalizer?.release()
        equalizer = null
    }

    /**
     * Toggle equalizer enabled state.
     */
    fun setEnabled(isEnabled: Boolean) {
        _enabled.value = isEnabled
        equalizer?.enabled = isEnabled
    }

    /**
     * Set band level (amplitude).
     * @param bandId Band index
     * @param level Level in millibels (mB)
     */
    fun setBandLevel(bandId: Short, level: Short) {
        try {
            equalizer?.setBandLevel(bandId, level)
            
            // Update state
            val currentBands = _bands.value.toMutableList()
            if (bandId >= 0 && bandId < currentBands.size) {
                currentBands[bandId.toInt()] = currentBands[bandId.toInt()].copy(level = level)
                _bands.value = currentBands
            }
            
            // Custom preset active
            _currentPreset.value = "Custom"
        } catch (e: Exception) {
            Log.e(TAG, "Error setting band level", e)
        }
    }

    /**
     * Use a preset by index.
     */
    fun usePreset(presetIndex: Short) {
        try {
            equalizer?.usePreset(presetIndex)
            val presetName = equalizer?.getPresetName(presetIndex)
            _currentPreset.value = presetName
            
            // Refund bands with new levels
            loadBands()
        } catch (e: Exception) {
            Log.e(TAG, "Error using preset", e)
        }
    }

    private fun loadBands() {
        val eq = equalizer ?: return
        val bandsList = mutableListOf<EqualizerBand>()
        
        try {
            val numBands = eq.numberOfBands
            val minLevel = eq.bandLevelRange[0]
            val maxLevel = eq.bandLevelRange[1]

            for (i in 0 until numBands) {
                val centerFreq = eq.getCenterFreq(i.toShort())
                val level = eq.getBandLevel(i.toShort())
                
                bandsList.add(
                    EqualizerBand(
                        id = i.toShort(),
                        centerFrequency = centerFreq,
                        level = level,
                        minLevel = minLevel,
                        maxLevel = maxLevel
                    )
                )
            }
            _bands.value = bandsList
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bands", e)
        }
    }

    private fun loadPresets() {
        val eq = equalizer ?: return
        val presetsList = mutableListOf<String>()
        
        try {
            val numPresets = eq.numberOfPresets
            for (i in 0 until numPresets) {
                presetsList.add(eq.getPresetName(i.toShort()))
            }
            _presets.value = presetsList
        } catch (e: Exception) {
            Log.e(TAG, "Error loading presets", e)
        }
    }
}

/**
 * Data class representing an equalizer band.
 */
data class EqualizerBand(
    val id: Short,
    val centerFrequency: Int, // in milliHertz
    val level: Short, // in millibels
    val minLevel: Short,
    val maxLevel: Short
) {
    fun getFrequencyString(): String {
        val hz = centerFrequency / 1000
        return if (hz >= 1000) {
            "${hz / 1000} kHz"
        } else {
            "$hz Hz"
        }
    }
}
