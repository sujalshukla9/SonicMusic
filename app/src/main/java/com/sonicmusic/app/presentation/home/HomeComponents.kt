package com.sonicmusic.app.presentation.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.presentation.components.SongCard

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun SongHorizontalList(
    songs: List<Song>,
    onSongClick: (Song) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(songs) { song ->
             // Reusing SongCard but forcing width for horizontal list
             Box(modifier = Modifier.width(160.dp)) {
                SongCard(
                     song = song,
                     onClick = { onSongClick(song) }
                )
             }
        }
    }
}
