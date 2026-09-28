package com.sonicmusic.app.features.search.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.domain.model.SearchSuggestionItem
import com.sonicmusic.app.domain.model.SuggestionType
import com.sonicmusic.app.features.player.presentation.sheets.SongActionSource
import com.sonicmusic.app.shared.components.songPressable
import com.sonicmusic.app.shared.components.SongThumbnail

@Composable
fun SearchSuggestionsDropdown(
    textSuggestions: List<String>,
    contentSuggestions: List<SearchSuggestionItem>,
    recentSearches: List<String>,
    isLoading: Boolean,
    onTextSuggestionClick: (String) -> Unit,
    onContentSuggestionClick: (SearchSuggestionItem) -> Unit,
    onRecentSearchClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding + 16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // 1. Loading Indicator at the top of the list if loading
                if (isLoading) {
                    item {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    }
                }

                // 2. Recent Searches that match
                if (recentSearches.isNotEmpty()) {
                    item {
                        Text(
                            text = "Recent Searches",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(recentSearches) { search ->
                        TextSuggestionRow(
                            icon = Icons.Rounded.History,
                            text = search,
                            onClick = { onRecentSearchClick(search) }
                        )
                    }

                    item {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                // 3. Rich Content Suggestions (limit to max 5 items)
                if (contentSuggestions.isNotEmpty()) {
                    item {
                        Text(
                            text = "Best Matches",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(contentSuggestions.take(5)) { item ->
                        RichContentSuggestionRow(
                            item = item,
                            onClick = { onContentSuggestionClick(item) }
                        )
                    }

                    if (textSuggestions.isNotEmpty()) {
                        item {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                // 4. Plain Text suggestions
                if (textSuggestions.isNotEmpty()) {
                    item {
                        Text(
                            text = "Suggestions",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(textSuggestions) { suggestion ->
                        TextSuggestionRow(
                            icon = Icons.Rounded.Search,
                            text = suggestion,
                            onClick = { onTextSuggestionClick(suggestion) }
                        )
                    }
                }

                // 5. Empty State
                if (recentSearches.isEmpty() && contentSuggestions.isEmpty() && textSuggestions.isEmpty() && !isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No suggestions found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextSuggestionRow(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Icon(
            imageVector = Icons.Rounded.NorthWest,
            contentDescription = "Copy query to search",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun RichContentSuggestionRow(
    item: SearchSuggestionItem,
    onClick: () -> Unit
) {
    val isArtist = item.type == SuggestionType.ARTIST
    val thumbnailShape = if (isArtist) CircleShape else RoundedCornerShape(8.dp)
    val isSongOrVideo = item.type == SuggestionType.SONG || item.type == SuggestionType.VIDEO

    val rowModifier = if (isSongOrVideo) {
        Modifier
            .fillMaxWidth()
            .songPressable(
                song = item.toSong(),
                source = SongActionSource.SEARCH,
                onClick = onClick
            )
    } else {
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    }

    Row(
        modifier = rowModifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(thumbnailShape)
        ) {
            SongThumbnail(
                artworkUrl = item.thumbnailUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            Text(
                text = formatSubtitle(item),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        val typeIcon = when (item.type) {
            SuggestionType.SONG -> Icons.Rounded.MusicNote
            SuggestionType.ARTIST -> Icons.Rounded.Person
            SuggestionType.ALBUM -> Icons.Rounded.Album
            SuggestionType.PLAYLIST -> Icons.Rounded.LibraryMusic
            SuggestionType.VIDEO -> Icons.Rounded.VideoLibrary
        }

        Icon(
            imageVector = typeIcon,
            contentDescription = item.type.name,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun formatSubtitle(item: SearchSuggestionItem): String {
    val cleanSubtitle = item.subtitle.trim()
    val typeLabel = when (item.type) {
        SuggestionType.SONG -> "Song"
        SuggestionType.ARTIST -> "Artist"
        SuggestionType.ALBUM -> "Album"
        SuggestionType.PLAYLIST -> "Playlist"
        SuggestionType.VIDEO -> "Video"
    }

    if (cleanSubtitle.startsWith(typeLabel, ignoreCase = true)) {
        return cleanSubtitle
    }

    if (cleanSubtitle.isEmpty()) {
        return typeLabel
    }

    return "$typeLabel • $cleanSubtitle"
}
