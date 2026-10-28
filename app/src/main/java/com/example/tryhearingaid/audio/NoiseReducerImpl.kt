package com.example.tryhearingaid.audio

import android.content.Context
import android.util.Log
import com.example.tryhearingaid.data.EnvironmentPreset
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Concrete implementation of [NoiseReducer].
 *
 * ## Model loading
 * Attempts to load `noise_suppression.tflite` from the app's `assets/` directory
 * with an NNAPI delegate for hardware acceleration.  On any [IOException] or
 * interpreter initialisation failure the class:
 *   1. Sets [isModelLoaded] = false.
 *   2. Activates the classical spectral-subtraction fallback.
 *   3. Emits a snackbar message so the ViewModel can surface it to the user.
 *
 * ## Environment presets
 * Each [EnvironmentPreset] maps to a [SuppressionConfig] that controls the
 * spectral-subtraction strength.  The same config is used both by the TFLite
 * post-processing and the standalone fallback path.
 *
 * Requirements: 2.1, 2.2, 2.3, 2.4, 2.5
 */
@Singleton
class NoiseReducerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : NoiseReducer {

    companion object {
        private const val TAG = "NoiseReducerImpl"
        private const val MODEL_FILENAME = "noise_suppression.tflite"
        private const val WARM_UP_FRAMES = 20
        private const val GATE_CLOSE_STEP = 0.04f
    }

    // ── State flows ───────────────────────────────────────────────────────────

    private val _isModelLoaded = MutableStateFlow(false)
    override val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    // ── Enabled flag ──────────────────────────────────────────────────────────

    @Volatile
    override var isEnabled: Boolean = true
        private set

    override fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    // ── Preset management ─────────────────────────────────────────────────────

    /** Suppression parameters per environment preset. */
    data class SuppressionConfig(
        /** Over-subtraction factor α (higher = more aggressive). */
        val alpha: Float,
        /** Spectral floor β (prevents musical-noise artefacts). */
        val beta: Float
    )

    private val presetConfigs: Map<EnvironmentPreset, SuppressionConfig> = mapOf(
        EnvironmentPreset.DEFAULT    to SuppressionConfig(alpha = 2.0f, beta = 0.01f),
        EnvironmentPreset.RESTAURANT to SuppressionConfig(alpha = 3.0f, beta = 0.02f),
        EnvironmentPreset.CLASSROOM  to SuppressionConfig(alpha = 2.5f, beta = 0.01f),
        EnvironmentPreset.OUTDOORS   to SuppressionConfig(alpha = 1.5f, beta = 0.005f),
        EnvironmentPreset.OFFICE     to SuppressionConfig(alpha = 2.0f, beta = 0.01f)
    )

    @Volatile
    private var activePreset: EnvironmentPreset = EnvironmentPreset.DEFAULT

    @Volatile
    private var activeConfig: SuppressionConfig = presetConfigs[EnvironmentPreset.DEFAULT]!!

    override fun setEnvironmentPreset(preset: EnvironmentPreset) {
        activePreset = preset
        activeConfig = presetConfigs[preset] ?: presetConfigs[EnvironmentPreset.DEFAULT]!!
        Log.d(TAG, "Environment preset set to $preset, config=$activeConfig")
    }

    override fun getActivePreset(): EnvironmentPreset = activePreset

    // ── TFLite interpreter ────────────────────────────────────────────────────

    private var interpreter: Interpreter? = null

    /** True once we have confirmed model loading failed and fallback is active. */
    @Volatile
    private var spectralSubtractionActive: Boolean = false

    // ── Spectral subtraction state ────────────────────────────────────────────
    // Simple single-channel power-spectrum noise estimate using a running average.

    /** Noise power estimate per frequency bin (updated each frame). */
    private var noisePowerEstimate: FloatArray? = null

    /** Number of frames processed — used to warm up the noise estimate. */
    @Volatile
    private var frameCount: Int = 0

    /** Noise estimate update rate (closer to 1 = slower adaptation). */
    private val noiseEstimateAlpha: Float = 0.98f

    // ── Initialisation ────────────────────────────────────────────────────────

    init {
        tryLoadModel()
    }

