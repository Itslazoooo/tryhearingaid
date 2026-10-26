package com.example.tryhearingaid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tryhearingaid.audio.AudioDirection
import com.example.tryhearingaid.data.EnvironmentPreset
import com.example.tryhearingaid.ui.audio.AudioIntent
import com.example.tryhearingaid.ui.audio.AudioUiState
import com.example.tryhearingaid.ui.audio.AudioViewModel

// ─── Screen ───────────────────────────────────────────────────────────────────

/**
 * Main amplifier control screen.
 *
 * Exposes:
 * - Gain slider (0–40 dB, 1 dB steps) — Requirements 1.2, 6.3
 * - Adaptive amplification toggle — Requirement 6.1
 * - Environment preset selector — Requirement 2.4
 * - Directional hearing control — Requirement 4.1–4.3
 * - Loudness warning banner — Requirement 6.4
 *
 * All controls bind to [AudioViewModel] via [collectAsStateWithLifecycle].
 */
@Composable
fun AmplifierScreen(
    viewModel: AudioViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AmplifierContent(
        uiState = uiState,
        onIntent = viewModel::handleIntent
    )
}

// ─── Content (stateless, preview-friendly) ────────────────────────────────────

@Composable
fun AmplifierContent(
    uiState: AudioUiState,
    onIntent: (AudioIntent) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Loudness warning banner ───────────────────────────────────────────
        LoudnessWarningBanner(visible = uiState.loudnessWarning)

        // ── Start / Stop control ─────────────────────────────────────────────
        StartStopRow(
            isRunning = uiState.isRunning,
            onStart = { onIntent(AudioIntent.Start) },
            onStop = { onIntent(AudioIntent.Stop) }
        )

        // ── Gain slider ───────────────────────────────────────────────────────
        GainSliderCard(
            gainDb = uiState.gain,
            onGainChange = { onIntent(AudioIntent.SetGain(it)) }
        )

        // ── Adaptive amplification toggle ─────────────────────────────────────
        AdaptiveAmplificationCard(
            enabled = uiState.adaptiveEnabled,
            onToggle = { onIntent(AudioIntent.ToggleAdaptive(it)) }
        )

        // ── Environment preset selector ───────────────────────────────────────
        EnvironmentPresetCard(
            selected = uiState.noisePreset,
            onSelect = { onIntent(AudioIntent.SetNoisePreset(it)) }
        )

        // ── Directional hearing control ───────────────────────────────────────
        DirectionalHearingCard(
            available = uiState.isDirectionalAvailable,
            selected = uiState.directionalDirection,
            onSelect = { onIntent(AudioIntent.SetDirection(it)) }
        )
    }
}

// ─── Loudness warning banner ──────────────────────────────────────────────────

/**
 * Animated banner that appears when the output level exceeds 85 dB SPL.
 * Requirement 6.4.
 */
@Composable
private fun LoudnessWarningBanner(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                text = "Warning: Output level exceeds 85 dB SPL. Reduce volume to protect your hearing.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

// ─── Start / Stop row ─────────────────────────────────────────────────────────

@Composable
private fun StartStopRow(
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onStart,
            enabled = !isRunning,
            modifier = Modifier.weight(1f)
        ) {
            Text("Start")
        }
        OutlinedButton(
            onClick = onStop,
            enabled = isRunning,
            modifier = Modifier.weight(1f)
        ) {
            Text("Stop")
        }
    }
}

// ─── Gain slider card ─────────────────────────────────────────────────────────

/**
 * Slider controlling amplification gain from 0 to 40 dB in 1 dB increments.
 * Requirements 1.2, 6.3.
 */
@Composable
private fun GainSliderCard(
    gainDb: Float,
    onGainChange: (Float) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Gain",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${gainDb.toInt()} dB",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = gainDb,
                onValueChange = onGainChange,
                valueRange = 0f..40f,
                steps = 39,           // 40 steps → 41 positions → 1 dB per step
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "0 dB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "40 dB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─── Adaptive amplification card ─────────────────────────────────────────────

/**
 * Toggle for adaptive amplification (auto-gain based on ambient noise floor).
 * Requirement 6.1.
 */
@Composable
private fun AdaptiveAmplificationCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Adaptive Amplification",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Automatically adjusts gain to maintain consistent speech clarity",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

// ─── Environment preset card ──────────────────────────────────────────────────

/**
 * Dropdown selector for noise reduction environment presets.
 * Requirement 2.4.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnvironmentPresetCard(
    selected: EnvironmentPreset,
    onSelect: (EnvironmentPreset) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Environment Preset",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Tune noise reduction for your current surroundings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selected.displayName(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Preset") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    EnvironmentPreset.entries.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.displayName()) },
                            onClick = {
                                onSelect(preset)
                                expanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }
        }
    }
}

// ─── Directional hearing card ─────────────────────────────────────────────────

/**
 * Direction picker (Front / Left / Right / Rear) for beam-forming.
 *
 * Disabled with an informational message when [available] is false, meaning the
 * device has only a single microphone (Requirement 4.2–4.3).
 */
@Composable
private fun DirectionalHearingCard(
    available: Boolean,
    selected: AudioDirection,
    onSelect: (AudioDirection) -> Unit
) {
    val directions: List<Pair<AudioDirection, String>> = listOf(
        AudioDirection.Front to "Front",
        AudioDirection.Left  to "Left",
        AudioDirection.Right to "Right",
        AudioDirection.Rear  to "Rear"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (available)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Directional Hearing",
                style = MaterialTheme.typography.titleMedium,
                color = if (available)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Emphasize sounds from a specific direction",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (!available) {
                // Single-mic fallback message — Requirement 4.3
                Text(
                    text = "Multi-microphone device required",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Direction chip row — Requirement 4.1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    directions.forEach { (direction, label) ->
                        FilterChip(
                            selected = selected == direction,
                            onClick = { onSelect(direction) },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

// ─── Display name helpers ─────────────────────────────────────────────────────

private fun EnvironmentPreset.displayName(): String = when (this) {
    EnvironmentPreset.DEFAULT    -> "Default"
    EnvironmentPreset.RESTAURANT -> "Restaurant"
    EnvironmentPreset.CLASSROOM  -> "Classroom"
    EnvironmentPreset.OUTDOORS   -> "Outdoors"
    EnvironmentPreset.OFFICE     -> "Office"
}
