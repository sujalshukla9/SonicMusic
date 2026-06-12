package com.sonicmusic.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.data.local.dao.ArtistPlayCount
import com.sonicmusic.app.data.local.entity.FollowedArtistEntity
import com.sonicmusic.app.domain.repository.ArtistRepository
import com.sonicmusic.app.domain.repository.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Unified artist item for the Artists screen.
 * Merges followed artists + history-based artists.
 */
data class MergedArtist(
    val name: String,
    val browseId: String? = null,
    val thumbnailUrl: String? = null,
    val playCount: Int = 0,
    val isFollowed: Boolean = false
)

@HiltViewModel
class ArtistsViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val artistRepository: ArtistRepository
) : ViewModel() {

    private val _historyArtists = MutableStateFlow<List<ArtistPlayCount>>(emptyList())
    private val _followedArtists = MutableStateFlow<List<FollowedArtistEntity>>(emptyList())

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Search functionality
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    // Merged artists: followed first, then history-only (deduplicated)
    private val _mergedArtists = combine(
        _followedArtists, _historyArtists
    ) { followed, history ->
        mergeArtists(followed, history)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Filtered by search query
    val filteredArtists: StateFlow<List<MergedArtist>> = combine(
        _mergedArtists, _searchQuery
    ) { artists, query ->
        if (query.isBlank()) artists
        else artists.filter { it.name.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadArtists()
    }

    private fun loadArtists() {
        viewModelScope.launch {
            _isLoading.value = true

            // Load history-based artists
            launch {
                historyRepository.getAllArtists().collect { artistList ->
                    _historyArtists.value = artistList
                    _isLoading.value = false
                }
            }

            // Load followed artists
            launch {
                artistRepository.getFollowedArtists().collect { followedList ->
                    _followedArtists.value = followedList
                }
            }
        }
    }

    private fun mergeArtists(
        followed: List<FollowedArtistEntity>,
        history: List<ArtistPlayCount>
    ): List<MergedArtist> {
        val historyMap = history.associateBy { it.artist.lowercase() }

        // Show ONLY followed artists (enriched with play count from history for sorting)
        return followed.map { f ->
            val historyEntry = historyMap[f.artistName.lowercase()]
            MergedArtist(
                name = f.artistName,
                browseId = f.browseId,
                thumbnailUrl = f.thumbnailUrl ?: historyEntry?.thumbnailUrl,
                playCount = historyEntry?.playCount ?: 0,
                isFollowed = true
            )
        }
    }

    fun toggleSearch() {
        _isSearchActive.value = !_isSearchActive.value
        if (!_isSearchActive.value) {
            _searchQuery.value = ""
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }
}
