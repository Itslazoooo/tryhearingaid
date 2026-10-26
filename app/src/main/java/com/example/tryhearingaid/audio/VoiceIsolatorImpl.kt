package com.example.tryhearingaid.audio

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Concrete implementation of [VoiceIsolator].
 *
 * ## Model loading
 * Attempts to load `voice_isolation.tflite` from `assets/`.  On failure the
 * stage degrades gracefully — audio passes through unmodified and
 * [dominantSpeakerConfidence] stays at 0.
 *
 * ## Re-evaluation cadence (Requirement 3.2)
 * A background coroutine fires every 3 seconds to update the dominant-speaker
 * confidence score from accumulated frame energy.
 *
 * ## Pass-through when silent (Requirement 3.4)
 * If no speech-like energy is detected for more than 5 consecutive seconds
 * [passThrough] is set to true and subsequent frames bypass isolation entirely
 * until speech is detected again.
 *
 * Requirements: 3.1, 3.2, 3.3, 3.4
 */
@Singleton
class VoiceIsolatorImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : VoiceIsolator {

    companion object {
        private const val TAG = "VoiceIsolatorImpl"
        private const val MODEL_FILENAME = "voice_isolation.tflite"

        /** Re-evaluate dominant speaker every 3 000 ms (Requirement 3.2). */
        private const val RE_EVAL_INTERVAL_MS = 3_000L

        /**
         * If no speech is detected for this many milliseconds, switch to
         * pass-through mode (Requirement 3.4).
         */
        private const val SILENCE_PASS_THROUGH_MS = 5_000L

        /**
         * RMS threshold below which a frame is considered non-speech.
         * 16-bit PCM: ~500 corresponds to roughly -36 dBFS.
         */
        private const val SPEECH_RMS_THRESHOLD = 500f
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private val _dominantSpeakerConfidence = MutableStateFlow(0f)
    override val dominantSpeakerConfidence: StateFlow<Float> =
        _dominantSpeakerConfidence.asStateFlow()

    @Volatile
    override var isEnabled: Boolean = true
        private set

    override fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            _dominantSpeakerConfidence.value = 0f
        }
    }

    // ── TFLite ────────────────────────────────────────────────────────────────

    private var interpreter: Interpreter? = null
    private var modelLoaded: Boolean = false

    // ── Pass-through / silence tracking ──────────────────────────────────────

    /**
     * When true, audio is passed through unmodified (no speech for >5 s).
     * Reset to false as soon as speech energy is detected again.
     */
    @Volatile
    private var passThrough: Boolean = false

    /** System-clock timestamp (ms) of the last frame that exceeded the speech threshold. */
    @Volatile
    private var lastSpeechDetectedMs: Long = System.currentTimeMillis()

    // ── Accumulated energy for the 3-second re-evaluation window ─────────────

    private var accumulatedRms: Double = 0.0
    private var accumulatedFrames: Int = 0

    // ── Coroutine scope for the background re-evaluation task ─────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Initialisation ────────────────────────────────────────────────────────

    init {
        tryLoadModel()
        startReEvaluationLoop()
    }

    private fun tryLoadModel() {
        try {
            val modelBuffer = loadModelFile()
            val nnApiDelegate = NnApiDelegate()
            val options = Interpreter.Options().addDelegate(nnApiDelegate)
            interpreter = Interpreter(modelBuffer, options)
            modelLoaded = true
            Log.i(TAG, "TFLite voice-isolation model loaded successfully.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load TFLite model ('$MODEL_FILENAME'): ${e.message}. " +
                    "Voice isolation will pass audio through unchanged.")
            modelLoaded = false
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

    /**
     * Background loop that re-evaluates the dominant-speaker confidence every
     * [RE_EVAL_INTERVAL_MS] milliseconds (Requirement 3.2).
     */
    private fun startReEvaluationLoop() {
        scope.launch {
            while (true) {
                delay(RE_EVAL_INTERVAL_MS)
                reEvaluateDominantSpeaker()
            }
        }
    }

    /**
     * Derive a confidence score from the accumulated RMS energy gathered over the
     * last 3-second window, then reset the accumulator.
     */
    private fun reEvaluateDominantSpeaker() {
        val frames = accumulatedFrames
        val avgRms = if (frames > 0) (accumulatedRms / frames).toFloat() else 0f
        // Reset accumulators for the next window
        accumulatedRms = 0.0
        accumulatedFrames = 0

        // Simple confidence heuristic: normalise RMS against a full-scale reference.
        // A more sophisticated model would use the TFLite output directly.
        val confidence = (avgRms / 32768f).coerceIn(0f, 1f)
        _dominantSpeakerConfidence.value = confidence

        Log.v(TAG, "Re-eval: avgRms=$avgRms confidence=$confidence passThrough=$passThrough")
    }

    // ── AudioProcessingStage ──────────────────────────────────────────────────

    override fun process(buffer: ShortArray, sampleRate: Int, channelCount: Int): ShortArray {
        if (!isEnabled) return buffer

        val rms = computeRms(buffer)
        val now = System.currentTimeMillis()

        // Detect speech energy and update silence timer
        if (rms > SPEECH_RMS_THRESHOLD) {
            lastSpeechDetectedMs = now
            if (passThrough) {
                // Speech detected again — exit pass-through mode
                passThrough = false
                Log.d(TAG, "Speech detected; exiting pass-through mode.")
            }
        } else if (!passThrough && (now - lastSpeechDetectedMs) > SILENCE_PASS_THROUGH_MS) {
            // No speech for >5 s — switch to pass-through (Requirement 3.4)
            passThrough = true
            _dominantSpeakerConfidence.value = 0f
            Log.d(TAG, "No speech for >5 s; entering pass-through mode.")
        }

        // Accumulate energy for the 3-second re-evaluation window
        accumulatedRms += rms
        accumulatedFrames++

        // Pass-through: return buffer unmodified
        if (passThrough) return buffer

        // When model is loaded use TFLite inference; otherwise pass through
        return if (modelLoaded && interpreter != null) {
            processTFLite(buffer)
        } else {
            buffer
        }
    }

    // ── TFLite inference ──────────────────────────────────────────────────────

    private fun processTFLite(buffer: ShortArray): ShortArray {
        // Normalise to [-1, 1]
        val inputFloat = FloatArray(buffer.size) { buffer[it] / 32768f }
        val outputFloat = Array(1) { FloatArray(buffer.size) }

        return try {
            interpreter?.run(
                arrayOf(inputFloat),
                mapOf(0 to outputFloat)
            )
            ShortArray(buffer.size) { i ->
                (outputFloat[0][i] * 32768f).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        } catch (e: Exception) {
            Log.e(TAG, "TFLite inference error: ${e.message}. Passing audio through.")
            buffer
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun computeRms(buffer: ShortArray): Float {
        if (buffer.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in buffer) sumSq += s.toDouble() * s.toDouble()
        return sqrt(sumSq / buffer.size).toFloat()
    }
}
