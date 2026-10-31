package com.example.tryhearingaid.audio

import kotlinx.coroutines.flow.StateFlow

// ─── AudioDevice ──────────────────────────────────────────────────────────────

sealed class AudioDevice {
    /** System default output (speaker or wired headphones). */
    object Default : AudioDevice()

    /** Bluetooth device identified by its hardware address. */
    data class Bluetooth(val address: String) : AudioDevice()

    /** Phone earpiece (built-in receiver). */
    object Earpiece : AudioDevice()

    /** Loudspeaker. */
    object Speaker : AudioDevice()
}

// ─── AudioConfig ──────────────────────────────────────────────────────────────

data class AudioConfig(
    val sampleRate: Int = 16_000,
    val channelCount: Int = 1,
    val frameSize: Int = 160,           // 10 ms at 16 kHz
    val outputDevice: AudioDevice = AudioDevice.Default,
    /** Silent frames queued before playback starts. More means more delay but fewer dropouts. */
    val prefillFrames: Int = 3,
    /** How many frames the system audio buffers hold. More means more delay but fewer dropouts. */
    val bufferFrames: Int = 8,
    /**
     * Raw mode: the microphone sound goes straight to the output with only the gain applied.
     * No noise reduction, voice focus, equalizer, adaptive gain or loudness limiter (the sound
     * recogniser still listens, without changing the sound).
     */
    val rawMode: Boolean = false
)

// ─── AudioEngineState ─────────────────────────────────────────────────────────

enum class AudioEngineState { IDLE, STARTING, RUNNING, PAUSED, ERROR }

// ─── AudioProcessingStage ─────────────────────────────────────────────────────

/**
 * A single link in the audio processing chain.
 *
 * Implementors MUST override the three-argument [process] function.
 * The single-argument convenience overload is provided for backward compatibility
 * and delegates to the full-signature method with default values.
 */
interface AudioProcessingStage {

    /** Whether this stage is currently active. Disabled stages pass audio through unchanged. */
    val isEnabled: Boolean

    /**
     * True for stages that only listen and never change the sound. They keep running in the
     * amplifier's raw mode; every other stage is skipped there.
     */
    val runsInRawMode: Boolean get() = false

    /**
     * Process [buffer] in-place (or return a new array) given [sampleRate] and [channelCount].
     * Must not block for longer than the frame duration (typically 10 ms).
     */
    fun process(buffer: ShortArray, sampleRate: Int, channelCount: Int): ShortArray

    /**
     * Convenience overload for stages that do not need sample-rate or channel info.
     * Delegates to [process] with `sampleRate = 16_000` and `channelCount = 1`.
     *
     * Backward-compatible: existing code that only implements this single-arg variant
     * should override it explicitly; new code should override the three-arg variant.
     */
    fun process(buffer: ShortArray): ShortArray = process(buffer, 16_000, 1)

    /** Enable or disable this processing stage at runtime. */
    fun setEnabled(enabled: Boolean)
}

// ─── AudioDirection ───────────────────────────────────────────────────────────

sealed class AudioDirection {
    object Front : AudioDirection()
    object Left : AudioDirection()
    object Right : AudioDirection()
    object Rear : AudioDirection()
    data class Custom(val azimuthDegrees: Float) : AudioDirection()
}

// ─── AudioEngine ──────────────────────────────────────────────────────────────

interface AudioEngine {
    /** Live state of this engine. */
    val state: StateFlow<AudioEngineState>

    /** A health reading of the audio, refreshed about twice a second while running. */
    val diagnostics: StateFlow<AudioDiagnostics>
        get() = kotlinx.coroutines.flow.MutableStateFlow(AudioDiagnostics())

    /**
     * Currently applied gain in dB, always in the range [0, 40].
     * Exposed as a read-only property so callers can inspect the clamped value.
     */
    val currentGain: Float

    /** Start recording and playback with the given [config]. */
    fun start(config: AudioConfig = AudioConfig())

    /** Stop recording and playback, releasing all audio resources. */
    fun stop()

    /**
     * Set the amplification gain.
     * Values are clamped to [0, 40] dB and quantized to the nearest 1 dB increment.
     */
    fun setGain(dB: Float)

    /** Enable or disable adaptive amplification (auto-gain based on ambient noise). */
    fun setAdaptiveMode(enabled: Boolean)

    /** Append [stage] to the end of the processing chain. */
    fun addProcessingStage(stage: AudioProcessingStage)

    /**
     * Return the current output sound pressure level in dB SPL.
     * Returns 0f when the engine is not [AudioEngineState.RUNNING].
     */
    fun getCurrentSplDb(): Float

    /**
     * Route audio output to [device].
     *
     * If the engine is currently running it is restarted with the new output
     * device so that processed audio is sent to the target (e.g. a connected A2DP
     * Bluetooth device). If the engine is idle the device is remembered and applied
     * on the next [start] call.
     *
     * Requirement 14.2
     */
    fun routeOutput(device: AudioDevice)
}
