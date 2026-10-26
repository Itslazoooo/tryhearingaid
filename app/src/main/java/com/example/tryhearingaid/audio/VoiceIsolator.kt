package com.example.tryhearingaid.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * Audio processing stage that isolates the dominant speaker's voice.
 *
 * When voice isolation is enabled the stage runs a TFLite speaker-separation
 * model on each frame and amplifies the dominant speaker while suppressing
 * other voices.  When no speech is detected for more than 5 consecutive
 * seconds the buffer is passed through unmodified (Requirement 3.4).
 *
 * Requirements: 3.1, 3.2, 3.3, 3.4
 */
interface VoiceIsolator : AudioProcessingStage {
    /**
     * Confidence score [0, 1] that the dominant speaker is currently being
     * tracked.  0 when no speech is present or isolation is disabled.
     */
    val dominantSpeakerConfidence: StateFlow<Float>
}
