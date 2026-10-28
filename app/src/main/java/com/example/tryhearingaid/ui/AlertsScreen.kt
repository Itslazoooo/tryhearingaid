package com.example.tryhearingaid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.tryhearingaid.data.AlertChannel
import com.example.tryhearingaid.data.EmergencyEvent
import com.example.tryhearingaid.data.FlashlightPattern
import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.data.SoundDetection
import com.example.tryhearingaid.ui.alerts.AlertIntent
import com.example.tryhearingaid.ui.alerts.AlertUiState
import com.example.tryhearingaid.ui.alerts.AlertViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─── Screen entry point ───────────────────────────────────────────────────────

/**
 * Root entry point that creates (or receives) an [AlertViewModel] and passes
 * the collected state down to the stateless [AlertsScreenContent].
 *
 * Satisfies Requirements 9.3, 9.4, 9.5.
 */
@Composable
fun AlertsScreen(viewModel: AlertViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    AlertsScreenContent(
        uiState = uiState,
        onIntent = viewModel::handleIntent
    )
}

// ─── Stateless content ────────────────────────────────────────────────────────

/**
 * Stateless composable — receives [AlertUiState] and dispatches [AlertIntent]
 * via [onIntent].  Keeping the content stateless makes it straightforward to
 * preview and test in isolation.
 */
