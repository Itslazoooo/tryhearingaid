package com.example.tryhearingaid.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Concrete implementation of [DirectionalFilter].
 *
 * ## Microphone detection
 * At construction time the class queries [AudioManager.getMicrophones] (API 28+).
 * If fewer than 2 microphones are available [isAvailable] is set to `false` and
 * [process] acts as a simple pass-through.
 *
 * ## Beamforming (dual-mic devices only)
 * The stage assumes stereo (2-channel) input:
 *   - Channel 0 = microphone 1 (left/front)
 *   - Channel 1 = microphone 2 (right/rear)
 *
 * Delay-and-sum beamforming delays one channel by D samples (computed from the
 * selected [AudioDirection], the assumed mic spacing of 15 cm, and the speed of
 * sound 343 m/s), then averages the two channels into a mono-like output.
 *
 * Direction → delay mapping (mic spacing = 0.15 m, default sample rate = 16 kHz):
 *   - Front  (0°)  → delay = 0
 *   - Left  (−90°) → delay = +MAX_DELAY_SAMPLES (left mic leads)
 *   - Right (+90°) → delay = −MAX_DELAY_SAMPLES (right mic leads)
 *   - Rear  (180°) → delay = 0 (symmetric)
 *   - Custom(az)   → delay = round(MIC_SPACING_M * sin(az_rad) * sampleRate / SPEED_OF_SOUND_MPS)
 *
 * max_delay = round(0.15 * 16000 / 343) ≈ 7 samples
 *
 * ## Crossfade on direction change
 * When [setDirection] is called the stage blends the old and new beam patterns over
 * 500 ms worth of frames using a linear crossfade, ensuring smooth transitions
 * without clicks or artefacts.
 *
 * Requirements: 6.2 — DirectionalFilter implementation
 */
@Singleton
class DirectionalFilterImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : DirectionalFilter {

    companion object {
        private const val TAG = "DirectionalFilterImpl"

        /** Assumed distance between microphones in metres. */
        private const val MIC_SPACING_M = 0.15f

        /** Speed of sound in metres per second. */
        private const val SPEED_OF_SOUND_MPS = 343f

        /** Default sample rate used for computing the static max-delay constant. */
        private const val DEFAULT_SAMPLE_RATE_HZ = 16_000

        /**
         * Maximum beamforming delay in samples at the default sample rate.
         * max_delay = round(0.15 * 16000 / 343) ≈ 7 samples.
         */
        val MAX_DELAY_SAMPLES: Int =
            (MIC_SPACING_M * DEFAULT_SAMPLE_RATE_HZ / SPEED_OF_SOUND_MPS).roundToInt()

        /** Duration of the crossfade window in milliseconds. */
        private const val CROSSFADE_DURATION_MS = 500L
    }

    // ── Availability ──────────────────────────────────────────────────────────

    private val _isAvailable = MutableStateFlow(false)
    override val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    // ── Enabled flag ──────────────────────────────────────────────────────────

    @Volatile
    override var isEnabled: Boolean = true
        private set

    override fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    // ── Direction / delay state ───────────────────────────────────────────────

    /** Currently active direction (used for new beam computation). */
    @Volatile
    private var currentDirection: AudioDirection = AudioDirection.Front

    /**
     * Delay in samples for the *current* (target) beam pattern.
     * A positive value means channel 1 is delayed (channel 0 leads).
     * A negative value means channel 0 is delayed (channel 1 leads).
     */
    @Volatile
    private var targetDelaySamples: Int = 0

    /**
     * Delay in samples for the *previous* beam pattern — held constant during
     * the crossfade window.
     */
    @Volatile
    private var oldDelaySamples: Int = 0

    // ── Crossfade state ───────────────────────────────────────────────────────

    /**
     * How many more crossfade frames remain before the transition is complete.
     * 0 means the transition is complete and only the target beam is applied.
     */
    @Volatile
    private var crossfadeFramesRemaining: Int = 0

    /** Total number of frames in the crossfade window (computed per [process] call using sampleRate). */
    @Volatile
    private var crossfadeTotalFrames: Int = 0

    // ── Delay-line ring buffers ───────────────────────────────────────────────

