package com.example.tryhearingaid.ui.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tryhearingaid.audio.AudioDirection
import com.example.tryhearingaid.audio.AudioEngine
import com.example.tryhearingaid.audio.AudioEngineState
import com.example.tryhearingaid.data.EnvironmentPreset
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── UI state ─────────────────────────────────────────────────────────────────

/**
 * Snapshot of everything the audio UI needs to render.
 *
 * - [gain] — current amplification in dB, always in [0f, 40f].
 * - [adaptiveEnabled] — whether auto-gain adjustment is active.
 * - [noisePreset] — selected environment noise preset.
 * - [directionalDirection] — selected beam-forming direction.
 * - [isRunning] — true while the [AudioEngine] is in [AudioEngineState.RUNNING].
 * - [loudnessWarning] — true when the last output frame exceeded the 85 dB SPL ceiling.
 * - [isDirectionalAvailable] — true when the device has two or more microphones.
 */
data class AudioUiState(
    val gain: Float = 0f,
    val adaptiveEnabled: Boolean = false,
    val noisePreset: EnvironmentPreset = EnvironmentPreset.DEFAULT,
    val directionalDirection: AudioDirection = AudioDirection.Front,
    val isRunning: Boolean = false,
    val loudnessWarning: Boolean = false,
    val isDirectionalAvailable: Boolean = false
)

// ─── Intents ──────────────────────────────────────────────────────────────────

/** All user actions that can be dispatched to [AudioViewModel]. */
sealed class AudioIntent {
    object Start : AudioIntent()
    object Stop : AudioIntent()
    data class SetGain(val dB: Float) : AudioIntent()
    data class ToggleAdaptive(val enabled: Boolean) : AudioIntent()
    data class SetNoisePreset(val preset: EnvironmentPreset) : AudioIntent()
    data class SetDirection(val direction: AudioDirection) : AudioIntent()
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

/**
 * Bridges the UI layer and the [AudioEngine].
 *
 * - Collects [AudioEngine.state] to keep [uiState.isRunning] in sync.
 * - Clamps [SetGain] values to [0f, 40f] before forwarding to the engine
 *   (Requirement 1.2 / 6.3).
 * - All heavy work is dispatched on [viewModelScope] so it runs on the main
 *   thread by default; [AudioEngine] implementations handle their own threading.
 */
@HiltViewModel
class AudioViewModel @Inject constructor(
    private val audioEngine: AudioEngine,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val GAIN_MIN = 0f
        private const val GAIN_MAX = 40f
    }

    private val _uiState = MutableStateFlow(
        AudioUiState(isDirectionalAvailable = detectDirectionalAvailability(context))
    )
    val uiState: StateFlow<AudioUiState> = _uiState.asStateFlow()

    init {
        // Keep isRunning in sync with the engine state
        viewModelScope.launch {
            audioEngine.state.collect { engineState ->
                _uiState.value = _uiState.value.copy(
                    isRunning = engineState == AudioEngineState.RUNNING
                )
            }
        }
    }

    /**
     * Dispatch a user intent to the engine or update local UI state.
     */
    fun handleIntent(intent: AudioIntent) {
        when (intent) {
            is AudioIntent.Start -> audioEngine.start()

            is AudioIntent.Stop -> audioEngine.stop()

            is AudioIntent.SetGain -> {
                val clamped = intent.dB.coerceIn(GAIN_MIN, GAIN_MAX)
                audioEngine.setGain(clamped)
                _uiState.value = _uiState.value.copy(gain = clamped)
            }

            is AudioIntent.ToggleAdaptive -> {
                audioEngine.setAdaptiveMode(intent.enabled)
                _uiState.value = _uiState.value.copy(adaptiveEnabled = intent.enabled)
            }

            is AudioIntent.SetNoisePreset -> {
                _uiState.value = _uiState.value.copy(noisePreset = intent.preset)
            }

            is AudioIntent.SetDirection -> {
                _uiState.value = _uiState.value.copy(directionalDirection = intent.direction)
            }
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Returns true if the device reports two or more built-in microphone inputs,
 * which is required for directional hearing (beam-forming) per Requirement 4.2–4.3.
 */
private fun detectDirectionalAvailability(context: Context): Boolean {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val micCount = audioManager
        .getDevices(AudioManager.GET_DEVICES_INPUTS)
        .count { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    return micCount >= 2
}
