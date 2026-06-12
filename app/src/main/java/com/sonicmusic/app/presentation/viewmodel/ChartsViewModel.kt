package com.sonicmusic.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.ChartAlbum
import com.sonicmusic.app.domain.model.ChartArtist
import com.sonicmusic.app.domain.model.ChartSong
import com.sonicmusic.app.domain.repository.ChartsRepository
import com.sonicmusic.app.data.repository.RegionProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ChartsUiState<out T> {
    object Loading : ChartsUiState<Nothing>
    data class Success<out T>(val data: T) : ChartsUiState<T>
    data class Error(val message: String) : ChartsUiState<Nothing>
}

@HiltViewModel
class TopSongsViewModel @Inject constructor(
    private val chartsRepository: ChartsRepository,
    private val regionProvider: RegionProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChartsUiState<List<ChartSong>>>(ChartsUiState.Loading)
    val uiState: StateFlow<ChartsUiState<List<ChartSong>>> = _uiState.asStateFlow()

    val regionName: StateFlow<String> = regionProvider.regionNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "United States")

    init {
        loadTopSongs()
    }

    fun loadTopSongs() {
        viewModelScope.launch {
            _uiState.value = ChartsUiState.Loading
            try {
                val region = regionProvider.getRegionCode()
                val songs = chartsRepository.getTopSongs(region)
                _uiState.value = ChartsUiState.Success(songs)
            } catch (e: Exception) {
                _uiState.value = ChartsUiState.Error(e.message ?: "Failed to load top songs")
            }
        }
    }
}

@HiltViewModel
class TopArtistsViewModel @Inject constructor(
    private val chartsRepository: ChartsRepository,
    private val regionProvider: RegionProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChartsUiState<List<ChartArtist>>>(ChartsUiState.Loading)
    val uiState: StateFlow<ChartsUiState<List<ChartArtist>>> = _uiState.asStateFlow()

    val regionName: StateFlow<String> = regionProvider.regionNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "United States")

    init {
        loadTopArtists()
    }

    fun loadTopArtists() {
        viewModelScope.launch {
            _uiState.value = ChartsUiState.Loading
            try {
                val region = regionProvider.getRegionCode()
                val artists = chartsRepository.getTopArtists(region)
                _uiState.value = ChartsUiState.Success(artists)
            } catch (e: Exception) {
                _uiState.value = ChartsUiState.Error(e.message ?: "Failed to load top artists")
            }
        }
    }
}

@HiltViewModel
class TopAlbumsViewModel @Inject constructor(
    private val chartsRepository: ChartsRepository,
    private val regionProvider: RegionProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChartsUiState<List<ChartAlbum>>>(ChartsUiState.Loading)
    val uiState: StateFlow<ChartsUiState<List<ChartAlbum>>> = _uiState.asStateFlow()

    val regionName: StateFlow<String> = regionProvider.regionNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "United States")

    init {
        loadTopAlbums()
    }

    fun loadTopAlbums() {
        viewModelScope.launch {
            _uiState.value = ChartsUiState.Loading
            try {
                val region = regionProvider.getRegionCode()
                val albums = chartsRepository.getTopAlbums(region)
                _uiState.value = ChartsUiState.Success(albums)
            } catch (e: Exception) {
                _uiState.value = ChartsUiState.Error(e.message ?: "Failed to load top albums")
            }
        }
    }
}
