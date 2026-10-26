package com.example.tryhearingaid.captions

import com.example.tryhearingaid.data.CaptionSegment
import kotlinx.coroutines.flow.StateFlow

/**
 * Wraps Android SpeechRecognizer (on-device via ML Kit) with speaker diarization.
 *
 * - [captions] emits the current list of [CaptionSegment]s for the active session.
 * - [isActive] reflects whether the engine is currently transcribing.
 * - [isUnavailable] emits `true` when the device's SpeechRecognizer is not available
 *   (e.g. not installed, no network for cloud model). The UI should show a
 *   "Captions unavailable" overlay in this case.
 * - [start] begins recognition; [stop] ends it.
 * - [setConfidenceThreshold] controls which segments are marked [CaptionSegment.isUncertain].
 */
interface CaptionEngine {
    /** Emits the growing list of caption segments for the current session. */
    val captions: StateFlow<List<CaptionSegment>>

    /** True while the engine is actively recognizing speech. */
    val isActive: StateFlow<Boolean>

    /**
     * True when the device's SpeechRecognizer is unavailable.
     * Defaults to `false`; set to `true` by the implementation when recognition
     * cannot be started (e.g. `SpeechRecognizer.isRecognitionAvailable` returns false).
     */
    val isUnavailable: StateFlow<Boolean>

    /**
     * Start continuous speech recognition in [languageCode].
     * If already active this is a no-op.
     */
    fun start(languageCode: String = "en-US")

    /** Stop recognition. Clears [captions] and sets [isActive] to false. */
    fun stop()

    /**
     * Set the minimum confidence score below which segments are marked
     * [CaptionSegment.isUncertain]. Default = 0.70.
     */
    fun setConfidenceThreshold(threshold: Float)
}

// ─── Fake CaptionEngine for tests ────────────────────────────────────────────

/**
 * Simple in-memory fake for unit tests. Captions can be pushed via [pushSegment].
 */
class FakeCaptionEngine : CaptionEngine {

    private val _captions = kotlinx.coroutines.flow.MutableStateFlow<List<CaptionSegment>>(emptyList())
    override val captions: StateFlow<List<CaptionSegment>> = _captions

    private val _isActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val isActive: StateFlow<Boolean> = _isActive

    private val _isUnavailable = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val isUnavailable: StateFlow<Boolean> = _isUnavailable

    var startCallCount = 0
    var stopCallCount = 0
    var lastLanguageCode: String? = null

    override fun start(languageCode: String) {
        startCallCount++
        lastLanguageCode = languageCode
        _isActive.value = true
    }

    override fun stop() {
        stopCallCount++
        _isActive.value = false
    }

    override fun setConfidenceThreshold(threshold: Float) { /* no-op for fake */ }

    /**
     * Test helper: simulate the scenario where SpeechRecognizer is unavailable
     * (mirrors what CaptionEngineImpl does when isRecognitionAvailable() == false).
     */
    fun simulateUnavailable() {
        _isUnavailable.value = true
        _isActive.value = false
    }

    /** Push a caption segment as if the recognizer produced it. */
    fun pushSegment(segment: CaptionSegment) {
        _captions.value = _captions.value + segment
    }
}
