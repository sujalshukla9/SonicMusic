package com.sonicmusic.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.SongRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getHomeContentUseCase: com.sonicmusic.app.domain.usecase.GetHomeContentUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadHomeContent()
    }

    private fun loadHomeContent() {
        viewModelScope.launch {
            getHomeContentUseCase()
                .onSuccess { content ->
                    _uiState.value = HomeUiState.Success(content)
                }
                .onFailure {
                    _uiState.value = HomeUiState.Error("Failed to load content")
                }
        }
    }
}

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Success(val content: com.sonicmusic.app.domain.usecase.HomeContent) : HomeUiState
    data class Error(val message: String) : HomeUiState
}
