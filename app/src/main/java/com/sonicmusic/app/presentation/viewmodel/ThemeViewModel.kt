package com.sonicmusic.app.presentation.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.request.ImageRequest
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.domain.model.DarkMode
import com.sonicmusic.app.domain.model.PaletteStyle
import com.sonicmusic.app.domain.model.ThemeMode
import com.sonicmusic.app.presentation.ui.theme.DynamicThemeExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val imageLoader = ImageLoader(context)

    // User Preferences
    val themeMode: StateFlow<ThemeMode> = settingsDataStore.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.DYNAMIC)

    val darkMode: StateFlow<DarkMode> = settingsDataStore.darkMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, DarkMode.SYSTEM)

    val paletteStyle: StateFlow<PaletteStyle> = settingsDataStore.paletteStyle
        .stateIn(viewModelScope, SharingStarted.Eagerly, PaletteStyle.CONTENT)

    val pureBlack: StateFlow<Boolean> = settingsDataStore.pureBlack
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val dynamicColorsEnabled: StateFlow<Boolean> = settingsDataStore.dynamicColors
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val dynamicColorIntensity: StateFlow<Int> = settingsDataStore.dynamicColorIntensity
        .stateIn(viewModelScope, SharingStarted.Eagerly, 85)

    // Dynamic State
    private val _seedColor = MutableStateFlow<Int?>(null)
    val seedColor: StateFlow<Int?> = _seedColor.asStateFlow()

    private val _currentAlbumArtUri = MutableStateFlow<String?>(null)

    // Cache to avoid re-extracting for same album art
    private val colorCache = LruCache<String, Int>(50)

    init {
        // Load initial seed color from DataStore if available
        viewModelScope.launch {
            settingsDataStore.lastSeedColor.collect { lastSeed ->
                if (_seedColor.value == null && lastSeed != null) {
                    _seedColor.value = lastSeed
                }
            }
        }
    }

    fun onAlbumArtChanged(albumArtUri: String?) {
        if (_currentAlbumArtUri.value == albumArtUri) return
        _currentAlbumArtUri.value = albumArtUri
        
        if (albumArtUri != null) {
            viewModelScope.launch {
                extractAndApplyColor(albumArtUri)
            }
        }
    }

    private suspend fun extractAndApplyColor(uri: String) {
        // Check cache first
        colorCache.get(uri)?.let { cached ->
            _seedColor.value = cached
            return
        }

        try {
            val request = ImageRequest.Builder(context)
                .data(uri)
                .size(64) // Smaller size for faster color extraction
                .allowHardware(false) // Need software bitmap for pixel access
                .build()

            val result = imageLoader.execute(request)
            val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return

            val extracted = DynamicThemeExtractor.extractSeedColor(bitmap)
            colorCache.put(uri, extracted)
            _seedColor.value = extracted
            
            // Save last extracted color to Datastore
            settingsDataStore.setLastSeedColor(extracted)
        } catch (e: Exception) {
            Log.w("ThemeVM", "Color extraction failed", e)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsDataStore.setThemeMode(mode) }
        // Re-trigger extraction just in case
        _currentAlbumArtUri.value?.let { uri ->
            viewModelScope.launch { extractAndApplyColor(uri) }
        }
    }

    fun setDarkMode(mode: DarkMode) {
        viewModelScope.launch { settingsDataStore.setDarkMode(mode) }
    }

    fun setPaletteStyle(style: PaletteStyle) {
        viewModelScope.launch { settingsDataStore.setPaletteStyle(style) }
    }

    fun setPureBlack(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setPureBlack(enabled) }
    }
}