    private fun tryLoadModel() {
        try {
            val modelBuffer = loadModelFile()
            val nnApiDelegate = NnApiDelegate()
            val options = Interpreter.Options().addDelegate(nnApiDelegate)
            interpreter = Interpreter(modelBuffer, options)
            _isModelLoaded.value = true
            spectralSubtractionActive = false
            Log.i(TAG, "TFLite noise-suppression model loaded successfully.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load TFLite model ('$MODEL_FILENAME'): ${e.message}. " +
                    "Falling back to spectral subtraction.")
            _isModelLoaded.value = false
            spectralSubtractionActive = true
            _snackbarMessages.tryEmit(
                "Noise reduction model unavailable — using basic noise filter instead."
            )
        }
    }

    /**
     * Load the TFLite model file from assets as a memory-mapped buffer.
     * Throws [IOException] if the file cannot be opened.
     */
    private fun loadModelFile(): MappedByteBuffer {
        val assetFd = context.assets.openFd(MODEL_FILENAME)
        return assetFd.createInputStream().channel.use { fc ->
            fc.map(FileChannel.MapMode.READ_ONLY, assetFd.startOffset, assetFd.declaredLength)
        }
    }

    // ── AudioProcessingStage ──────────────────────────────────────────────────

    override fun process(buffer: ShortArray, sampleRate: Int, channelCount: Int): ShortArray {
        if (!isEnabled) return buffer
        return if (_isModelLoaded.value && interpreter != null) {
            processTFLite(buffer)
        } else {
            processSpectralSubtraction(buffer)
        }
    }

    // ── TFLite inference path ─────────────────────────────────────────────────

    private fun processTFLite(buffer: ShortArray): ShortArray {
        // Convert ShortArray → FloatArray normalised to [-1, 1]
        val inputFloat = FloatArray(buffer.size) { buffer[it] / 32768f }
        val outputFloat = Array(1) { FloatArray(buffer.size) }

        return try {
            // Input tensor shape [1, frameSize]; output tensor shape [1, frameSize]
            interpreter?.run(
                arrayOf(inputFloat),
                mapOf(0 to outputFloat)
            )
            // Apply preset alpha as a blend between suppressed and original
            val config = activeConfig
            val result = ShortArray(buffer.size) { i ->
                val suppressed = outputFloat[0][i]
                val blended = suppressed * config.alpha.coerceIn(0f, 1f) +
                        inputFloat[i] * (1f - config.alpha.coerceIn(0f, 1f))
                (blended * 32768f).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "TFLite inference error: ${e.message}. Falling back to spectral subtraction.")
            processSpectralSubtraction(buffer)
        }
    }

    // ── Spectral-subtraction fallback ─────────────────────────────────────────

    /**
     * Gentle noise gate used when the neural noise model is not available.
     *
     * It follows the quietest level it hears (the background noise) and, when the current frame
     * is not clearly louder than that, turns the volume down smoothly instead of removing sound
     * from each individual sample. The earlier sample-by-sample subtraction badly distorted
     * speech. Here the gain moves gradually, never drops below a fixed floor, and speech passes
     * through untouched.
     */
    internal fun processSpectralSubtraction(buffer: ShortArray): ShortArray {
        if (buffer.isEmpty()) return buffer

        val config = activeConfig
        val n = buffer.size

        var sumSq = 0.0
        for (s in buffer) sumSq += s.toDouble() * s
        val rms = sqrt(sumSq / n).toFloat()

        // Track the background level: drop quickly to quiet moments, rise very slowly.
        gateNoiseFloor = when {
            frameCount == 0 -> rms
            rms < gateNoiseFloor -> gateNoiseFloor * 0.8f + rms * 0.2f
            else -> gateNoiseFloor * 1.001f + 0.01f
        }
        frameCount++

        // Never attenuate by more than this (stronger presets allow more).
        val minGain = (0.5f / config.alpha).coerceIn(0.15f, 0.5f)
        val threshold = max(gateNoiseFloor * (1f + config.alpha), 50f)

        val target = when {
            frameCount <= WARM_UP_FRAMES -> 1f // still learning what "quiet" is
            rms >= threshold -> 1f
            else -> max(minGain, rms / threshold)
        }

        // Open at once for speech, close slowly (about a quarter second) so words are not clipped.
        val start = gateGain
        val end = if (target > start) target else max(target, start - GATE_CLOSE_STEP)
        gateGain = end

        return ShortArray(n) { i ->
            val g = start + (end - start) * (i + 1) / n
            (buffer[i] * g).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    private var gateNoiseFloor: Float = 0f
    private var gateGain: Float = 1f

    /** Whether the spectral-subtraction fallback is currently active. */
    fun isSpectralSubtractionActive(): Boolean = spectralSubtractionActive
}
