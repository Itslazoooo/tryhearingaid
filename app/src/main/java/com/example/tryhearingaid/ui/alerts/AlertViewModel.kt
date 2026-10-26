package com.example.tryhearingaid.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tryhearingaid.data.AlertChannel
import com.example.tryhearingaid.data.EmergencyEvent
import com.example.tryhearingaid.data.FlashlightPattern
import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.data.SoundDetection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─── UI state ─────────────────────────────────────────────────────────────────

/**
 * Snapshot of everything the Alerts screen needs to render.
 *
 * - [enabledCategories] — set of [SoundCategory] values the user has toggled on.
 *   Defaults to all 21 categories enabled.
 * - [alertChannels] — set of [AlertChannel] values the user has enabled.
 * - [flashlightPattern] — the configured [FlashlightPattern] for flashlight alerts.
 * - [recentDetections] — the N most-recent [SoundDetection] events, newest first.
 * - [emergencyEvent] — non-null when an emergency sound is actively detected;
 *   null when no emergency is in progress or after the user dismisses it.
 */
data class AlertUiState(
    val enabledCategories: Set<SoundCategory> = SoundCategory.entries.toSet(),
    val alertChannels: Set<AlertChannel> = setOf(AlertChannel.SCREEN_BANNER, AlertChannel.VIBRATION),
    val flashlightPattern: FlashlightPattern = FlashlightPattern.SHORT,
    val recentDetections: List<SoundDetection> = emptyList(),
    val emergencyEvent: EmergencyEvent? = null
)

// ─── Intents ──────────────────────────────────────────────────────────────────

/** All user actions that can be dispatched to [AlertViewModel]. */
sealed class AlertIntent {
    /** Toggle a single [SoundCategory] on or off. */
    data class ToggleCategory(val category: SoundCategory, val enabled: Boolean) : AlertIntent()

    /** Toggle a single [AlertChannel] on or off. */
    data class ToggleChannel(val channel: AlertChannel, val enabled: Boolean) : AlertIntent()

    /** Change the flashlight alert pattern. */
    data class SetFlashlightPattern(val pattern: FlashlightPattern) : AlertIntent()

    /** User dismissed the emergency overlay. */
    object DismissEmergency : AlertIntent()

    /** A new [SoundDetection] arrived from the classifier (internal use). */
    data class OnDetection(val detection: SoundDetection) : AlertIntent()

    /** An emergency event was triggered (internal use). */
    data class OnEmergency(val event: EmergencyEvent) : AlertIntent()
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

/**
 * Manages alert settings and sound detection state for [AlertsScreen].
 *
 * - Exposes a single [StateFlow]<[AlertUiState]> that the screen collects.
 * - Handles [AlertIntent]s dispatched from the UI or from internal detection
 *   events forwarded by the [AudioProcessingService] / [DataRepository].
 *
 * Satisfies Requirements 9.3, 9.4, 9.5.
 */
class AlertViewModel : ViewModel() {

    companion object {
        /** Maximum number of recent detections shown in the feed. */
        const val MAX_RECENT_DETECTIONS = 50
    }

    private val _uiState = MutableStateFlow(AlertUiState())
    val uiState: StateFlow<AlertUiState> = _uiState.asStateFlow()

    // ── Intent handling ───────────────────────────────────────────────────────

    fun handleIntent(intent: AlertIntent) {
        when (intent) {
            is AlertIntent.ToggleCategory -> {
                val current = _uiState.value.enabledCategories.toMutableSet()
                if (intent.enabled) current.add(intent.category) else current.remove(intent.category)
                _uiState.value = _uiState.value.copy(enabledCategories = current)
            }

            is AlertIntent.ToggleChannel -> {
                val current = _uiState.value.alertChannels.toMutableSet()
                if (intent.enabled) current.add(intent.channel) else current.remove(intent.channel)
                _uiState.value = _uiState.value.copy(alertChannels = current)
            }

            is AlertIntent.SetFlashlightPattern -> {
                _uiState.value = _uiState.value.copy(flashlightPattern = intent.pattern)
            }

            is AlertIntent.DismissEmergency -> {
                _uiState.value = _uiState.value.copy(emergencyEvent = null)
            }

            is AlertIntent.OnDetection -> {
                // Only enqueue detections for enabled categories (Requirement 9.5)
                if (intent.detection.category !in _uiState.value.enabledCategories) return
                val updated = buildList {
                    add(intent.detection)
                    addAll(_uiState.value.recentDetections)
                }.take(MAX_RECENT_DETECTIONS)
                _uiState.value = _uiState.value.copy(recentDetections = updated)
            }

            is AlertIntent.OnEmergency -> {
                _uiState.value = _uiState.value.copy(emergencyEvent = intent.event)
            }
        }
    }

    // ── Convenience helpers for testing / preview ─────────────────────────────

    /**
     * Inject a fake detection — used in Compose previews and unit tests.
     */
    fun injectDetection(detection: SoundDetection) {
        viewModelScope.launch { handleIntent(AlertIntent.OnDetection(detection)) }
    }

    /**
     * Inject a fake emergency event — used in Compose previews and unit tests.
     */
    fun injectEmergency(event: EmergencyEvent) {
        viewModelScope.launch { handleIntent(AlertIntent.OnEmergency(event)) }
    }
}
