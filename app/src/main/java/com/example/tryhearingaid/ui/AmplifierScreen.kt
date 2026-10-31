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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.tryhearingaid.audio.AudioDiagnosticsAdvisor
import com.example.tryhearingaid.audio.FindingLevel
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tryhearingaid.audio.AudioDirection
import com.example.tryhearingaid.data.AudioSettings
import com.example.tryhearingaid.data.LatencyMode
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

        // ── Headphones required ───────────────────────────────────────────────
        HeadphonesBanner(uiState = uiState)

        // ── Start / Stop control ─────────────────────────────────────────────
        StartStopRow(
            isRunning = uiState.amplifierWanted,
            onStart = { onIntent(AudioIntent.Start) },
            onStop = { onIntent(AudioIntent.Stop) }
        )

        // ── Gain slider ───────────────────────────────────────────────────────
        GainSliderCard(
            gainDb = uiState.gain,
            outputLevelDb = uiState.outputLevelDb,
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

        // ── Sound quality: sample rate, noise reduction, voice focus ──────────
        SoundQualityCard(uiState = uiState, onIntent = onIntent)

        // ── Sound diagnostic ──────────────────────────────────────────────────
        SoundDiagnosticCard(uiState = uiState)

        // ── Directional hearing control ───────────────────────────────────────
        DirectionalHearingCard(
            available = uiState.isDirectionalAvailable,
            selected = uiState.directionalDirection,
            onSelect = { onIntent(AudioIntent.SetDirection(it)) }
        )
    }
}

// ─── Sound diagnostic ─────────────────────────────────────────────────────────

@Composable
private fun SoundDiagnosticCard(uiState: AudioUiState) {
    var open by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val d = uiState.diagnostics
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sound diagnostic", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
            }
            if (open) {
                Text(
                    "Check what is wrong with the sound while the amplifier is running.",
                    style = MaterialTheme.typography.bodySmall
                )
                uiState.findings.forEach { finding ->
                    val label = when (finding.level) {
                        FindingLevel.OK -> "OK"
                        FindingLevel.INFO -> "Note"
                        FindingLevel.WARNING -> "Warning"
                        FindingLevel.PROBLEM -> "Problem"
                    }
                    Text("$label: ${finding.text}", style = MaterialTheme.typography.bodyMedium)
                }
                if (d.running) {
                    val line = { v: Float -> String.format(java.util.Locale.US, "%.0f", v) }
                    Text(
                        "Input ${line(d.inputRmsDb)} dB (peak ${line(d.inputPeakDb)})\n" +
                            "Output ${line(d.outputRmsDb)} dB (peak ${line(d.outputPeakDb)})\n" +
                            "Output device: ${AudioDiagnosticsAdvisor.deviceName(d.outputDeviceType)}\n" +
                            "Dropouts: ${d.underrunsTotal}   Load: ${d.loadPercent}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(onClick = { clipboard.setText(AnnotatedString(uiState.diagnosticReport)) }) {
                    Text("Copy report")
                }
            }
        }
    }
}

// ─── Headphones banner ────────────────────────────────────────────────────────

@Composable
private fun HeadphonesBanner(uiState: AudioUiState) {
    val (text, error) = when {
        !uiState.headphonesConnected ->
            "Connect headphones, earbuds or a hearing aid to use the amplifier. " +
                "Through the phone's speaker it would squeal and echo." to true
        !uiState.amplifierWanted -> "Amplifier is off." to false
        uiState.isRunning -> "Amplifier is on." to false
        else -> "Starting… If nothing starts, try a lower sample rate below." to false
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

// ─── Sound quality card ───────────────────────────────────────────────────────

@Composable
private fun SoundQualityCard(uiState: AudioUiState, onIntent: (AudioIntent) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sound quality", style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Raw audio (no effects)", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "The microphone sound goes straight to your headphones, only louder by " +
                            "the gain. Noise reduction, voice focus, the equalizer and the " +
                            "loudness limiter are skipped. Turn it off to use them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = uiState.rawMode,
                    onCheckedChange = { onIntent(AudioIntent.SetRawMode(it)) }
                )
            }

            Text("Delay", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LatencyMode.entries.forEach { mode ->
                    FilterChip(
                        selected = uiState.latency == mode,
                        onClick = { onIntent(AudioIntent.SetLatency(mode)) },
                        label = { Text("${mode.estimatedDelayMs} ms") }
                    )
                }
            }
            Text(
                "${uiState.latency.label}: about ${uiState.latency.estimatedDelayMs} ms added by the " +
                    "app, plus your phone and headphones (Bluetooth adds a lot). Lower delay can " +
                    "crackle; higher delay is smoother. The sample rate below does not change " +
                    "the delay.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Sample rate", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioSettings.SAMPLE_RATES.forEach { hz ->
                    FilterChip(
                        selected = uiState.sampleRate == hz,
                        onClick = { onIntent(AudioIntent.SetSampleRate(hz)) },
                        label = { Text("${hz / 1000} kHz") }
                    )
                }
            }
            Text(
                "Keeps sound up to ${uiState.sampleRate / 2000} kHz. Higher is clearer but uses " +
                    "more battery. Changing it restarts the amplifier briefly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (uiState.rawMode) {
                Text(
                    "Noise reduction and voice focus are ignored while Raw audio is on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "Noise reduction: " + percentText(uiState.noiseStrength),
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = uiState.noiseStrength,
                onValueChange = { onIntent(AudioIntent.SetNoiseStrength(it)) },
                valueRange = 0f..1f
            )
            Text(
                "0% leaves the sound untouched. The preset above sets the style.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Voice focus", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Turns down sound below 250 Hz and above 4 kHz so speech stands out. " +
                            "This removes some of the sound range on purpose.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = uiState.voiceFocusEnabled,
                    onCheckedChange = { onIntent(AudioIntent.SetVoiceFocus(it)) }
                )
            }
            if (uiState.voiceFocusEnabled) {
                Text(
                    "Voice focus strength: " + percentText(uiState.voiceFocusStrength),
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = uiState.voiceFocusStrength,
                    onValueChange = { onIntent(AudioIntent.SetVoiceFocusStrength(it)) },
                    valueRange = 0f..1f
                )
            }
        }
    }
}

private fun percentText(strength: Float) = "${(strength * 100).toInt()}%"

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
    outputLevelDb: Float,
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
            Text(
                text = "Output level: ${outputLevelDb.toInt()} dB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
