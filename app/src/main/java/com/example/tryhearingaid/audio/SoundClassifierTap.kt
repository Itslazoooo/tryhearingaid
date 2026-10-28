package com.example.tryhearingaid.audio

import com.example.tryhearingaid.classification.SoundClassifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feeds the live microphone audio to the sound classifier (Requirements 9 and 10).
 *
 * It is a pass-through stage of the audio pipeline: it never changes the audio. It keeps the
 * most recent [windowSamples] samples and, once a full window has been collected, hands a
 * copy of it to [sink] every [hopSamples] samples.
 *
 * Why a window: YAMNet judges about one second of sound at a time (15 600 samples at 16 kHz).
 * The pipeline delivers 10 ms frames, so passing single frames would make the classifier look at
 * 10 ms of sound followed by silence. With the defaults the classifier gets a fresh one-second
 * window about every half second.
 *
 * Place it first in the pipeline so the classifier hears the raw microphone sound, before noise
 * reduction and amplification change it.
 *
 * It is a singleton so that adding it to the engine again after the service restarts adds nothing
 * new (the engine ignores a stage it already has).
 *
 * Only 16 kHz mono audio is used. Any other format is passed through untouched and not classified.
 *
 * Runs on the audio thread, so it must stay quick. [sink] is expected to queue the work and
 * return; an exception from it is swallowed so a classifier problem can never interrupt playback.
 */
@Singleton
class SoundClassifierTap(
    private val sink: (ShortArray) -> Unit,
    private val windowSamples: Int = DEFAULT_WINDOW_SAMPLES,
    private val hopSamples: Int = DEFAULT_HOP_SAMPLES
) : AudioProcessingStage {

    /** Used by Hilt: send windows to the app's [SoundClassifier]. */
    @Inject
    constructor(classifier: SoundClassifier) : this(classifier::submitAudioFrame)

    init {
        require(windowSamples > 0) { "windowSamples must be positive" }
        require(hopSamples in 1..windowSamples) { "hopSamples must be between 1 and windowSamples" }
    }

    // Circular buffer of the latest samples. Only touched from the audio thread.
    private val ring = ShortArray(windowSamples)
    private var writePosition = 0
    private var filled = 0
    private var samplesSinceEmit = 0

    @Volatile
    private var enabled = true

    override val isEnabled: Boolean get() = enabled

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override fun process(buffer: ShortArray, sampleRate: Int, channelCount: Int): ShortArray {
        if (sampleRate != EXPECTED_SAMPLE_RATE || channelCount != 1) return buffer

        for (sample in buffer) {
            ring[writePosition] = sample
            writePosition = (writePosition + 1) % windowSamples
        }
        filled = minOf(windowSamples, filled + buffer.size)
        samplesSinceEmit += buffer.size

        if (filled == windowSamples && samplesSinceEmit >= hopSamples) {
            samplesSinceEmit = 0
            try {
                sink(snapshot())
            } catch (e: Exception) {
                // Never let the classifier interrupt the audio path.
            }
        }
        return buffer
    }

    /** The latest [windowSamples] samples in time order, oldest first, as a new array. */
    private fun snapshot(): ShortArray {
        val out = ShortArray(windowSamples)
        // When the buffer is full the oldest sample sits at the write position.
        val tail = windowSamples - writePosition
        System.arraycopy(ring, writePosition, out, 0, tail)
        System.arraycopy(ring, 0, out, tail, writePosition)
        return out
    }

    companion object {
        const val EXPECTED_SAMPLE_RATE = 16_000

        /** One YAMNet window: 0.975 s at 16 kHz. */
        const val DEFAULT_WINDOW_SAMPLES = 15_600

        /** About half a second between windows. */
        const val DEFAULT_HOP_SAMPLES = 7_800
    }
}
