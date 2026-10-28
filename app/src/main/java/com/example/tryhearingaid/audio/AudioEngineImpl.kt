package com.example.tryhearingaid.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Concrete implementation of [AudioEngine] using [AudioRecord] and [AudioTrack] in
 * EXCLUSIVE low-latency mode on a dedicated high-priority thread.
 *
 * ### Gain handling
 * - [setGain] quantizes the requested dB value to the nearest 1 dB increment via
 *   `round(dB).toInt().toFloat()`, then clamps to [0f, 40f].
 * - The clamped value is stored as [currentGain] (dB) and converted to a linear
 *   multiplier `10^(gain/20)` before being applied to each PCM frame.
 *
 * ### Pipeline
 * Each captured frame is passed sequentially through every registered
 * [AudioProcessingStage] in insertion order, then the gain multiplier is applied,
 * and the resulting samples are written to [AudioTrack].
 *
 * ### Loudness warning
 * If the RMS SPL of the output frame would exceed 85 dB SPL (relative to PCM full
 * scale used as 0 dB SPL reference per NIOSH convention), a [loudnessWarning] event
 * is emitted and the gain is clamped so output stays at or below that ceiling.
 *
 * ### Thread model
 * A [HandlerThread] named "AudioEngine" runs at [Process.THREAD_PRIORITY_URGENT_AUDIO].
 * The I/O loop runs on that thread; the [AudioRecord] and [AudioTrack] instances are
 * also created on it so that their thread-affinity guarantees are respected.
 *
 * ### Adaptive mode
 * [setAdaptiveMode] stores the flag; the actual adaptive loop is implemented in task 3.4.
 */
@Singleton // One engine for the whole app: the service and the ViewModels must share it.
class AudioEngineImpl @Inject constructor() : AudioEngine {

    // ── Public state ──────────────────────────────────────────────────────────

    private val _state = MutableStateFlow(AudioEngineState.IDLE)
    override val state: StateFlow<AudioEngineState> = _state.asStateFlow()

    private val _currentGain = MutableStateFlow(0f)
    override val currentGain: Float get() = _currentGain.value

    /**
     * Emits [Unit] whenever the output SPL would exceed 85 dB SPL, indicating
     * that the gain was clamped to prevent harmful loudness levels (Requirement 6.4).
     */
    private val _loudnessWarning = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val loudnessWarning: SharedFlow<Unit> = _loudnessWarning.asSharedFlow()

    // ── Internal state ────────────────────────────────────────────────────────

    /** Linear gain multiplier derived from [_currentGain] (in dB). */
    @Volatile private var gainLinear: Float = 1f

    @Volatile private var adaptiveModeEnabled: Boolean = false

    /** Processing stages appended via [addProcessingStage]. */
    private val processingStages = mutableListOf<AudioProcessingStage>()

    /** Config supplied to the most recent [start] call. */
    @Volatile private var activeConfig: AudioConfig = AudioConfig()

    /**
     * Output device explicitly selected via [routeOutput]. When non-null it
     * overrides the `outputDevice` of any [start] config so that a default
     * [start] call still honours a previously-routed device (Requirement 14.2).
     */
    @Volatile private var routedOutputDevice: AudioDevice? = null

    /** Running flag controlling the audio I/O loop. */
    @Volatile private var running: Boolean = false

    // ── Adaptive amplification state (Requirement 6.1 – 6.4) ─────────────────
    /** Sum of squared RMS values accumulated over the 2-second measurement window. */
    private var adaptiveNoiseAccumulator: Double = 0.0

    /** Number of frames accumulated in the current 2-second window. */
    private var adaptiveFrameCount: Int = 0

    /**
     * The target gain (dB) computed from the measured noise floor plus the 15 dB SNR
     * target, clamped to [0, 40] dB (Requirement 6.1, 6.3).
     */
    private var adaptiveTargetGainDb: Float = 0f

