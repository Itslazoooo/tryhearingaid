package com.example.tryhearingaid.captions

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.tryhearingaid.audio.AudioEngine
import com.example.tryhearingaid.audio.AudioEngineState
import com.example.tryhearingaid.audio.AudioProcessingStage
import com.example.tryhearingaid.audio.SoundClassifierTap
import com.example.tryhearingaid.data.CaptionSegment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Live captions from an offline speech model that runs inside the app (no internet, no Google app).
 *
 * The sound comes from the amplifier's own microphone stream while the amplifier runs, so captions
 * and amplifier never fight over the microphone. When the amplifier is off, the engine opens the
 * microphone itself. It also takes part in the audio chain as a listen-only stage.
 */
@Singleton
class VoskCaptionEngine @Inject constructor(
    @Suppress("unused") @ApplicationContext private val context: Context,
    private val store: VoskModelStore,
    private val audioEngine: AudioEngine
) : CaptionEngine, AudioProcessingStage {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _captions = MutableStateFlow<List<CaptionSegment>>(emptyList())
    override val captions: StateFlow<List<CaptionSegment>> = _captions.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    override val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _isUnavailable = MutableStateFlow(false)
    override val isUnavailable: StateFlow<Boolean> = _isUnavailable.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // 16 kHz audio waiting for the model. Old audio is dropped if the model falls behind.
    private val frames = Channel<ShortArray>(capacity = 200, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile private var capturing = false
    @Volatile private var threshold = DEFAULT_THRESHOLD
    private var job: Job? = null
    private var recorderJob: Job? = null
    private val speakers = SpeakerTracker()

    // Loudness of the sentence being heard, for the speaker guess.
    private var energySum = 0.0
    private var energySamples = 0L

    // ── AudioProcessingStage (listen only) ────────────────────────────────────

    override val isEnabled: Boolean get() = capturing
    override val runsInRawMode: Boolean get() = true
    override fun setEnabled(enabled: Boolean) = Unit

    override fun process(buffer: ShortArray, sampleRate: Int, channelCount: Int): ShortArray {
        if (!capturing || channelCount != 1) return buffer
        val factor = SoundClassifierTap.decimationFactor(sampleRate) ?: return buffer
        // Always a new array: later stages change the original in place.
        frames.trySend(if (factor == 1) buffer.copyOf() else SoundClassifierTap.downsample(buffer, factor))
        return buffer
    }

    // ── CaptionEngine ─────────────────────────────────────────────────────────

    override fun start(languageCode: String) {
        if (_isActive.value) return
        val root = store.modelRoot(languageCode)
        if (root == null) {
            _lastError.value = "The offline speech model is not downloaded yet."
            _isUnavailable.value = true
            return
        }
        _lastError.value = null
        _isUnavailable.value = false
        _captions.value = emptyList()
        speakers.reset()
        while (frames.tryReceive().isSuccess) { /* drop old audio */ }
        _isActive.value = true

        job = scope.launch {
            val loaded = load(root.absolutePath)
            if (loaded == null) {
                _lastError.value = "The offline speech model could not be loaded."
                _isUnavailable.value = true
                _isActive.value = false
                return@launch
            }
            val (model, recognizer) = loaded
            capturing = true
            val sourceJob = launch { followAudioSource() }
            try {
                for (frame in frames) {
                    for (s in frame) energySum += s.toDouble() * s
                    energySamples += frame.size
                    if (recognizer.acceptWaveForm(frame, frame.size)) emit(recognizer.result)
                }
            } finally {
                sourceJob.cancel()
                runCatching { emit(recognizer.finalResult) }
                runCatching { recognizer.close() }
                runCatching { model.close() }
            }
        }
    }

    /** Loads the model and a recognizer for it, or returns null (and cleans up) if that fails. */
    private fun load(path: String): Pair<Model, Recognizer>? {
        var model: Model? = null
        return try {
            val loadedModel = Model(path)
            model = loadedModel
            loadedModel to Recognizer(loadedModel, MODEL_RATE.toFloat()).also { it.setWords(true) }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not load the speech model", t)
            runCatching { model?.close() }
            null
        }
    }

    override fun stop() {
        capturing = false
        _isActive.value = false
        _lastError.value = null
        recorderJob?.cancel()
        recorderJob = null
        job?.cancel()
        job = null
    }

    override fun setConfidenceThreshold(threshold: Float) {
        this.threshold = threshold.coerceIn(0f, 1f)
    }

    // ── Audio source ──────────────────────────────────────────────────────────

    /** Uses the amplifier's audio while it runs, and the phone's microphone directly otherwise. */
    private suspend fun followAudioSource() {
        audioEngine.state.collect { state ->
            val engineHasMic = state == AudioEngineState.RUNNING || state == AudioEngineState.STARTING
            if (engineHasMic) {
                recorderJob?.cancel()
                recorderJob = null
            } else if (recorderJob?.isActive != true) {
                recorderJob = scope.launch { recordLoop() }
            }
        }
    }

    private suspend fun recordLoop() = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(
            MODEL_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            _lastError.value = "Could not use the microphone."
            return@withContext
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, MODEL_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4
            )
        } catch (e: Exception) {
            _lastError.value = "Could not use the microphone."
            return@withContext
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            _lastError.value = "Could not use the microphone (it may be in use)."
            return@withContext
        }
        val buffer = ShortArray(MODEL_RATE / 10) // 100 ms
        try {
            record.startRecording()
            while (isActive) {
                val n = record.read(buffer, 0, buffer.size)
                if (n > 0) frames.trySend(buffer.copyOf(n)) else if (n < 0) break
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    // ── Results ───────────────────────────────────────────────────────────────

    private fun emit(json: String) {
        val utterance = VoskResults.parse(json)
        val rms = if (energySamples > 0) sqrt(energySum / energySamples).toFloat() else 0f
        energySum = 0.0
        energySamples = 0L
        utterance ?: return

        val now = System.currentTimeMillis()
        val segment = CaptionSegment(
            id = UUID.randomUUID(),
            speakerLabel = speakers.labelFor(rms, now),
            text = utterance.text,
            confidence = utterance.confidence,
            timestampMs = now,
            isUncertain = utterance.confidence < threshold
        )
        _captions.value = _captions.value + segment
    }

    private companion object {
        const val TAG = "VoskCaptionEngine"
        const val MODEL_RATE = 16_000
        const val DEFAULT_THRESHOLD = 0.70f
    }
}
