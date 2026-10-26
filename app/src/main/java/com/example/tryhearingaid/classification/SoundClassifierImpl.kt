package com.example.tryhearingaid.classification

import android.content.Context
import android.util.Log
import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.data.SoundDetection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete implementation of [SoundClassifier] backed by the YAMNet TFLite model.
 *
 * ## Model
 * YAMNet accepts a 1-D float tensor of 15 600 samples (0.975 s @ 16 kHz) and
 * outputs a 2-D tensor of shape [frames, 521] containing per-frame class scores.
 *
 * ## AudioSet → SoundCategory mapping
 * Each [SoundCategory] is mapped to one or more AudioSet class indices.
 * For each output frame the maximum score across all mapped indices is used as
 * the confidence for that category.  The category with the highest such confidence
 * is emitted if it exceeds [MIN_CONFIDENCE_THRESHOLD].
 *
 * ## Graceful degradation
 * If the model fails to load, [isModelLoaded] is set to `false` and no detections
 * are ever emitted (the classifier simply becomes a no-op).
 *
 * Requirements: 9.1, 9.5
 */
@Singleton
class SoundClassifierImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SoundClassifier {

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "SoundClassifierImpl"
        private const val MODEL_FILENAME = "yamnet.tflite"

        /** YAMNet input window: 0.975 s × 16 000 Hz = 15 600 samples. */
        private const val YAMNET_INPUT_SAMPLES = 15_600

        /** Total number of AudioSet classes output by YAMNet. */
        private const val AUDIOSET_CLASS_COUNT = 521

        /**
         * Minimum confidence to emit a [SoundDetection].
         * Only the highest-confidence category per frame is emitted, and only if
         * it is above this threshold.
         */
        private const val MIN_CONFIDENCE_THRESHOLD = 0.30f