    /**
     * The smoothed current adaptive gain (dB) that converges toward
     * [adaptiveTargetGainDb] using a 500 ms exponential time constant (Requirement 6.2).
     */
    private var adaptiveCurrentGainDb: Float = 0f

    /** Dedicated high-priority background thread. */
    private var audioThread: HandlerThread? = null

    /** Currently active [AudioRecord] instance (non-null while running). */
    private var audioRecord: AudioRecord? = null

    /** Currently active [AudioTrack] instance (non-null while running). */
    private var audioTrack: AudioTrack? = null

    // ── 85 dB SPL ceiling ─────────────────────────────────────────────────────
    // RMS of a full-scale 16-bit sine wave ≈ 32768 / sqrt(2) ≈ 23170.
    // We define 85 dB SPL as RMS = 32768 * 10^((85 - 96) / 20) ≈ 32768 * 0.355 ≈ 11633.
    // Using 96 dBFS as the reference for a full-scale 16-bit signal.
    private val spl85RmsThreshold: Float = 32768f * 10f.pow((85f - 96f) / 20f)

    companion object {
        private const val TAG = "AudioEngineImpl"

        /** PCM encoding used throughout. */
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** Input channel configuration: mono capture. */
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO

        /** Output channel configuration: mono playback. */
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO

        /** Maximum allowed gain in dB (Requirement 1.2, 6.3). */
        const val GAIN_MAX_DB = 40f

        /** Minimum allowed gain in dB (Requirement 1.2). */
        const val GAIN_MIN_DB = 0f

        /** Frames of silence written before playback starts. */
        private const val PREFILL_FRAMES = 3

        /** How fast the loudness limiter lets go after a loud moment (per frame, 0..1). */
        private const val LIMITER_RELEASE = 0.02f
    }

    /** Smoothed gain used by the loudness limiter, so loudness changes are gradual, not choppy. */
    private var limiterGain: Float = 1f

    // ── AudioEngine interface ─────────────────────────────────────────────────

    /**
     * Start audio capture and playback with [config].
     *
     * Transitions: IDLE → STARTING → RUNNING (or ERROR on failure).
     * Creates [AudioRecord] and [AudioTrack] in EXCLUSIVE low-latency mode on a
     * dedicated [HandlerThread] running at [Process.THREAD_PRIORITY_URGENT_AUDIO].
     */
    override fun start(config: AudioConfig) {
        if (_state.value == AudioEngineState.RUNNING || _state.value == AudioEngineState.STARTING) {
            Log.w(TAG, "start() called while engine is already ${ _state.value }; ignoring.")
            return
        }
        // Honour a device explicitly routed via routeOutput() so that a default
        // start() call still routes to the previously-selected output device
        // (Requirement 14.2).
        val effectiveConfig = if (routedOutputDevice != null) {
            config.copy(outputDevice = routedOutputDevice!!)
        } else {
            config
        }
        _state.value = AudioEngineState.STARTING
        activeConfig = effectiveConfig

        // Start dedicated high-priority thread
        val thread = HandlerThread("AudioEngine", Process.THREAD_PRIORITY_URGENT_AUDIO).also {
            it.start()
            audioThread = it
        }

        // Run the entire setup + I/O loop on the audio thread
        thread.looper.let {
            android.os.Handler(it).post {
                try {
                    initAudioDevices(effectiveConfig)
                    _state.value = AudioEngineState.RUNNING
                    audioLoop(effectiveConfig)
                } catch (e: Exception) {
                    Log.e(TAG, "Audio engine error: ${e.message}", e)
                    _state.value = AudioEngineState.ERROR
                    releaseAudioDevices()
                }
            }
        }
    }

    /**
     * Stop audio capture and playback, releasing all audio resources.
     *
     * Transitions state to IDLE after resources are released.
     */
    override fun stop() {
        running = false
        // Give the loop a moment to notice the flag, then force-release
        audioThread?.quitSafely()
        audioThread = null
        releaseAudioDevices()
        _state.value = AudioEngineState.IDLE
    }

