package com.example.tryhearingaid.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * Audio processing stage that steers the microphone pickup pattern toward a
 * specified [AudioDirection] using delay-and-sum beamforming.
 *
 * On single-microphone devices [isAvailable] is `false` and [process] acts as a
 * transparent pass-through.  On dual-microphone devices [isAvailable] is `true`
 * and the stage applies a delay-and-sum beam for the currently selected direction.
 *
 * Requirements: 6.2 — DirectionalFilter interface
 */
interface DirectionalFilter : AudioProcessingStage {

    /**
     * `true` when the device has at least two microphones and beamforming is
     * operational; `false` on single-mic devices (pass-through mode).
     */
    val isAvailable: StateFlow<Boolean>

    /**
     * Set the target listening direction.
     *
     * On dual-mic devices the beam pattern transitions to the new direction
     * within 500 ms via a smooth crossfade.  Calls on single-mic devices are
     * silently ignored.
     */
    fun setDirection(direction: AudioDirection)
}