    /**
     * Ring buffer for channel 0 delay line.
     * Size is [MAX_DELAY_SAMPLES] + 1 to accommodate the maximum delay.
     */
    private val delayBuf0: ShortArray = ShortArray(MAX_DELAY_SAMPLES + 2)
    private val delayBuf1: ShortArray = ShortArray(MAX_DELAY_SAMPLES + 2)

    /** Write position in the ring buffers. */
    @Volatile
    private var delayWritePos: Int = 0

    // ── Initialisation ────────────────────────────────────────────────────────

    init {
        detectMicrophoneCount()
    }

    /**
     * Query [AudioManager] for the number of available microphones.
     * Requires API 28+.  On older devices the stage is always unavailable.
     */
    private fun detectMicrophoneCount() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.d(TAG, "API < 28: microphone enumeration not supported; isAvailable = false")
            _isAvailable.value = false
            return
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            Log.w(TAG, "AudioManager unavailable; isAvailable = false")
            _isAvailable.value = false
            return
        }

        try {
            val micCount = audioManager.microphones?.size ?: 0
            val available = micCount >= 2
            _isAvailable.value = available
            Log.i(TAG, "Detected $micCount microphone(s); isAvailable = $available")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query microphones: ${e.message}; isAvailable = false")
            _isAvailable.value = false
        }
    }

    // ── DirectionalFilter interface ───────────────────────────────────────────

    /**
     * Update the target beam direction.
     *
     * The transition from the old beam to the new one is smoothed over 500 ms
     * using a per-frame linear crossfade applied in the next [process] call(s).
     */
    override fun setDirection(direction: AudioDirection) {
        if (!_isAvailable.value) return   // no-op on single-mic devices

        val newDelay = computeDelaySamples(direction, DEFAULT_SAMPLE_RATE_HZ)

        synchronized(this) {
            oldDelaySamples = targetDelaySamples
            targetDelaySamples = newDelay
            currentDirection = direction

            // Crossfade will be set up on the next process() call once we know sampleRate.
            // We flag it by setting crossfadeFramesRemaining to -1 (pending setup).
            crossfadeFramesRemaining = -1
        }

        Log.d(TAG, "Direction set to $direction; delaySamples=$newDelay")
    }

    // ── AudioProcessingStage interface ────────────────────────────────────────

    /**
     * Apply directional beamforming to [buffer] in-place and return it.
     *
     * If [isAvailable] is false (single-mic) or the stage is disabled the buffer
     * is returned unmodified (pass-through).
     *
     * The buffer is expected to be interleaved stereo PCM: even indices = channel 0,
     * odd indices = channel 1.  Mono buffers are passed through unchanged even on
     * dual-mic devices (beamforming requires two channels).
     */
    override fun process(buffer: ShortArray, sampleRate: Int, channelCount: Int): ShortArray {
        if (!isEnabled) return buffer
        if (!_isAvailable.value) return buffer
        if (channelCount < 2 || buffer.size < 2) return buffer

        val channels = 2   // beamforming is always stereo
        val frames = buffer.size / channels

        // ── Initialise crossfade if pending ───────────────────────────────────
        synchronized(this) {
            if (crossfadeFramesRemaining == -1) {
                // Compute total crossfade frames based on actual sample rate
                crossfadeTotalFrames = ((CROSSFADE_DURATION_MS * sampleRate) / 1000L).toInt()
                    .coerceAtLeast(1)
                crossfadeFramesRemaining = crossfadeTotalFrames
            }
        }

        val target = targetDelaySamples
        val old = oldDelaySamples
        val cfRemaining = crossfadeFramesRemaining
        val cfTotal = crossfadeTotalFrames

        // ── Delay-and-sum beamforming per frame ───────────────────────────────
        val output = ShortArray(buffer.size)

        for (frame in 0 until frames) {
            val idx0 = frame * channels       // channel 0 sample index
            val idx1 = frame * channels + 1   // channel 1 sample index

            val ch0 = buffer[idx0]
            val ch1 = buffer[idx1]

            // Write current samples into delay ring buffers
            val wp = delayWritePos % delayBuf0.size
            delayBuf0[wp] = ch0
            delayBuf1[wp] = ch1

            // Compute blended output for the crossfade
            val targetSample = applyDelayAndSum(ch0, ch1, target, wp)

            if (cfRemaining > 0) {
                // We are mid-crossfade — blend old and new beam patterns
                val oldSample = applyDelayAndSum(ch0, ch1, old, wp)
                val alpha = 1f - cfRemaining.toFloat() / cfTotal.toFloat()
                val blended = (oldSample * (1f - alpha) + targetSample * alpha)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()

                // Write blended mono output into both channels
                output[idx0] = blended
                output[idx1] = blended

                crossfadeFramesRemaining = (cfRemaining - 1).coerceAtLeast(0)
            } else {
                // Crossfade complete — output target beam only
                output[idx0] = targetSample
                output[idx1] = targetSample
            }

            delayWritePos++
        }

        return output
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Apply delay-and-sum beamforming for a single stereo sample pair.
     *
     * If [delaySamples] > 0 channel 1 is delayed (channel 0 leads — maps to Front/Left).
     * If [delaySamples] < 0 channel 0 is delayed (channel 1 leads — maps to Right).
     *
     * The delayed sample is fetched from the ring buffer at [currentWritePos].
     * Returns the averaged output as a [Short].
     */
    private fun applyDelayAndSum(
        ch0: Short,
        ch1: Short,
        delaySamples: Int,
        currentWritePos: Int
    ): Short {
        val absDel = abs(delaySamples).coerceIn(0, MAX_DELAY_SAMPLES)

        val delayed0: Float
        val delayed1: Float

        if (delaySamples == 0) {
            // No delay — sum channels directly
            delayed0 = ch0.toFloat()
            delayed1 = ch1.toFloat()
        } else if (delaySamples > 0) {
            // Channel 1 is delayed by absDel samples — fetch from ring buffer
            val readPos = (currentWritePos - absDel + delayBuf1.size * 2) % delayBuf1.size
            delayed0 = ch0.toFloat()
            delayed1 = delayBuf1[readPos].toFloat()
        } else {
            // Channel 0 is delayed by absDel samples — fetch from ring buffer
            val readPos = (currentWritePos - absDel + delayBuf0.size * 2) % delayBuf0.size
            delayed0 = delayBuf0[readPos].toFloat()
            delayed1 = ch1.toFloat()
        }

        // Sum and halve (delay-and-sum beamforming)
        return ((delayed0 + delayed1) * 0.5f)
            .toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

    /**
     * Compute the beamforming delay in samples for the given [direction] and [sampleRate].
     *
     * Formula: delay = round(MIC_SPACING_M * sin(azimuth_rad) * sampleRate / SPEED_OF_SOUND_MPS)
     *
     * Direction-to-azimuth mapping:
     *   Front  →   0° → sin = 0   → delay = 0
     *   Left   → −90° → sin = −1  → delay = −MAX (but we invert sign so LEFT means +MAX)
     *   Right  → +90° → sin = +1  → delay = +MAX (inverted to −MAX so right mic leads)
     *   Rear   → 180° → sin = 0   → delay = 0
     *   Custom →  az° → delay = round(0.15 * sin(az_rad) * sr / 343)
     *
     * Sign convention: positive delay = channel 1 is delayed (channel 0 / left mic leads).
     */
    internal fun computeDelaySamples(direction: AudioDirection, sampleRate: Int): Int {
        val maxDelay = (MIC_SPACING_M * sampleRate / SPEED_OF_SOUND_MPS).roundToInt()
        return when (direction) {
            is AudioDirection.Front -> 0
            is AudioDirection.Rear  -> 0
            is AudioDirection.Left  -> +maxDelay   // left mic leads → delay right channel
            is AudioDirection.Right -> -maxDelay   // right mic leads → delay left channel
            is AudioDirection.Custom -> {
                val az = direction.azimuthDegrees
                val azRad = Math.toRadians(az.toDouble())
                (MIC_SPACING_M * sin(azRad).toFloat() * sampleRate / SPEED_OF_SOUND_MPS)
                    .roundToInt()
                    .coerceIn(-maxDelay, maxDelay)
            }
        }
    }
}