    /**
     * Set the amplification gain (Requirement 1.2).
     *
     * The [dB] value is:
     * 1. Quantized to the nearest 1 dB: `round(dB).toInt().toFloat()`
     * 2. Clamped to [0f, 40f]
     * 3. Converted to a linear multiplier: `10^(clampedDb / 20)`
     */
    override fun setGain(dB: Float) {
        val quantized = dB.roundToInt().toFloat()
        val clamped = quantized.coerceIn(GAIN_MIN_DB, GAIN_MAX_DB)
        _currentGain.value = clamped
        gainLinear = 10f.pow(clamped / 20f)
    }

    /**
     * Enable or disable adaptive amplification mode (Requirement 6.1).
     * The actual adaptive loop is implemented in task 3.4; this method stores the flag.
     */
    override fun setAdaptiveMode(enabled: Boolean) {
        adaptiveModeEnabled = enabled
    }

    /**
     * Append [stage] to the end of the audio processing chain.
     * Stages are applied in insertion order during the audio I/O loop.
     *
     * Adding a stage that is already in the chain (the same object) does nothing. The engine
     * outlives the audio service, and the service adds its stages every time it starts, so
     * without this check a restarted service would process the audio twice.
     */
    override fun addProcessingStage(stage: AudioProcessingStage) {
        synchronized(processingStages) {
            if (processingStages.none { it === stage }) {
                processingStages.add(stage)
            }
        }
    }

    /**
     * Return the current output SPL in dB (computed from the last written frame).
     * Returns 0f when the engine is not [AudioEngineState.RUNNING].
     */
    override fun getCurrentSplDb(): Float {
        if (_state.value != AudioEngineState.RUNNING) return 0f
        return lastOutputSplDb
    }

