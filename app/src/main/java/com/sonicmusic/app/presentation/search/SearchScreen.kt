package com.sonicmusic.app.presentation.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.presentation.components.SongCard
import com.sonicmusic.app.presentation.player.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    searchViewModel: SearchViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by searchViewModel.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var active by rememberSaveable { mutableStateOf(false) }
    
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // Search Bar with Status Bar Padding
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = statusBarPadding)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = { 
                        searchViewModel.search(it)
                        active = false
                    },
                    active = active,
                    onActiveChange = { active = it },
                    placeholder = { Text("Search songs...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (active || query.isNotEmpty()) {
                            IconButton(onClick = { 
                                if (query.isNotEmpty()) query = "" else active = false 
                            }) {
                                Icon(Icons.Default.Close, contentDescription = null)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                     // Search suggestions could go here
                }
            }

            // Results
            when (val state = uiState) {
                is SearchUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is SearchUiState.Success -> {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            bottom = navBarPadding + 160.dp,
                            start = 16.dp,
                            end = 16.dp
                        ),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.songs) { song ->
                            SongCard(
                                song = song,
                                onClick = { playerViewModel.playSong(song.id) }
                            )
                        }
                    }
                }
                is SearchUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = state.message, color = MaterialTheme.colorScheme.error)
                    }
                }
                is SearchUiState.Idle -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Search for your favorite music",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
