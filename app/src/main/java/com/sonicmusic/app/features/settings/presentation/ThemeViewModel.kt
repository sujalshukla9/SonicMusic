package com.sonicmusic.app.features.settings.presentation

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
import com.sonicmusic.app.features.player.service.PlayerServiceConnection
import com.sonicmusic.app.theme.DynamicThemeExtractor
import com.sonicmusic.app.theme.PlayerColorScheme
import com.sonicmusic.app.theme.ColorExtractor
import com.sonicmusic.app.theme.DynamicColorSchemeFactory
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ThemeCacheEntry(
    val seedColor: Int,
    val scheme: PlayerColorScheme
)

@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val playerServiceConnection: PlayerServiceConnection,
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

    private val _contentBitmap = MutableStateFlow<Bitmap?>(null)
    val contentBitmap: StateFlow<Bitmap?> = _contentBitmap.asStateFlow()

    private val _currentAlbumArtUri = MutableStateFlow<String?>(null)
    val currentAlbumArtUri: StateFlow<String?> = _currentAlbumArtUri.asStateFlow()

    private var currentMediaKey: String? = null

    // Cache to avoid re-extracting for same album art or song id
    private val schemeCache = LruCache<String, ThemeCacheEntry>(60)
    private var colorExtractionJob: Job? = null
    private var currentExtractionGeneration = 0L

    init {
        // 1. Load initial seed color from DataStore (one-shot read for instant startup without flashing)
        viewModelScope.launch {
            val lastSeed = settingsDataStore.lastSeedColor.first()
            if (_seedColor.value == null && lastSeed != null) {
                _seedColor.value = lastSeed
                val isDark = checkIsDark()
                updatePlayerColorScheme(lastSeed, isDark, paletteStyle.value)
            }
        }

        // 2. Reactively observe Media3 playback state directly from PlayerServiceConnection
        viewModelScope.launch {
            playerServiceConnection.currentSong.collect { song ->
                onSongChanged(song?.id, song?.thumbnailUrl)
            }
        }

        // 3. Keep player color scheme in sync when dark mode or palette style changes
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(darkMode, paletteStyle, _seedColor) { dm, ps, seed ->
                Triple(dm, ps, seed)
            }.collect { (_, style, seed) ->
                if (seed != null) {
                    val isDark = checkIsDark()
                    updatePlayerColorScheme(seed, isDark, style)
                }
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

    private fun updatePlayerColorScheme(seed: Int, isDark: Boolean, style: PaletteStyle) {
        val dynamicScheme = DynamicColorSchemeFactory.generateColorScheme(seed, isDark, style)
        _playerColorScheme.value = PlayerColorScheme(
            primary = dynamicScheme.primary,
            secondary = dynamicScheme.secondary,
            backgroundStops = if (isDark) {
                listOf(
                    dynamicScheme.surfaceContainerLow,
                    dynamicScheme.surfaceContainerLowest,
                    Color(0xFF111318)
                )
            } else {
                listOf(
                    dynamicScheme.surfaceContainerHigh,
                    dynamicScheme.surfaceContainerLow,
                    Color(0xFFFBF8FF)
                )
            },
            onSurface = if (isDark) Color.White else Color.Black,
            isDarkGradient = isDark
        )
    }

    fun onSongChanged(songId: String?, albumArtUri: String?) {
        val key = songId ?: albumArtUri
        if (currentMediaKey == key && key != null) return
        currentMediaKey = key
        _currentAlbumArtUri.value = albumArtUri

        colorExtractionJob?.cancel()

        if (albumArtUri.isNullOrBlank()) {
            // Retain previous valid palette if we have one to avoid flashing during track metadata latency.
            // If no palette was ever set, load default fallback.
            if (_seedColor.value == null) {
                val isDark = checkIsDark()
                _playerColorScheme.value = ColorExtractor.fallbackColorScheme(isDark)
            }
            _contentBitmap.value = null
            return
        }

        val cacheKey = key ?: albumArtUri
        val generation = ++currentExtractionGeneration
        colorExtractionJob = viewModelScope.launch(Dispatchers.Default) {
            extractAndApplyColor(cacheKey, albumArtUri, generation)
        }
    }

    fun onAlbumArtChanged(albumArtUri: String?) {
        onSongChanged(songId = null, albumArtUri = albumArtUri)
    }

    private suspend fun extractAndApplyColor(key: String, uri: String, generation: Long) {
        val isDark = checkIsDark()
        val currentStyle = paletteStyle.value

        // 1. Check scheme cache first (instant hit without re-running palette extraction)
        schemeCache.get(key)?.let { cached ->
            if (generation != currentExtractionGeneration || _currentAlbumArtUri.value != uri) return
            _playerColorScheme.value = cached.scheme
            _seedColor.value = cached.seedColor
            return
        }

        try {
            // 2. Check Coil memoryCache first to avoid redundant decode or network if already in memory
            val memoryCacheKey = coil.memory.MemoryCache.Key(uri)
            val cachedBitmap = imageLoader.memoryCache?.get(memoryCacheKey)?.bitmap

            val bitmap: Bitmap? = if (cachedBitmap != null && !cachedBitmap.isRecycled) {
                cachedBitmap
            } else {
                val request = ImageRequest.Builder(context)
                    .data(uri)
                    .size(128)
                    .allowHardware(false)
                    .build()

                val result = imageLoader.execute(request)
                val drawable = (result as? coil.request.SuccessResult)?.drawable ?: result.drawable
                (drawable as? BitmapDrawable)?.bitmap ?: drawable?.toBitmap(128, 128)
            }

            if (bitmap == null) {
                Log.w("ThemeVM", "Failed to obtain bitmap for $uri")
                return
            }

            // Stale check
            if (generation != currentExtractionGeneration || _currentAlbumArtUri.value != uri) return

            _contentBitmap.value = bitmap

            // 3. Extract validated seed color using AndroidX Palette + MCU hybrid pipeline
            val seed = DynamicThemeExtractor.extractSeedColor(bitmap)

            // Stale check before applying theme
            if (generation != currentExtractionGeneration || _currentAlbumArtUri.value != uri) return

            // 4. Generate Material 3 color scheme
            val dynamicScheme = DynamicColorSchemeFactory.generateColorScheme(seed, isDark, currentStyle)
            val extractedScheme = PlayerColorScheme(
                primary = dynamicScheme.primary,
                secondary = dynamicScheme.secondary,
                backgroundStops = if (isDark) {
                    listOf(
                        dynamicScheme.surfaceContainerLow,
                        dynamicScheme.surfaceContainerLowest,
                        Color(0xFF111318)
                    )
                } else {
                    listOf(
                        dynamicScheme.surfaceContainerHigh,
                        dynamicScheme.surfaceContainerLow,
                        Color(0xFFFBF8FF)
                    )
                },
                onSurface = if (isDark) Color.White else Color.Black,
                isDarkGradient = isDark
            )

            // Cache with true seedColor integer
            schemeCache.put(key, ThemeCacheEntry(seed, extractedScheme))

            // Atomic update of seed & player scheme - smoothly transitions root M3 theme via animateColorAsState
            _seedColor.value = seed
            settingsDataStore.setLastSeedColor(seed)
            _playerColorScheme.value = extractedScheme
            Log.d("ThemeVM", "Successfully applied M3 dynamic theme seed: #${Integer.toHexString(seed)} for $uri (key=$key)")
        } catch (e: Exception) {
            Log.w("ThemeVM", "Color extraction failed for $uri", e)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsDataStore.setThemeMode(mode) }
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