    /**
     * Route audio output to [device] (Requirement 14.2).
     *
     * - Stores [device] in [routedOutputDevice] and [activeConfig] so the next
     *   [start] call routes to it.
     * - If the engine is currently running, restart it so processed audio is
     *   immediately sent to the new output device (e.g. a connected A2DP device).
     *
     * Routing to [AudioDevice.Bluetooth] relies on the OS automatically sending
     * [AudioAttributes.USAGE_MEDIA] audio to the connected A2DP sink. If routing
     * fails on restart the engine transitions to [AudioEngineState.ERROR]; the
     * caller may recover by routing back to [AudioDevice.Default]
     * (earpiece/speaker fallback — see the design error-handling table).
     */
    override fun routeOutput(device: AudioDevice) {
        Log.i(TAG, "Routing output to $device")
        routedOutputDevice = device
        activeConfig = activeConfig.copy(outputDevice = device)

        val isRunning = _state.value == AudioEngineState.RUNNING ||
            _state.value == AudioEngineState.STARTING
        if (isRunning) {
            // Restart with the new output device. stop() tears down the current
            // AudioRecord/AudioTrack; start() rebuilds them with the new routing.
            stop()
            start(activeConfig)
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** SPL of the last output frame (updated on the audio thread). */
    @Volatile private var lastOutputSplDb: Float = 0f

    /**
     * Build the [AudioAttributes] for the playback [AudioTrack] based on the
     * configured output device (Requirement 14.2).
     *
     * - [AudioDevice.Bluetooth] → [AudioAttributes.USAGE_MEDIA]. The OS
     *   automatically routes media audio to the connected A2DP sink, so processed
     *   hearing-aid audio is sent to the Bluetooth device.
     * - [AudioDevice.Earpiece] → [AudioAttributes.USAGE_VOICE_COMMUNICATION] so
     *   the built-in earpiece receiver is selected (fallback output).
     * - [AudioDevice.Speaker] / [AudioDevice.Default] → [AudioAttributes.USAGE_MEDIA].
     *
     * Speech content type is used throughout so the system applies its speech
     * audio routing/pre-processing policies.
     */
    private fun buildOutputAttributes(config: AudioConfig): AudioAttributes {
        val usage = when (config.outputDevice) {
            is AudioDevice.Earpiece -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            is AudioDevice.Bluetooth -> {
                Log.i(TAG, "Routing output to Bluetooth A2DP device ${config.outputDevice.address}")
                AudioAttributes.USAGE_MEDIA
            }
            is AudioDevice.Speaker -> AudioAttributes.USAGE_MEDIA
            is AudioDevice.Default -> AudioAttributes.USAGE_MEDIA
        }
        return AudioAttributes.Builder()
            .setUsage(usage)
            // Raw mode asks for no speech-specific treatment from the system.
            .setContentType(
                if (config.rawMode) AudioAttributes.CONTENT_TYPE_MUSIC
                else AudioAttributes.CONTENT_TYPE_SPEECH
            )
            .build()
    }

    /**
     * Initialise [AudioRecord] and [AudioTrack] in EXCLUSIVE low-latency mode.
     * Throws [IllegalStateException] if either device fails to initialise.
     */
    private fun initAudioDevices(config: AudioConfig) {
        val sampleRate = config.sampleRate
        // Generous buffers (about 80 ms) stop the crackling that very small buffers cause when the
        // audio thread is delayed for even a moment.
        val minBufSize = bufferBytes(
            AudioRecord.getMinBufferSize(sampleRate, CHANNEL_IN, ENCODING), config
        )

        fun buildRecord(source: Int) = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(ENCODING)
                    .setChannelMask(CHANNEL_IN)
                    .build()
            )
            .setBufferSizeInBytes(minBufSize)
            .build()

        // Raw mode prefers the unprocessed microphone, which skips the phone's own automatic
        // gain and noise handling (the usual reason a phone microphone sounds "squashed").
        val record = if (config.rawMode) {
            runCatching { buildRecord(MediaRecorder.AudioSource.UNPROCESSED) }.getOrNull()
                ?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                ?: buildRecord(MediaRecorder.AudioSource.MIC)
        } else {
            buildRecord(MediaRecorder.AudioSource.MIC)
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialise (state=${record.state})")
        }

        val outMinBufSize = bufferBytes(
            AudioTrack.getMinBufferSize(sampleRate, CHANNEL_OUT, ENCODING), config
        )

        val trackBuilder = AudioTrack.Builder()
            .setAudioAttributes(buildOutputAttributes(config))
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(ENCODING)
                    .setChannelMask(CHANNEL_OUT)
                    .build()
            )
            .setBufferSizeInBytes(outMinBufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val track = trackBuilder.build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            record.release()
            track.release()
            throw IllegalStateException("AudioTrack failed to initialise (state=${track.state})")
        }

        audioRecord = record
        audioTrack = track
    }

    /**
     * Size in bytes for a system audio buffer: never below what the system needs, and otherwise
     * [AudioConfig.bufferFrames] frames. Big buffers mean fewer dropouts but more delay.
     */
    private fun bufferBytes(systemMinBytes: Int, config: AudioConfig): Int {
        val wanted = config.frameSize * 2 * config.bufferFrames
        val system = if (config.bufferFrames >= 8) systemMinBytes * 2 else systemMinBytes
        return maxOf(system, wanted)
    }

