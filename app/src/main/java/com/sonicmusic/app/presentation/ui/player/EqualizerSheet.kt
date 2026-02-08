package com.sonicmusic.app.presentation.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.domain.usecase.EqualizerBand
import kotlin.math.roundToInt

/**
 * Equalizer Bottom Sheet
 *
 * Material 3 bottom sheet for configuring the audio equalizer.
 * Features:
 * - Enable/Disable toggle
 * - Preset selection dropdown
 * - Band sliders with frequency labels
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    bands: List<EqualizerBand>,
    onBandLevelChange: (Short, Short) -> Unit,
    presets: List<String>,
    currentPreset: String?,
    onPresetSelect: (Short) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isVisible) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Equalizer",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Presets Dropdown
            var expanded by remember { mutableStateOf(false) }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                TextField(
                    value = currentPreset ?: "Custom",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    label = { Text("Preset") },
                    colors = ExposedDropdownMenuDefaults.textFieldColors()
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    presets.forEachIndexed { index, preset ->
                        DropdownMenuItem(
                            text = { Text(preset) },
                            onClick = {
                                onPresetSelect(index.toShort())
                                expanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            trailingIcon = if (preset == currentPreset) {
                                { Icon(Icons.Default.Check, null) }
                            } else null
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Band Sliders
            if (bands.isNotEmpty()) {
                bands.forEach { band ->
                    BandSlider(
                        band = band,
                        onLevelChange = { level ->
                            onBandLevelChange(band.id, level)
                        },
                        enabled = enabled
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            } else {
                Text(
                    text = "Equalizer not available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BandSlider(
    band: EqualizerBand,
    onLevelChange: (Short) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = band.getFrequencyString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            // Format level as dB (mB / 100)
            val db = band.level / 100f
            Text(
                text = String.format("%+.1f dB", db),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Slider(
            value = band.level.toFloat(),
            onValueChange = { onLevelChange(it.roundToInt().toShort()) },
            valueRange = band.minLevel.toFloat()..band.maxLevel.toFloat(),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
