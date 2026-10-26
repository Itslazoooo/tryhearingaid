package com.example.tryhearingaid.audio

import com.example.tryhearingaid.data.EnvironmentPreset
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Audio processing stage that suppresses background noise.
 *
 * When the TFLite model loads successfully, noise suppression is performed by
 * the neural network.  If the model fails to load, the stage automatically
 * activates a classical spectral-subtraction fallback and exposes
 * [isModelLoaded] = false so the UI can inform the user.
 *
 * Requirements: 2.1, 2.2, 2.3, 2.4, 2.5
 */
interface NoiseReducer : AudioProcessingStage {
    /** True when the TFLite noise-suppression model is loaded and ready. */
    val isModelLoaded: StateFlow<Boolean>

    /**
     * Emits snackbar messages destined for the ViewModel / UI layer.
     * Emitted when the model fails to load or other notable events occur.
     */
    val snackbarMessages: SharedFlow<String>

    /**
     * Switch to the suppression profile tuned for [preset].
     * Takes effect on the next processed audio frame.
     */
    fun setEnvironmentPreset(preset: EnvironmentPreset)

    /** Return the currently active [EnvironmentPreset]. */
    fun getActivePreset(): EnvironmentPreset
}