    /**
     * The main audio capture → process → playback loop.
     * Runs on the dedicated audio thread until [running] becomes false or an
     * exception is thrown.
     *
     * When [adaptiveModeEnabled] is true, the adaptive amplification loop
     * (Requirements 6.1 – 6.4) runs inside this method:
     *   1. Accumulate RMS of each captured frame into a 2-second window.
     *   2. Every 2 seconds, derive the noise-floor dBFS and set [adaptiveTargetGainDb]
     *      to (noiseFloorDb + 15) clamped to [0, 40] dB.
     *   3. Each frame, exponentially smooth [adaptiveCurrentGainDb] toward
     *      [adaptiveTargetGainDb] using a 500 ms time constant.
     *   4. Override [gainLinear] with the smoothed adaptive value before the gain/
     *      loudness check so all existing ceiling logic continues to apply.
     */
    private fun audioLoop(config: AudioConfig) {
        val record = audioRecord ?: return
        val track = audioTrack ?: return

        val frameSize = config.frameSize           // samples per frame
        val sampleRate = config.sampleRate
        val channelCount = config.channelCount
        val buffer = ShortArray(frameSize)

        // Duration of one frame in milliseconds (e.g. 160 samples / 16 000 Hz = 10 ms)
        val frameSizeMs = (frameSize.toFloat() / sampleRate.toFloat()) * 1000f

        // Smoothing factor: fraction of the 500 ms transition window covered by one frame.
        // alpha ≈ 0.02 for a 10 ms frame and a 500 ms time constant.
        val smoothingAlpha = (frameSizeMs / 500f).coerceIn(0f, 1f)

        // How many frames make up the 2-second noise-floor measurement window.
        val framesPerWindow = (2000f / frameSizeMs).toInt().coerceAtLeast(1)

        // Reset adaptive state so stale values from a previous run don't bleed in.
        adaptiveNoiseAccumulator = 0.0
        adaptiveFrameCount = 0
        adaptiveTargetGainDb = 0f
        adaptiveCurrentGainDb = 0f

        // Start the output with a little silence so it does not run dry (and crackle) at the start.
        val prefill = frameSize * config.prefillFrames
        if (prefill > 0) track.write(ShortArray(prefill), 0, prefill)
        limiterGain = 1f
        record.startRecording()
        track.play()
        running = true

        try {
            while (running) {
                // 1. Capture a frame from the microphone
                val readCount = record.read(buffer, 0, frameSize)
                if (readCount <= 0) continue   // skip empty/error reads

                // Raw mode: the microphone sound goes to the output with only the gain applied.
                if (config.rawMode) {
                    var raw: ShortArray = buffer.copyOf(readCount)
                    synchronized(processingStages) {
                        for (stage in processingStages) {
                            // Listen-only stages (the sound recogniser) keep running; they do not
                            // change the sound.
                            if (stage.isEnabled && stage.runsInRawMode) {
                                stage.process(raw, sampleRate, channelCount)
                            }
                        }
                    }
                    raw = applyRawGain(raw)
                    lastOutputSplDb = computeSplDb(raw)
                    track.write(raw, 0, raw.size)
                    continue
                }

                // 2. Adaptive amplification loop (Requirement 6.1 – 6.4)
                if (adaptiveModeEnabled) {
                    // Accumulate RMS of the raw (pre-processed) captured frame
                    val frameRms = computeRms(buffer.copyOf(readCount))
                    adaptiveNoiseAccumulator += frameRms.toDouble()
                    adaptiveFrameCount++

                    // Every 2-second window: recompute noise floor and update target gain
                    if (adaptiveFrameCount >= framesPerWindow) {
                        val avgRms = (adaptiveNoiseAccumulator / adaptiveFrameCount).toFloat()

                        // Convert average RMS to dBFS using the same 96 dBFS reference
                        // used by computeSplDb (16-bit full scale = 32767)
                        val noiseFloorDb = if (avgRms > 0f) {
                            96f + 20f * kotlin.math.log10(avgRms / 32767f)
                        } else {
                            0f
                        }

                        // Target: noise floor + 15 dB SNR, clamped to [0, 40] dB (Req 6.1, 6.3)
                        adaptiveTargetGainDb = (noiseFloorDb + 15f).coerceIn(GAIN_MIN_DB, GAIN_MAX_DB)

                        // Reset accumulator for next window
                        adaptiveNoiseAccumulator = 0.0
                        adaptiveFrameCount = 0
                    }

                    // Each frame: exponentially smooth current gain toward target (Req 6.2)
                    // This produces a 500 ms ramp regardless of how large the step is.
                    adaptiveCurrentGainDb += smoothingAlpha * (adaptiveTargetGainDb - adaptiveCurrentGainDb)
                    val clampedAdaptiveDb = adaptiveCurrentGainDb.coerceIn(GAIN_MIN_DB, GAIN_MAX_DB)

                    // Override gainLinear so the existing loudness check uses the adaptive value
                    gainLinear = 10f.pow(clampedAdaptiveDb / 20f)

                    // Keep the observable currentGain state in sync
                    _currentGain.value = clampedAdaptiveDb
                }
                // When adaptiveModeEnabled == false: gainLinear and _currentGain are
                // controlled exclusively by setGain() — no changes needed here.

                // 3. Pass frame through all enabled processing stages
                var processed: ShortArray = buffer.copyOf(readCount)
                synchronized(processingStages) {
                    for (stage in processingStages) {
                        if (stage.isEnabled) {
                            processed = stage.process(processed, sampleRate, channelCount)
                        }
                    }
                }

                // 4. Apply gain and check 85 dB SPL ceiling (Requirement 6.4)
                processed = applyGainWithLoudnessCheck(processed)

                // 5. Update the current SPL measurement
                lastOutputSplDb = computeSplDb(processed)

                // 6. Write to AudioTrack output
                track.write(processed, 0, processed.size)
            }
        } finally {
            runCatching { record.stop() }
            runCatching { track.stop() }
        }
    }