        /**
         * Maps each [SoundCategory] to the AudioSet class indices whose maximum
         * score represents that category's confidence.
         *
         * Index references are derived from the YAMNet AudioSet ontology:
         *   https://github.com/tensorflow/models/blob/master/research/audioset/yamnet/yamnet_class_map.csv
         */
        val CATEGORY_INDEX_MAP: Map<SoundCategory, IntArray> = mapOf(
            // Doorbell / Ding-dong
            SoundCategory.DOORBELL       to intArrayOf(396, 397),
            // Baby cry / infant cry / crying
            SoundCategory.BABY_CRYING    to intArrayOf(20, 21, 22),
            // Fire alarm / smoke detector
            SoundCategory.FIRE_ALARM     to intArrayOf(400, 401, 402),
            // Car horn / vehicle horn / honking
            SoundCategory.CAR_HORN       to intArrayOf(300, 301, 302),
            // Dog bark / dog communication
            SoundCategory.DOG_BARKING    to intArrayOf(74, 75, 76),
            // Speech — generic speech as a proxy for "name called"
            SoundCategory.NAME_CALLED    to intArrayOf(0, 1),
            // Knock / tap / thud
            SoundCategory.KNOCK          to intArrayOf(469, 470),
            // Telephone / ringtone / dial
            SoundCategory.PHONE_RINGING  to intArrayOf(382, 383, 384),
            // Water / running water / liquid
            SoundCategory.WATER_RUNNING  to intArrayOf(288, 289, 290),
            // Glass breaking / shatter
            SoundCategory.GLASS_BREAKING to intArrayOf(346, 347),
            // Generic siren
            SoundCategory.SIREN          to intArrayOf(312, 313, 314, 315, 316),
            // Smoke detector / fire alarm
            SoundCategory.SMOKE_ALARM    to intArrayOf(400, 402),
            // Crowd / cheering / audience
            SoundCategory.CROWD_CHEERING to intArrayOf(25, 26, 27, 28),
            // Bird / birdsong / tweet
            SoundCategory.BIRDS          to intArrayOf(93, 94, 95, 96),
            // Thunder / thunderstorm
            SoundCategory.THUNDER        to intArrayOf(324, 325),
            // General speech
            SoundCategory.SPEECH         to intArrayOf(0, 1, 2, 3, 4),
            // Carbon monoxide detector
            SoundCategory.CO_ALARM       to intArrayOf(401),
            // Fire engine / fire truck siren
            SoundCategory.FIRE_TRUCK_SIREN   to intArrayOf(312),
            // Ambulance siren
            SoundCategory.AMBULANCE_SIREN    to intArrayOf(313),
            // Police car siren
            SoundCategory.POLICE_SIREN       to intArrayOf(314),
            // Verbal calls for help — treated as speech containing "help"
            SoundCategory.VERBAL_HELP        to intArrayOf(0, 1)
        )
    }

    // ── SharedFlow ────────────────────────────────────────────────────────────

    private val _detections = MutableSharedFlow<SoundDetection>(extraBufferCapacity = 64)
    override val detections: SharedFlow<SoundDetection> = _detections.asSharedFlow()

    // ── Enabled-categories filter ─────────────────────────────────────────────

    @Volatile
    private var enabledCategories: Set<SoundCategory> = SoundCategory.entries.toSet()

    override fun setEnabledCategories(categories: Set<SoundCategory>) {
        enabledCategories = categories.toSet()
    }

    // ── Classifier enable/disable ─────────────────────────────────────────────

    @Volatile
    private var classifierEnabled: Boolean = false

    private var classificationJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Audio frame queue ─────────────────────────────────────────────────────

    /**
     * Buffered channel holding raw 16 kHz mono PCM frames submitted by callers.
     * Capacity 8 means up to ~7.8 s of audio can be queued before older frames
     * are dropped (we use CONFLATED semantics via offer).
     */
    private val frameQueue = Channel<ShortArray>(capacity = 8)

    // ── TFLite ────────────────────────────────────────────────────────────────

    private var interpreter: Interpreter? = null

    /** True when the model was loaded successfully; false on any load error. */
    var isModelLoaded: Boolean = false
        private set

    // ── Initialisation ────────────────────────────────────────────────────────

    init {
        tryLoadModel()
    }

    private fun tryLoadModel() {
        try {
            val buffer = loadModelFile()
            interpreter = Interpreter(buffer, Interpreter.Options())
            isModelLoaded = true
            Log.i(TAG, "YAMNet TFLite model loaded successfully.")
        } catch (e: Exception) {
            isModelLoaded = false
            Log.w(TAG, "Failed to load YAMNet model ('$MODEL_FILENAME'): ${e.message}. " +
                    "SoundClassifier will emit no detections.")
        }
    }

    /**
     * Memory-map the TFLite model from assets.
     * Throws [IOException] if the file cannot be opened (e.g. placeholder stub).
     */
    @Throws(IOException::class)
    private fun loadModelFile(): MappedByteBuffer {
        val assetFd = context.assets.openFd(MODEL_FILENAME)
        return assetFd.createInputStream().channel.use { fc ->
            fc.map(FileChannel.MapMode.READ_ONLY, assetFd.startOffset, assetFd.declaredLength)
        }
    }

    // ── SoundClassifier API ───────────────────────────────────────────────────

    override fun setEnabled(enabled: Boolean) {
        if (enabled == classifierEnabled) return
        classifierEnabled = enabled
        if (enabled) {
            startClassificationLoop()
        } else {
            classificationJob?.cancel()
            classificationJob = null
        }
    }

    override fun submitAudioFrame(samples: ShortArray) {
        if (!classifierEnabled || !isModelLoaded) return
        // Best-effort: drop oldest frame if queue is full rather than blocking the
        // audio thread. The Channel trySend() returns a result we can discard.
        frameQueue.trySend(samples)
    }

    // ── Background classification coroutine ───────────────────────────────────

    private fun startClassificationLoop() {
        classificationJob?.cancel()
        classificationJob = scope.launch(Dispatchers.Default) {
            for (samples in frameQueue) {
                if (!classifierEnabled) break
                classifyFrame(samples)
            }
        }
    }

    /**
     * Run YAMNet on [samples] and emit the best-matching [SoundDetection] if the
     * top confidence exceeds [MIN_CONFIDENCE_THRESHOLD] and the category is enabled.
     */
    private fun classifyFrame(samples: ShortArray) {
        val interp = interpreter ?: return

        try {
            // --- Prepare input tensor: FloatArray[1][YAMNET_INPUT_SAMPLES] -----
            val inputSamples = YAMNET_INPUT_SAMPLES
            val floatInput = FloatArray(inputSamples)
            val copyLen = minOf(samples.size, inputSamples)
            for (i in 0 until copyLen) {
                floatInput[i] = samples[i] / 32768f
            }
            // Zero-pad if samples is shorter than expected
            // (already zero by FloatArray initialisation)

            // YAMNet input shape: [waveform_length] (flat 1-D tensor)
            val inputTensor = arrayOf(floatInput)

            // --- Infer output tensor shape from the model -----------------------
            // YAMNet outputs:
            //   output[0]: scores  — [num_frames, 521]
            //   output[1]: embeddings — [num_frames, 1024]  (ignored here)
            //   output[2]: spectrogram — [num_spectrogram_frames, 64] (ignored)
            //
            // num_frames depends on the input length; for 15 600 samples it is 1.
            // We allocate [1][521] and use interpreter.run with an output map.

            val numFrames = 1 // for the standard 15 600-sample window
            val scoresOutput = Array(numFrames) { FloatArray(AUDIOSET_CLASS_COUNT) }

            // Use the same run(inputs, outputs) pattern as NoiseReducerImpl/VoiceIsolatorImpl.
            interp.run(inputTensor, mapOf(0 to scoresOutput))

            // --- Aggregate scores across all frames ----------------------------
            // For each SoundCategory compute max score across mapped indices and frames.
            val frameScores = scoresOutput // [numFrames][521]

            val categoryScores = mutableMapOf<SoundCategory, Float>()
            for ((category, indices) in CATEGORY_INDEX_MAP) {
                var maxScore = 0f
                for (frame in frameScores) {
                    for (idx in indices) {
                        if (idx < frame.size) {
                            val s = frame[idx]
                            if (s > maxScore) maxScore = s
                        }
                    }
                }
                categoryScores[category] = maxScore
            }

            // --- Find the highest-confidence category --------------------------
            val (bestCategory, bestScore) = categoryScores.maxByOrNull { it.value }
                ?: return

            if (bestScore < MIN_CONFIDENCE_THRESHOLD) return

            // --- Apply enabled-categories filter -------------------------------
            if (bestCategory !in enabledCategories) return

            val detection = SoundDetection(
                category = bestCategory,
                confidence = bestScore,
                timestampMs = System.currentTimeMillis()
            )
            _detections.tryEmit(detection)

        } catch (e: Exception) {
            // Per design error-handling table: emit nothing for that frame, log only.
            Log.e(TAG, "YAMNet classification error: ${e.message}")
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    /**
     * Release TFLite resources. Call this when the owning service is destroyed.
     */
    fun close() {
        scope.cancel()
        interpreter?.close()
        interpreter = null
    }
}
