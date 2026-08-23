package com.sonicmusic.app.presentation.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.ImageRequest
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.domain.model.DarkMode
import com.sonicmusic.app.domain.model.PaletteStyle
import com.sonicmusic.app.domain.model.ThemeMode
import com.sonicmusic.app.domain.model.ThumbnailShape
import com.sonicmusic.app.presentation.ui.theme.DynamicThemeExtractor
import com.sonicmusic.app.presentation.ui.theme.PlayerColorScheme
import com.sonicmusic.app.presentation.ui.theme.ColorExtractor
import com.sonicmusic.app.presentation.ui.theme.DynamicColorSchemeFactory
import androidx.compose.ui.graphics.toArgb
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    @SuppressLint("StaticFieldLeak") @ApplicationContext private val context: Context
) : ViewModel() {

    // Use the Coil singleton configured in SonicMusicApplication.newImageLoader()
    // so we benefit from the tuned memory/disk caches and respectCacheHeaders(false).
    private val imageLoader get() = context.imageLoader

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

    val thumbnailShape: StateFlow<ThumbnailShape> = settingsDataStore.thumbnailShape
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThumbnailShape.ROUNDED_SQUARE)

    // Dynamic Scheme State
    private val _playerColorScheme = MutableStateFlow<PlayerColorScheme>(ColorExtractor.fallbackColorScheme(true))
    val playerColorScheme: StateFlow<PlayerColorScheme> = _playerColorScheme.asStateFlow()

    // Dynamic State
    private val _seedColor = MutableStateFlow<Int?>(null)
    val seedColor: StateFlow<Int?> = _seedColor.asStateFlow()

    private val _currentAlbumArtUri = MutableStateFlow<String?>(null)

    // Cache to avoid re-extracting for same album art
    private val schemeCache = LruCache<String, PlayerColorScheme>(50)

    init {
        // Load initial seed color from DataStore (one-shot read)
        viewModelScope.launch {
            val lastSeed = settingsDataStore.lastSeedColor.first()
            if (_seedColor.value == null && lastSeed != null) {
                _seedColor.value = lastSeed
                val isDark = checkIsDark()
                val fallbackScheme = DynamicColorSchemeFactory.generateColorScheme(lastSeed, isDark, PaletteStyle.CONTENT)
                _playerColorScheme.value = PlayerColorScheme(
                    primary = androidx.compose.ui.graphics.Color(fallbackScheme.primary.toArgb()),
                    secondary = androidx.compose.ui.graphics.Color(fallbackScheme.secondary.toArgb()),
                    backgroundStops = if (isDark) {
                        listOf(
                            androidx.compose.ui.graphics.Color(fallbackScheme.surfaceContainerLow.toArgb()),
                            androidx.compose.ui.graphics.Color(fallbackScheme.surfaceContainerLowest.toArgb()),
                            androidx.compose.ui.graphics.Color(0xFF111318)
                        )
                    } else {
                        listOf(
                            androidx.compose.ui.graphics.Color(fallbackScheme.surfaceContainerHigh.toArgb()),
                            androidx.compose.ui.graphics.Color(fallbackScheme.surfaceContainerLow.toArgb()),
                            androidx.compose.ui.graphics.Color(0xFFFBF8FF)
                        )
                    },
                    onSurface = if (isDark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black,
                    isDarkGradient = isDark
                )
            }
        }
    }

    private fun checkIsDark(): Boolean {
        return when (darkMode.value) {
            DarkMode.LIGHT -> false
            DarkMode.DARK -> true
            DarkMode.SYSTEM -> {
                val currentNightMode = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
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
        } else {
            // Reset to fallback when no album art
            val isDark = checkIsDark()
            _playerColorScheme.value = ColorExtractor.fallbackColorScheme(isDark)
            _seedColor.value = null
        }
    }

    private suspend fun extractAndApplyColor(uri: String) {
        val isDark = checkIsDark()
        // Check cache first
        schemeCache.get(uri)?.let { cached ->
            _playerColorScheme.value = cached
            _seedColor.value = cached.primary.toArgb()
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

            val extractedScheme = ColorExtractor.extractColorsFromBitmap(bitmap, isDark)
            schemeCache.put(uri, extractedScheme)
            _playerColorScheme.value = extractedScheme
            
            val primaryArgb = extractedScheme.primary.toArgb()
            _seedColor.value = primaryArgb
            
            // Save last extracted color to Datastore
            settingsDataStore.setLastSeedColor(primaryArgb)
        } catch (e: Exception) {
            Log.w("ThemeVM", " Color extraction failed", e)
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