@Composable
fun AlertsScreenContent(
    uiState: AlertUiState,
    onIntent: (AlertIntent) -> Unit
) {
    // Emergency full-screen overlay takes priority over the scrollable content
    if (uiState.emergencyEvent != null) {
        EmergencyOverlay(
            event = uiState.emergencyEvent,
            onDismiss = { onIntent(AlertIntent.DismissEmergency) }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 1. Sound category toggles ─────────────────────────────────────────
        SectionHeader(title = "Sound Categories")
        Text(
            text = "Enable or disable individual sound categories. " +
                    "Only enabled categories will trigger alerts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SoundCategoryList(
            enabledCategories = uiState.enabledCategories,
            onToggle = { category, enabled ->
                onIntent(AlertIntent.ToggleCategory(category, enabled))
            }
        )

        Divider()

        // ── 2. Alert channels ─────────────────────────────────────────────────
        SectionHeader(title = "Alert Channels")
        Text(
            text = "Choose how you want to be notified when a sound is detected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AlertChannelCheckboxes(
            enabledChannels = uiState.alertChannels,
            onToggle = { channel, enabled ->
                onIntent(AlertIntent.ToggleChannel(channel, enabled))
            }
        )

        Divider()

        // ── 3. Flashlight pattern selector ────────────────────────────────────
        SectionHeader(title = "Flashlight Pattern")
        Text(
            text = "Select the flash pattern used when the flashlight alert channel is active.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlashlightPatternSelector(
            selected = uiState.flashlightPattern,
            onSelect = { pattern -> onIntent(AlertIntent.SetFlashlightPattern(pattern)) }
        )

        Divider()

        // ── 4. Recent detections feed ─────────────────────────────────────────
        SectionHeader(title = "Recent Detections")
        if (uiState.recentDetections.isEmpty()) {
            Text(
                text = "No detections yet. Sounds will appear here as they are detected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            RecentDetectionsFeed(detections = uiState.recentDetections)
        }

        // Bottom spacing so the last card isn't hidden behind the nav bar
        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ─── Sound category toggles ───────────────────────────────────────────────────

@Composable
private fun SoundCategoryList(
    enabledCategories: Set<SoundCategory>,
    onToggle: (SoundCategory, Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            SoundCategory.entries.forEachIndexed { index, category ->
                SoundCategoryRow(
                    category = category,
                    enabled = category in enabledCategories,
                    onToggle = { enabled -> onToggle(category, enabled) }
                )
                if (index < SoundCategory.entries.lastIndex) {
                    Divider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SoundCategoryRow(
    category: SoundCategory,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category.displayName(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (category.isLifeSafety()) {
                Text(
                    text = "Life-safety — always monitored",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle
        )
    }
}

// ─── Alert channel checkboxes ─────────────────────────────────────────────────

@Composable
private fun AlertChannelCheckboxes(
    enabledChannels: Set<AlertChannel>,
    onToggle: (AlertChannel, Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            AlertChannel.entries.forEachIndexed { index, channel ->
                AlertChannelRow(
                    channel = channel,
                    checked = channel in enabledChannels,
                    onChecked = { checked -> onToggle(channel, checked) }
                )
                if (index < AlertChannel.entries.lastIndex) {
                    Divider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertChannelRow(
    channel: AlertChannel,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChecked
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = channel.displayName(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = channel.description(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─── Flashlight pattern selector ─────────────────────────────────────────────

@Composable
private fun FlashlightPatternSelector(
    selected: FlashlightPattern,
    onSelect: (FlashlightPattern) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            FlashlightPattern.entries.forEachIndexed { index, pattern ->
                FlashlightPatternRow(
                    pattern = pattern,
                    selected = pattern == selected,
                    onSelect = { onSelect(pattern) }
                )
                if (index < FlashlightPattern.entries.lastIndex) {
                    Divider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FlashlightPatternRow(
    pattern: FlashlightPattern,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = pattern.displayName(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = pattern.description(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─── Recent detections feed ───────────────────────────────────────────────────

@Composable
private fun RecentDetectionsFeed(detections: List<SoundDetection>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        detections.forEach { detection ->
            DetectionCard(detection = detection)
        }
    }
}

@Composable
private fun DetectionCard(detection: SoundDetection) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Confidence dot indicator
            val dotColor = when {
                detection.confidence >= 0.80f -> MaterialTheme.colorScheme.error
                detection.confidence >= 0.50f -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.outline
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color = dotColor, shape = CircleShape)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = detection.category.displayName(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatTimestamp(detection.timestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Confidence percentage badge
            Text(
                text = "${(detection.confidence * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

// ─── Emergency full-screen overlay ───────────────────────────────────────────

/**
 * Full-screen [AlertDialog] shown when [EmergencyEvent] is non-null.
 *
 * Satisfies Requirement 10.2 — high-priority full-screen alert with category
 * info, confidence level, and a dismiss action.
 */
@Composable
private fun EmergencyOverlay(
    event: EmergencyEvent,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* intentionally non-dismissible via back-press */ },
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Emergency",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                text = "⚠ Emergency Alert",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = event.category.displayName(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Confidence: ${(event.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Detected at: ${formatTimestamp(event.detectedAtMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Please check your surroundings immediately.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Dismiss")
            }
        },
        containerColor = MaterialTheme.colorScheme.errorContainer
    )
}

// ─── Section header ───────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.NotificationsActive,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ─── Extension functions for display names ───────────────────────────────────

private fun SoundCategory.displayName(): String = when (this) {
    SoundCategory.DOORBELL -> "Doorbell"
    SoundCategory.BABY_CRYING -> "Baby Crying"
    SoundCategory.FIRE_ALARM -> "Fire Alarm"
    SoundCategory.CAR_HORN -> "Car Horn"
    SoundCategory.DOG_BARKING -> "Dog Barking"
    SoundCategory.NAME_CALLED -> "Name Called"
    SoundCategory.KNOCK -> "Knock on Door"
    SoundCategory.PHONE_RINGING -> "Phone Ringing"
    SoundCategory.WATER_RUNNING -> "Water Running"
    SoundCategory.GLASS_BREAKING -> "Glass Breaking"
    SoundCategory.SIREN -> "Siren"
    SoundCategory.SMOKE_ALARM -> "Smoke Alarm"
    SoundCategory.CROWD_CHEERING -> "Crowd Cheering"
    SoundCategory.BIRDS -> "Birds"
    SoundCategory.THUNDER -> "Thunder"
    SoundCategory.SPEECH -> "Speech"
    SoundCategory.CO_ALARM -> "CO Alarm"
    SoundCategory.FIRE_TRUCK_SIREN -> "Fire Truck Siren"
    SoundCategory.AMBULANCE_SIREN -> "Ambulance Siren"
    SoundCategory.POLICE_SIREN -> "Police Siren"
    SoundCategory.VERBAL_HELP -> "Verbal Call for Help"
}

/** Returns true for the six life-safety categories (Requirement 10.1). */
private fun SoundCategory.isLifeSafety(): Boolean = when (this) {
    SoundCategory.SMOKE_ALARM,
    SoundCategory.CO_ALARM,
    SoundCategory.FIRE_TRUCK_SIREN,
    SoundCategory.AMBULANCE_SIREN,
    SoundCategory.POLICE_SIREN,
    SoundCategory.VERBAL_HELP -> true
    else -> false
}

private fun AlertChannel.displayName(): String = when (this) {
    AlertChannel.SCREEN_BANNER -> "On-Screen Banner"
    AlertChannel.VIBRATION -> "Vibration"
    AlertChannel.FLASHLIGHT -> "Flashlight"
    AlertChannel.SMARTWATCH -> "Smartwatch"
}

private fun AlertChannel.description(): String = when (this) {
    AlertChannel.SCREEN_BANNER -> "Show a banner notification on your screen"
    AlertChannel.VIBRATION -> "Vibrate the device when a sound is detected"
    AlertChannel.FLASHLIGHT -> "Flash the device torch in the selected pattern"
    AlertChannel.SMARTWATCH -> "Send an alert to your connected smartwatch"
}

private fun FlashlightPattern.displayName(): String = when (this) {
    FlashlightPattern.SHORT -> "Short"
    FlashlightPattern.LONG -> "Long"
    FlashlightPattern.SOS -> "SOS"
}

private fun FlashlightPattern.description(): String = when (this) {
    FlashlightPattern.SHORT -> "A single brief flash per alert"
    FlashlightPattern.LONG -> "A sustained long flash per alert"
    FlashlightPattern.SOS -> "SOS Morse code pattern (· · · — — — · · ·)"
}

// ─── Timestamp formatting ─────────────────────────────────────────────────────

private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun formatTimestamp(timestampMs: Long): String =
    timeFormatter.format(Date(timestampMs))

// ─── Previews ─────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "Alerts Screen — normal state")
@Composable
private fun AlertsScreenPreview() {
    MaterialTheme {
        AlertsScreenContent(
            uiState = AlertUiState(
                enabledCategories = setOf(
                    SoundCategory.DOORBELL,
                    SoundCategory.FIRE_ALARM,
                    SoundCategory.SMOKE_ALARM
                ),
                alertChannels = setOf(AlertChannel.SCREEN_BANNER, AlertChannel.VIBRATION),
                flashlightPattern = FlashlightPattern.SOS,
                recentDetections = listOf(
                    SoundDetection(SoundCategory.DOORBELL, 0.92f, System.currentTimeMillis()),
                    SoundDetection(SoundCategory.DOG_BARKING, 0.65f, System.currentTimeMillis() - 5_000),
                    SoundDetection(SoundCategory.THUNDER, 0.48f, System.currentTimeMillis() - 15_000)
                )
            ),
            onIntent = {}
        )
    }
}

@Preview(showBackground = true, name = "Emergency Overlay")
@Composable
private fun EmergencyOverlayPreview() {
    MaterialTheme {
        EmergencyOverlay(
            event = EmergencyEvent(
                category = SoundCategory.SMOKE_ALARM,
                confidence = 0.94f,
                detectedAtMs = System.currentTimeMillis()
            ),
            onDismiss = {}
        )
    }
}
