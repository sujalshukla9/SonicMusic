package com.sonicmusic.app.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.presentation.player.PlayerViewModel

@Composable
fun HomeScreen(
    homeViewModel: HomeViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    
    // Gradient text brush
    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary
        )
    )

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (val state = uiState) {
            is HomeUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is HomeUiState.Success -> {
                LazyColumn(
                    contentPadding = PaddingValues(
                        top = statusBarPadding + 24.dp,
                        bottom = navBarPadding + 160.dp // Nav Bar + Player + Spacing
                    ),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header
                    item {
                        Text(
                            text = "Sonic Music",
                            style = TextStyle(
                                fontSize = 32.sp,
                                fontWeight = FontWeight.ExtraBold,
                                brush = gradientBrush
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    item {
                        if (state.content.listenAgain.isNotEmpty()) {
                            SectionHeader(title = "Listen Again")
                            SongHorizontalList(
                                songs = state.content.listenAgain,
                                onSongClick = { playerViewModel.playSong(it.id) }
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                    
                    item {
                        if (state.content.quickPicks.isNotEmpty()) {
                            SectionHeader(title = "Quick Picks")
                            SongHorizontalList(
                                songs = state.content.quickPicks,
                                onSongClick = { playerViewModel.playSong(it.id) }
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }

                    item {
                         if (state.content.newReleases.isNotEmpty()) {
                            SectionHeader(title = "New Releases")
                            SongHorizontalList(
                                songs = state.content.newReleases,
                                onSongClick = { playerViewModel.playSong(it.id) }
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }

                    item {
                        if (state.content.trending.isNotEmpty()) {
                             SectionHeader(title = "Trending")
                             SongHorizontalList(
                                 songs = state.content.trending,
                                 onSongClick = { playerViewModel.playSong(it.id) }
                             )
                             Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
            is HomeUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = state.message, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
