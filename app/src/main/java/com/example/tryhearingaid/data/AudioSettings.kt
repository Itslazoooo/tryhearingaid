package com.example.tryhearingaid.data

/**
 * The amplifier's sound-quality settings, chosen by the user and saved between runs.
 *
 * - [sampleRate]: samples per second the microphone is read at. Higher keeps more of the high
 *   frequencies (the highest sound that can be kept is half this number), but uses more battery.
 * - [noisePreset] and [noiseStrength]: how hard background noise is turned down. 0 switches noise
 *   reduction off; 0.5 is the preset exactly as designed; 1 is twice as strong.
 * - [voiceFocusEnabled] and [voiceFocusStrength]: emphasise the range where speech lives
 *   (roughly 250 Hz to 4 kHz) by turning everything outside it down. This removes high and low
 *   frequencies on purpose, so it is off unless chosen.
 *
 * - [rawMode]: when on (the default) the microphone sound goes straight to the headphones with
 *   only the volume applied; the noise, voice focus and equalizer settings are ignored.
 * - [latency]: how big the audio buffers are. Small buffers give less delay but more crackling;
 *   large buffers give a smoother sound but more delay. This, not the sample rate, sets the delay.
 *
 * With noise reduction at 0 and voice focus off, the sound is only amplified (plus the equalizer
 * and the safety limiter), and nothing in the audible range is filtered out.
 */
data class AudioSettings(
    val rawMode: Boolean = true,
    val latency: LatencyMode = LatencyMode.BALANCED,
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val noisePreset: EnvironmentPreset = EnvironmentPreset.DEFAULT,
    val noiseStrength: Float = DEFAULT_STRENGTH,
    val voiceFocusEnabled: Boolean = false,
    val voiceFocusStrength: Float = DEFAULT_STRENGTH
) {
    /** A copy whose values are all inside what the app supports (for values read from storage). */
    fun sanitized(): AudioSettings = copy(
        sampleRate = if (sampleRate in SAMPLE_RATES) sampleRate else DEFAULT_SAMPLE_RATE,
        noiseStrength = noiseStrength.coerceIn(0f, 1f),
        voiceFocusStrength = voiceFocusStrength.coerceIn(0f, 1f)
    )

    /** Samples in one frame at [sampleRate] for the chosen [latency]. */
    val frameSize: Int get() = sampleRate * latency.frameMs / 1000

    /** The engine settings these choices turn into. */
    fun toAudioConfig() = com.example.tryhearingaid.audio.AudioConfig(
        sampleRate = sampleRate,
        frameSize = frameSize,
        prefillFrames = latency.prefillFrames,
        bufferFrames = latency.bufferFrames,
        rawMode = rawMode
    )

    companion object {
        /** Each is a multiple of 16 kHz, which the sound recogniser needs, so it can be reduced exactly. */
        val SAMPLE_RATES = listOf(16_000, 32_000, 48_000)
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val DEFAULT_STRENGTH = 0.5f
    }
}

/**
 * Trade-off between delay and dropouts. [estimatedDelayMs] is the delay the app adds (capture,
 * queue and playback); the phone and the headphones add their own on top, and Bluetooth adds a lot.
 */
enum class LatencyMode(
    val label: String,
    val frameMs: Int,
    val prefillFrames: Int,
    val bufferFrames: Int
) {
    LOWEST("Lowest delay", 5, 0, 2),
    LOW("Low", 10, 1, 4),
    BALANCED("Balanced", 10, 3, 8),
    SMOOTH("Smooth", 20, 3, 8),
    SMOOTHEST("Smoothest", 40, 3, 8);

    val estimatedDelayMs: Int get() = frameMs * (2 + prefillFrames)
}
