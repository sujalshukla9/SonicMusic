package com.sonicmusic.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.data.repository.InnertubeHomeRepository
import com.sonicmusic.app.domain.model.HomeSection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI State for the clean song-only home feed.
 */
sealed interface HomeUIState {
    object Loading : HomeUIState
    data class Success(val sections: List<HomeSection>) : HomeUIState
    data class Error(val message: String) : HomeUIState
}

/**
 * Clean ViewModel example that coordinates the [InnertubeHomeRepository] clean feed
 * and exposes state flow objects to the Compose UI.
 */
@HiltViewModel
class CleanHomeViewModel @Inject constructor(
    private val homeRepository: InnertubeHomeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUIState>(HomeUIState.Loading)
    val uiState: StateFlow<HomeUIState> = _uiState.asStateFlow()

    init {
        loadHomeFeed()
    }

    /**
     * Loads clean shelves from the Innertube Home repository pipeline.
     *
     * @param customCookies Session cookies if authenticated.
     */
    fun loadHomeFeed(customCookies: String? = null) {
        viewModelScope.launch {
            _uiState.value = HomeUIState.Loading
            try {
                val sections = homeRepository.getHomeFeed(customCookies)
                if (sections.isNotEmpty()) {
                    _uiState.value = HomeUIState.Success(sections)
                } else {
                    _uiState.value = HomeUIState.Error("No clean songs available on the home feed.")
                }
            } catch (e: Exception) {
                _uiState.value = HomeUIState.Error(e.localizedMessage ?: "Failed to fetch clean home feed sections.")
            }
        }
    }
}
