package com.example.tryhearingaid.audio

import com.example.tryhearingaid.data.Audiogram
import com.example.tryhearingaid.data.EqBand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 8-band IIR peaking EQ filter implementation.
 *
 * Each band is a second-order peaking biquad (Bell/peak EQ filter) centered at one
 * of the 8 audiogram frequencies: 250, 500, 1000, 2000, 3000, 4000, 6000, 8000 Hz.
 *
 * ### Gain clamping
 * [setBandGain] clamps the requested gain to [-20 dB, +40 dB].
 *
 * ### Audiogram mapping
 * [applyAudiogram] uses a linear mapping: 0 dBHL → 0 dB gain, 80 dBHL → 40 dB gain.
 * A higher threshold (more hearing loss) produces a proportionally higher gain so
 * the hearing aid boosts exactly the frequencies the user struggles with.
 *
 * ### Thread safety
 * Band state is held in a [MutableStateFlow] (atomic swap).  Filter coefficients are
 * recomputed atomically from that state before each [process] call, so changes take
 * effect within the very next audio frame — well inside the 100 ms requirement.
 */
class EqualizerImpl @Inject constructor() : Equalizer {

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        /** Center frequencies of the 8 EQ bands in Hz. */
        val CENTER_FREQUENCIES_HZ = intArrayOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)

        private const val BAND_COUNT = 8

        /** Minimum allowed band gain in dB. */
        const val GAIN_MIN_DB = -20f

        /** Maximum allowed band gain in dB. */
        const val GAIN_MAX_DB = 40f

        /** Bandwidth (Q factor) for each peaking filter — roughly 1 octave. */
        private const val Q = 1.414f   // sqrt(2), giving ~1-octave bandwidth

        /**
         * Linear mapping: 0 dBHL → 0 dB gain, 80 dBHL → 40 dB gain.
         * gain = threshold * (40f / 80f) = threshold * 0.5f
         */
        private const val AUDIOGRAM_GAIN_SLOPE = 40f / 80f
    }

    // ── Public state ──────────────────────────────────────────────────────────

    private val _bands = MutableStateFlow(
        CENTER_FREQUENCIES_HZ.map { hz -> EqBand(centerHz = hz, gainDb = 0f) }
    )
    override val bands: StateFlow<List<EqBand>> = _bands.asStateFlow()

    @Volatile
    override var isEnabled: Boolean = true
        private set

    // ── IIR filter state ──────────────────────────────────────────────────────

    /**
     * Biquad filter coefficients for each of the 8 bands.
     * Layout per band: [b0, b1, b2, a1, a2] (normalised: a0 = 1).
     */
    private val coeffs: Array<FloatArray> = Array(BAND_COUNT) { FloatArray(5) }

    /**
     * Per-band, per-channel delay-line state [x1, x2, y1, y2] for the transposed
     * direct form II biquad.  Supports up to 2 channels; mono uses index 0 only.
     */
    private val state: Array<Array<FloatArray>> =
        Array(BAND_COUNT) { Array(2) { FloatArray(4) } }   // [band][channel][x1,x2,y1,y2]

    /** Tracks whether coefficients are stale and need to be recomputed. */
    private val coeffsDirty = AtomicBoolean(true)

    /** Sample rate from the last [process] call (used to recompute coefficients on-the-fly). */
    @Volatile
    private var lastSampleRate: Int = 16_000

    // ── Equalizer interface ───────────────────────────────────────────────────

    /**
     * Set the gain for band [bandIndex] (0-based).
     *
     * [gainDb] is clamped to [[GAIN_MIN_DB], [GAIN_MAX_DB]].  The change is visible
     * in [bands] immediately (StateFlow atomic update) and will be reflected in
     * processed audio within 100 ms (next audio frame).
     */
    override fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex < 0 || bandIndex >= BAND_COUNT) return
        val clamped = gainDb.coerceIn(GAIN_MIN_DB, GAIN_MAX_DB)
        val current = _bands.value.toMutableList()
        current[bandIndex] = current[bandIndex].copy(gainDb = clamped)
        _bands.value = current
        coeffsDirty.set(true)
    }

    /**
     * Map an [audiogram] to EQ band gains and apply them.
     *
     * For each band frequency, the average of the left-ear and right-ear thresholds is
     * used (if both ears have data for that frequency).  Missing entries fall back to
     * whichever ear has data, or to 0 dBHL if neither has data for that frequency.
     *
     * Mapping: gain = threshold (dBHL) × (40 / 80) — linear, 0 dBHL → 0 dB, 80 dBHL → 40 dB.
     * The result is clamped to [[GAIN_MIN_DB], [GAIN_MAX_DB]].
     */
    override fun applyAudiogram(audiogram: Audiogram) {
        val newBands = CENTER_FREQUENCIES_HZ.mapIndexed { i, hz ->
            val leftThreshold = audiogram.leftEar[hz]?.toFloat()
            val rightThreshold = audiogram.rightEar[hz]?.toFloat()
            val thresholdDbHl = when {
                leftThreshold != null && rightThreshold != null -> (leftThreshold + rightThreshold) / 2f
                leftThreshold != null -> leftThreshold
                rightThreshold != null -> rightThreshold
                else -> 0f
            }
            val gainDb = (thresholdDbHl * AUDIOGRAM_GAIN_SLOPE).coerceIn(GAIN_MIN_DB, GAIN_MAX_DB)
            EqBand(centerHz = hz, gainDb = gainDb)
        }
        _bands.value = newBands
        coeffsDirty.set(true)
    }

    /**
     * Reset all band gains to 0 dB (flat response) and clear filter delay-line state.
     */
    override fun reset() {
        _bands.value = CENTER_FREQUENCIES_HZ.map { hz -> EqBand(centerHz = hz, gainDb = 0f) }
        clearDelayLines()
        coeffsDirty.set(true)
    }

    override fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    // ── AudioProcessingStage interface ────────────────────────────────────────

    /**
     * Apply the 8-band IIR peaking EQ to [buffer] in-place and return it.
     *
     * If the stage is disabled the buffer is returned unmodified.
     *
     * Coefficients are recomputed lazily whenever [coeffsDirty] is set — meaning
     * any [setBandGain] or [applyAudiogram] call is reflected within one frame.
     */
    override fun process(buffer: ShortArray, sampleRate: Int, channelCount: Int): ShortArray {
        if (!isEnabled) return buffer

        // Recompute coefficients if stale or if the sample rate changed.
        if (coeffsDirty.getAndSet(false) || sampleRate != lastSampleRate) {
            lastSampleRate = sampleRate
            recomputeCoefficients(sampleRate)
        }

        val channels = channelCount.coerceIn(1, 2)
        val frames = buffer.size / channels

        // Convert to float, process, convert back to short
        val floatBuf = FloatArray(buffer.size) { buffer[it].toFloat() }

        // Apply each band's biquad filter in series
        for (band in 0 until BAND_COUNT) {
            val c = coeffs[band]
            val b0 = c[0]; val b1 = c[1]; val b2 = c[2]
            val a1 = c[3]; val a2 = c[4]

            // Skip flat bands (gain ≈ 0 dB) to save CPU; coefficients would be unity anyway
            val bandGain = _bands.value.getOrNull(band)?.gainDb ?: 0f
            if (bandGain == 0f) continue

            for (ch in 0 until channels) {
                val s = state[band][ch]
                // s = [w1, w2] — transposed direct form II
                for (frame in 0 until frames) {
                    val idx = frame * channels + ch
                    val x = floatBuf[idx]

                    // Transposed Direct Form II biquad:
                    //   y  = b0*x + w1
                    //   w1 = b1*x - a1*y + w2
                    //   w2 = b2*x - a2*y
                    val y = b0 * x + s[0]
                    s[0] = b1 * x - a1 * y + s[1]
                    s[1] = b2 * x - a2 * y

                    floatBuf[idx] = y
                }
            }
        }

        // Convert back to 16-bit PCM with hard clipping
        for (i in buffer.indices) {
            buffer[i] = floatBuf[i]
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return buffer
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Compute second-order peaking (Bell) biquad coefficients for each band.
     *
     * The peaking EQ biquad formula (from Audio EQ Cookbook, R. Bristow-Johnson):
     *
     *   A  = 10^(dBgain/40)
     *   w0 = 2π * f0 / Fs
     *   alpha = sin(w0) / (2*Q)
     *
     *   b0 =  1 + alpha * A
     *   b1 = -2 * cos(w0)
     *   b2 =  1 - alpha * A
     *   a0 =  1 + alpha / A
     *   a1 = -2 * cos(w0)   (same as b1)
     *   a2 =  1 - alpha / A
     *
     * Normalised by dividing all by a0.
     */
    private fun recomputeCoefficients(sampleRate: Int) {
        val currentBands = _bands.value
        for (i in 0 until BAND_COUNT) {
            val band = currentBands.getOrNull(i) ?: continue
            val gainDb = band.gainDb
            val f0 = CENTER_FREQUENCIES_HZ[i].toFloat()
            val fs = sampleRate.toFloat()

            // Nyquist guard: if center frequency >= Nyquist, set to unity
            if (f0 >= fs / 2f) {
                coeffs[i] = floatArrayOf(1f, 0f, 0f, 0f, 0f)
                continue
            }

            if (gainDb == 0f) {
                // Unity passthrough — no filtering needed
                coeffs[i] = floatArrayOf(1f, 0f, 0f, 0f, 0f)
                continue
            }

            val a = 10f.pow(gainDb / 40f)    // sqrt(linear gain)
            val w0 = (2.0 * PI * f0 / fs).toFloat()
            val cosW0 = cos(w0.toDouble()).toFloat()
            val sinW0 = sin(w0.toDouble()).toFloat()
            val alpha = sinW0 / (2f * Q)

            val b0 = 1f + alpha * a
            val b1 = -2f * cosW0
            val b2 = 1f - alpha * a
            val a0 = 1f + alpha / a
            val a1 = -2f * cosW0
            val a2 = 1f - alpha / a

            // Normalise
            coeffs[i][0] = b0 / a0
            coeffs[i][1] = b1 / a0
            coeffs[i][2] = b2 / a0
            coeffs[i][3] = a1 / a0
            coeffs[i][4] = a2 / a0
        }
    }

    /** Zero out all biquad delay-line state (avoids clicks after reset). */
    private fun clearDelayLines() {
        for (band in 0 until BAND_COUNT) {
            for (ch in 0..1) {
                state[band][ch][0] = 0f
                state[band][ch][1] = 0f
            }
        }
    }
}
