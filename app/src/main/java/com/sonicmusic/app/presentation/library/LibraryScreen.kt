package com.sonicmusic.app.presentation.library

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.presentation.components.SongCard
import com.sonicmusic.app.presentation.player.PlayerViewModel

@Composable
fun LibraryScreen(
    libraryViewModel: LibraryViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val likedSongs by libraryViewModel.likedSongs.collectAsStateWithLifecycle()

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            contentPadding = PaddingValues(
                top = statusBarPadding + 24.dp,
                bottom = navBarPadding + 160.dp
            ),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Text(
                    text = "Your Library",
                    style = TextStyle(
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            items(likedSongs) { song ->
                SongCard(
                    song = song,
                    onClick = { playerViewModel.playSong(song.id) }
                )
            }
        }
    }
}
