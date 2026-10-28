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
 * With noise reduction at 0 and voice focus off, the sound is only amplified (plus the equalizer
 * and the safety limiter), and nothing in the audible range is filtered out.
 */
data class AudioSettings(
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

    /** One 10 ms frame at [sampleRate]. */
    val frameSize: Int get() = sampleRate / 100

    companion object {
        /** Each is a multiple of 16 kHz, which the sound recogniser needs, so it can be reduced exactly. */
        val SAMPLE_RATES = listOf(16_000, 32_000, 48_000)
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val DEFAULT_STRENGTH = 0.5f
    }
}
