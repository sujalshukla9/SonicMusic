package com.sonicmusic.app.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.SongRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val songRepository: SongRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    fun search(query: String) {
        if (query.isBlank()) return
        
        _uiState.value = SearchUiState.Loading
        viewModelScope.launch {
            songRepository.searchSongs(query)
                .onSuccess { songs ->
                    _uiState.value = SearchUiState.Success(songs)
                }
                .onFailure {
                    _uiState.value = SearchUiState.Error("Search failed")
                }
        }
    }
}

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Success(val songs: List<Song>) : SearchUiState
    data class Error(val message: String) : SearchUiState
}
