package com.sonicmusic.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.data.repository.SettingsRepository
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.model.ThemeMode
import com.sonicmusic.app.service.AudioEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val audioEngine: AudioEngine
) : ViewModel() {

    // Audio Engine Settings
    val wifiStreamingQuality = audioEngine.audioEngineState
        .map { it.wifiQualityTier }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamQuality.LOSSLESS)

    val cellularStreamingQuality = audioEngine.audioEngineState
        .map { it.cellularQualityTier }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamQuality.HIGH)

    val downloadQuality: StateFlow<StreamQuality> = settingsRepository.downloadQuality // Keep download in repo for now or move to AE if AE handles it (AE does have setDownloadQualityTier)
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamQuality.BEST)

    val normalizeVolume = audioEngine.audioEngineState
        .map { it.soundCheckEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val crossfadeDuration = audioEngine.audioEngineState
        .map { it.crossfadeDuration }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 3)

    // Repository Settings
    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    val dynamicColors: StateFlow<Boolean> = settingsRepository.dynamicColors
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val gaplessPlayback: StateFlow<Boolean> = settingsRepository.gaplessPlayback
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val cacheSizeLimit: StateFlow<Long> = settingsRepository.cacheSizeLimit
        .stateIn(viewModelScope, SharingStarted.Eagerly, 2048L)

    val pauseHistory: StateFlow<Boolean> = settingsRepository.pauseHistory
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val albumArtBlur: StateFlow<Boolean> = settingsRepository.albumArtBlur
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val skipSilence: StateFlow<Boolean> = settingsRepository.skipSilence
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val autoQueueSimilar: StateFlow<Boolean> = settingsRepository.autoQueueSimilar
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setWifiQuality(quality: StreamQuality) = audioEngine.setWifiQualityTier(quality)
    
    fun setCellularQuality(quality: StreamQuality) = audioEngine.setCellularQualityTier(quality)

    fun setDownloadQuality(quality: StreamQuality) {
        viewModelScope.launch {
            settingsRepository.setDownloadQuality(quality)
            // Also update AE if needed, AE has setDownloadQualityTier
            audioEngine.setDownloadQualityTier(quality) 
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    fun setDynamicColors(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDynamicColors(enabled)
        }
    }

    fun setNormalizeVolume(enabled: Boolean) {
        audioEngine.setSoundCheck(enabled)
    }

    fun setGaplessPlayback(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setGaplessPlayback(enabled)
        }
    }

    fun setCrossfadeDuration(seconds: Int) {
        audioEngine.setCrossfade(seconds > 0, seconds * 1000)
    }

    fun setCacheSizeLimit(mb: Long) {
        viewModelScope.launch {
            settingsRepository.setCacheSizeLimit(mb)
        }
    }

    fun setPauseHistory(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPauseHistory(enabled)
        }
    }

    fun setAlbumArtBlur(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAlbumArtBlur(enabled)
        }
    }

    fun setSkipSilence(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSkipSilence(enabled)
        }
    }

    fun setAutoQueueSimilar(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoQueueSimilar(enabled)
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            // TODO: Implement cache clearing
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            // TODO: Implement search history clearing
        }
    }
}