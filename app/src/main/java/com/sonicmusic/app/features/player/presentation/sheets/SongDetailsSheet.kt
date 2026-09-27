package com.sonicmusic.app.features.player.presentation.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.features.player.audio.AudioCapabilityDetector
import com.sonicmusic.app.shared.components.SongThumbnail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongDetailsSheet(
    isVisible: Boolean,
    song: Song?,
    streamInfo: AudioStreamInfo?,
    downloadedFileSizeBytes: Long?,
    durationMs: Long,
    onDismiss: () -> Unit,
    audioCapability: AudioCapabilityDetector.AudioCapability = AudioCapabilityDetector.AudioCapability(),
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (!isVisible || song == null) return

    val colorScheme = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colorScheme.surfaceContainerHigh,
        scrimColor = colorScheme.scrim.copy(alpha = 0.62f),
        contentColor = colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 32.dp)
        ) {
            // ── Header: thumbnail + title + artist ─────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SongThumbnail(
                        artworkUrl = song.thumbnailUrl,
                        modifier = Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = song.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Song Info Section ───────────────────────────────────────
            SongDetailsSectionHeader(title = "Song Info")

            Spacer(modifier = Modifier.height(4.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = colorScheme.surfaceContainer
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {

                    SongDetailsRow(
                        icon = Icons.Rounded.MusicNote,
                        label = "Title",
                        value = song.title
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    SongDetailsRow(
                        icon = Icons.Rounded.Person,
                        label = "Artist",
                        value = song.artist
                    )

                    if (!song.album.isNullOrBlank()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        SongDetailsRow(
                            icon = Icons.Rounded.Album,
                            label = "Album",
                            value = song.album
                        )
                    }

                    if (song.year != null && song.year > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        SongDetailsRow(
                            icon = Icons.Rounded.CalendarMonth,
                            label = "Year",
                            value = song.year.toString()
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    val displayDuration = if (song.duration > 0) {
                        song.formattedDuration()
                    } else if (durationMs > 0) {
                        val minutes = (durationMs / 1000) / 60
                        val seconds = (durationMs / 1000) % 60
                        String.format("%d:%02d", minutes, seconds)
                    } else {
                        "Unknown"
                    }

                    SongDetailsRow(
                        icon = Icons.Rounded.Schedule,
                        label = "Duration",
                        value = displayDuration
                    )

                    if (song.viewCount != null && song.viewCount > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        SongDetailsRow(
                            icon = Icons.Rounded.QueryStats,
                            label = "Views",
                            value = formatViewCount(song.viewCount)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Audio Quality Section ───────────────────────────────────
            SongDetailsSectionHeader(title = "Audio Quality")

            Spacer(modifier = Modifier.height(4.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = colorScheme.surfaceContainer
            ) {
                if (streamInfo != null) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {

                        // Quality badge row with colored chip
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.GraphicEq,
                                    contentDescription = null,
                                    tint = colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = " Quality",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = streamInfo.qualityBadge,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )

                        SongDetailsRow(
                            icon = Icons.Rounded.AudioFile,
                            label = "Codec",
                            value = if (streamInfo.isLocal) "Local File" else streamInfo.codec.uppercase()
                        )

                        if (streamInfo.bitrate > 0 && !streamInfo.isLocal) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                            SongDetailsRow(
                                icon = Icons.Rounded.GraphicEq,
                                label = "Bitrate",
                                value = "${streamInfo.bitrate} kbps"
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )

                        SongDetailsRow(
                            icon = Icons.Rounded.GraphicEq,
                            label = "Sample Rate",
                            value = "${streamInfo.sampleRate / 1000f}".removeSuffix(".0") + " kHz"
                        )

                        if (streamInfo.bitDepth > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                            SongDetailsRow(
                                icon = Icons.Rounded.GraphicEq,
                                label = "Bit Depth",
                                value = "${streamInfo.bitDepth}-bit"
                            )
                        }

                        if (streamInfo.channelCount > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                            val channelLabel = when (streamInfo.channelCount) {
                                1 -> "Mono"
                                2 -> "Stereo"
                                6 -> "5.1 Surround"
                                8 -> "7.1 Surround"
                                else -> "${streamInfo.channelCount}ch"
                            }
                            SongDetailsRow(
                                icon = Icons.Rounded.GraphicEq,
                                label = "Channels",
                                value = channelLabel
                            )
                        }

                        if (streamInfo.containerFormat.isNotBlank() && streamInfo.containerFormat != "Unknown") {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                            SongDetailsRow(
                                icon = Icons.Rounded.AudioFile,
                                label = "Container",
                                value = streamInfo.containerFormat
                            )
                        }

                        // Signal path row
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        SongDetailsRow(
                            icon = Icons.Rounded.GraphicEq,
                            label = "Signal Path",
                            value = streamInfo.getSignalPath()
                        )
                    }
                } else {
                    // Stream info not yet available
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (isPlaying)
                                "Detecting stream quality…"
                            else
                                "Stream quality info will appear once playback starts.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Spatial Audio Section ────────────────────────────────────
            // Only shown when the Spatializer API reports capability (API 32+).
            // The section is intentionally informational-only (no toggle) — see plan.
            if (audioCapability.hasSpatializer) {
                Spacer(modifier = Modifier.height(12.dp))

                SongDetailsSectionHeader(title = "Spatial Audio")

                Spacer(modifier = Modifier.height(4.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = colorScheme.surfaceContainer
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {

                        // Status row — Active / Available-not-enabled
                        val spatialStatusColor = if (audioCapability.spatializerActive)
                            Color(0xFF00BCD4)  // teal — matches badge pill color
                        else
                            colorScheme.onSurfaceVariant

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.SurroundSound,
                                    contentDescription = null,
                                    tint = spatialStatusColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Status",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = if (audioCapability.spatializerActive)
                                            spatialStatusColor.copy(alpha = 0.14f)
                                        else
                                            colorScheme.surfaceContainerHighest,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (audioCapability.spatializerActive) "Active"
                                           else "Available, not enabled",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (audioCapability.spatializerActive)
                                        spatialStatusColor
                                    else
                                        colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Immersive audio level
                        if (audioCapability.spatializerLevelName.isNotBlank() &&
                            audioCapability.spatializerLevelName != "None") {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                            SongDetailsRow(
                                icon = Icons.Rounded.GraphicEq,
                                label = "Immersive Level",
                                value = audioCapability.spatializerLevelName
                            )
                        }

                        // canBeSpatialized() result for the current format
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        SongDetailsRow(
                            icon = Icons.Rounded.AudioFile,
                            label = "Format Eligible",
                            value = if (audioCapability.canBeSpatializedStereoOpus) "Yes" else "No"
                        )
                    }
                }
            }

            // ── File Size Section (downloaded or estimated stream) ──────────────
            val isDownloaded = downloadedFileSizeBytes != null && downloadedFileSizeBytes > 0
            val durationSec = if (song.duration > 0) song.duration else (durationMs / 1000).toInt()
            val fileSizeLabel = when {
                isDownloaded -> formatFileSize(downloadedFileSizeBytes!!)
                streamInfo != null && streamInfo.bitrate > 0 && durationSec > 0 -> {
                    val estimatedBytes = (streamInfo.bitrate * 1000L * durationSec) / 8
                    "${formatFileSize(estimatedBytes)} (Estimated)"
                }
                else -> null
            }

            if (fileSizeLabel != null) {
                Spacer(modifier = Modifier.height(12.dp))

                SongDetailsSectionHeader(title = if (isDownloaded) "Downloaded File" else "Stream Info")

                Spacer(modifier = Modifier.height(4.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = colorScheme.surfaceContainer
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        SongDetailsRow(
                            icon = Icons.Rounded.SdStorage,
                            label = "File Size",
                            value = fileSizeLabel
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SongDetailsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun SongDetailsRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(0.45f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.55f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

private fun formatViewCount(views: Long): String {
    return when {
        views >= 1_000_000_000 -> "${views / 1_000_000_000}B views"
        views >= 1_000_000     -> "${views / 1_000_000}M views"
        views >= 1_000         -> "${views / 1_000}K views"
        else                   -> "$views views"
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> String.format("%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576     -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1_024         -> String.format("%.0f KB", bytes / 1_024.0)
        else                   -> "$bytes B"
    }
}
