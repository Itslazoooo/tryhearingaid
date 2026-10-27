package com.example.tryhearingaid.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tryhearingaid.data.AlertChannel
import com.example.tryhearingaid.data.EmergencyEvent
import com.example.tryhearingaid.data.FlashlightPattern
import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.data.SoundDetection
import com.example.tryhearingaid.emergency.SosController
import com.example.tryhearingaid.emergency.SosResult
import com.example.tryhearingaid.environmental.DescriptionCard
import com.example.tryhearingaid.environmental.NarrationController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─── SOS status ───────────────────────────────────────────────────────────────

/** Progress and outcome of the emergency SOS, shown on the Alerts screen (Requirement 10.3, 10.4). */
sealed class SosStatus {
    /** Nothing has been triggered, or the last result was dismissed. */
    object Idle : SosStatus()

    /** Messages are being sent. */
    object Sending : SosStatus()

    /** No emergency contacts are saved, so nothing was sent. */
    object NoContacts : SosStatus()

    /** Every contact received the message. */
    data class Sent(val count: Int) : SosStatus()

    /** Some contacts received it and some did not, after retries. [failed] holds the numbers that did not. */
    data class PartlyFailed(val sent: Int, val failed: List<String>) : SosStatus()

    /** No contact received it. The screen should offer to dial one of [failed] by hand. */
    data class AllFailed(val failed: List<String>) : SosStatus()

    /** SOS could not run, for example because it is not set up or the permission is missing. */
    data class Error(val message: String) : SosStatus()
}

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
    val emergencyEvent: EmergencyEvent? = null,
    val sosStatus: SosStatus = SosStatus.Idle,
    val awarenessEnabled: Boolean = false,
    val descriptionCards: List<DescriptionCard> = emptyList()
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

    /** User pressed the SOS button. Ignored while a previous SOS is still sending. */
    object TriggerSos : AlertIntent()

    /** User closed the SOS result message. */
    object DismissSosStatus : AlertIntent()

    /** Turn environmental awareness (spoken-style sound descriptions) on or off (Requirement 15). */
    data class SetAwareness(val enabled: Boolean) : AlertIntent()
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

/**
 * Manages alert settings and sound detection state for [AlertsScreen].
 *
 * - Exposes a single [StateFlow]<[AlertUiState]> that the screen collects.
 * - Handles [AlertIntent]s dispatched from the UI or from internal detection
 *   events forwarded by the [AudioProcessingService] / [DataRepository].
 *
 * Satisfies Requirements 9.3, 9.4, 9.5, 10.3, 10.4, 15.1 to 15.5.
 *
 * Both controllers are optional so the screen can be previewed and tested without Android
 * services. Without [sosController] the SOS button reports an error; without
 * [narrationController] awareness mode shows no cards.
 */
class AlertViewModel(
    private val sosController: SosController? = null,
    private val narrationController: NarrationController? = null
) : ViewModel() {

    companion object {
        /** Maximum number of recent detections shown in the feed. */
        const val MAX_RECENT_DETECTIONS = 50
    }

    private val _uiState = MutableStateFlow(AlertUiState())
    val uiState: StateFlow<AlertUiState> = _uiState.asStateFlow()

    init {
        // Mirror the narration cards into the UI state. Only touches viewModelScope when a
        // controller was supplied, so plain `AlertViewModel()` keeps working in JVM tests.
        narrationController?.let { controller ->
            viewModelScope.launch {
                controller.cards.collect { cards ->
                    _uiState.update { it.copy(descriptionCards = cards) }
                }
            }
        }
    }

    override fun onCleared() {
        narrationController?.stop()
        super.onCleared()
    }

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

            is AlertIntent.TriggerSos -> triggerSos()

            is AlertIntent.DismissSosStatus -> {
                // Do not hide the status while messages are still going out.
                if (_uiState.value.sosStatus !is SosStatus.Sending) {
                    _uiState.update { it.copy(sosStatus = SosStatus.Idle) }
                }
            }

            is AlertIntent.SetAwareness -> {
                if (intent.enabled) narrationController?.start() else narrationController?.stop()
                _uiState.update { it.copy(awarenessEnabled = intent.enabled) }
            }
        }
    }

    private fun triggerSos() {
        if (_uiState.value.sosStatus is SosStatus.Sending) return
        val controller = sosController
        if (controller == null) {
            _uiState.update { it.copy(sosStatus = SosStatus.Error("SOS is not available yet.")) }
            return
        }
        _uiState.update { it.copy(sosStatus = SosStatus.Sending) }
        viewModelScope.launch {
            val status = try {
                when (val result = controller.trigger()) {
                    SosResult.NoContacts -> SosStatus.NoContacts
                    is SosResult.Completed -> when {
                        result.failed.isEmpty() -> SosStatus.Sent(result.sentTo.size)
                        result.allFailed -> SosStatus.AllFailed(result.failed)
                        else -> SosStatus.PartlyFailed(result.sentTo.size, result.failed)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SosStatus.Error(e.message ?: "SOS failed.")
            }
            _uiState.update { it.copy(sosStatus = status) }
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