    /**
     * Apply [gainLinear] to [samples] in-place, clamping each sample to the
     * valid 16-bit range. If the resulting RMS exceeds the 85 dB SPL threshold,
     * reduce the multiplier so the frame stays at or below the ceiling and emit
     * a [loudnessWarning] event (Requirement 6.4).
     *
     * Returns the modified samples (same array, modified in place).
     */
    private fun applyGainWithLoudnessCheck(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples
        val rms = computeRms(samples)

        // The most gain that keeps this frame at or below the 85 dB SPL ceiling.
        var target = gainLinear
        if (rms > 0f && rms * target > spl85RmsThreshold) {
            target = spl85RmsThreshold / rms
            // Emit loudness warning (non-blocking; drops if buffer is full)
            _loudnessWarning.tryEmit(Unit)
        }

        // Go down at once when a sound is too loud, come back up slowly. The gain then glides
        // across the frame instead of jumping at frame edges, which would sound like crackle.
        val start = limiterGain
        val end = if (target < start) target else start + (target - start) * LIMITER_RELEASE
        limiterGain = end

        val n = samples.size
        for (i in 0 until n) {
            val g = start + (end - start) * (i + 1) / n
            val amplified = (samples[i] * g).toInt()
            samples[i] = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    /**
     * Raw mode gain: plain multiplication by the chosen gain, nothing else. Samples that would go
     * past full scale are cut at full scale. The loudness warning still fires when the level is
     * above the 85 dB reference, but the sound is not turned down, so keep the gain moderate.
     */
    private fun applyRawGain(samples: ShortArray): ShortArray {
        val gain = gainLinear
        if (computeRms(samples) * gain > spl85RmsThreshold) _loudnessWarning.tryEmit(Unit)
        return applyPlainGain(samples, gain)
    }

    /** Compute the RMS amplitude of [samples]. */
    private fun computeRms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in samples) sumSq += s.toDouble() * s.toDouble()
        return sqrt(sumSq / samples.size).toFloat()
    }

    /**
     * Compute the SPL in dBFS (dB relative to full scale) for [samples].
     * Returns 0f for silent frames.
     */
    private fun computeSplDb(samples: ShortArray): Float {
        val rms = computeRms(samples)
        if (rms <= 0f) return 0f
        // 96 dBFS reference for 16-bit PCM full scale (32767)
        return 96f + 20f * kotlin.math.log10(rms / 32767f)
    }

    /** Release [AudioRecord] and [AudioTrack] resources safely. */
    private fun releaseAudioDevices() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null

        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
    }
}

/** Multiplies every sample by [gain], cutting at the 16-bit limits. Returns [samples] changed in place. */
internal fun applyPlainGain(samples: ShortArray, gain: Float): ShortArray {
    for (i in samples.indices) {
        samples[i] = (samples[i] * gain).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
    return samples
}
