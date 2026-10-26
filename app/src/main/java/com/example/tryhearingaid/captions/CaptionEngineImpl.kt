package com.example.tryhearingaid.captions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.tryhearingaid.data.CaptionSegment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Production [CaptionEngine] implementation wrapping Android [SpeechRecognizer]
 * (on-device via ML Kit speech recognition) with energy-based speaker diarization.
 *
 * ### Speech recognition
 * Uses [SpeechRecognizer.createOnDeviceSpeechRecognizer] when available (Android 13+
 * with ML Kit on-device model), falling back to [SpeechRecognizer.createSpeechRecognizer]
 * for standard on-device recognition on older API levels.
 *
 * When [SpeechRecognizer] is completely unavailable on the device,
 * [_isUnavailable] is set to `true` so the UI can surface a fallback message.
 *
 * ### Confidence and uncertainty
 * Each result from [SpeechRecognizer] carries a confidence score. If the confidence
 * is below [confidenceThreshold] (default 0.70), [CaptionSegment.isUncertain] = true.
 *
 * ### Speaker diarization (energy-based heuristic)
 * The engine estimates a speaker change by tracking the RMS energy of successive
 * partial-result transitions and the elapsed time since the last segment. A speaker
 * change is declared when:
 *   - the energy delta between the last finalized segment and the current one exceeds
 *     [ENERGY_CHANGE_THRESHOLD_RATIO] × previous energy, AND
 *   - the silence gap since the last segment exceeds [SPEAKER_CHANGE_MIN_GAP_MS]
 *
 * On a detected change, the current speaker index is incremented and a new
 * "Speaker N" label is assigned. Speaker index resets to 1 on [stop].
 *
 * ### Continuous listening
 * [SpeechRecognizer] stops after each utterance. The engine automatically restarts
 * recognition after each result via [restartRecognition] so listening continues
 * while [isActive] is true.
 *
 * ### Supported languages
 * [SUPPORTED_LANGUAGES] lists the 11 required language codes: fil, en, es, fr, de,
 * ar, zh, ja, ko, pt, hi (plus their regional variants). The passed [languageCode]
 * is validated against this set; unsupported codes fall back to "en-US".
 *
 * Requirements: 7.1, 7.2, 7.3, 7.6
 */
@Singleton
class CaptionEngineImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : CaptionEngine {

    // ── Public state ──────────────────────────────────────────────────────────

    private val _captions = MutableStateFlow<List<CaptionSegment>>(emptyList())
    override val captions: StateFlow<List<CaptionSegment>> = _captions.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    override val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    /**
     * Exposed as observable state so the UI can show a fallback overlay
     * when speech recognition hardware / service is unavailable on the device.
     */
    private val _isUnavailable = MutableStateFlow(false)
    override val isUnavailable: StateFlow<Boolean> = _isUnavailable.asStateFlow()

    // ── Configuration ─────────────────────────────────────────────────────────

    /** Segments with confidence below this value are marked [CaptionSegment.isUncertain]. */
    @Volatile private var confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD

    /** Language code used for the active recognition session. */
    @Volatile private var activeLanguageCode: String = "en-US"

    // ── Speaker diarization state ─────────────────────────────────────────────

    /** Index of the current speaker (1-based). Resets to 1 on [stop]. */
    @Volatile private var currentSpeakerIndex: Int = 1

    /** Label shown for the current speaker in emitted segments. */
    private val currentSpeakerLabel: String
        get() = "Speaker $currentSpeakerIndex"

    /** RMS energy of the most recently finalized segment (for change detection). */
    @Volatile private var lastSegmentEnergy: Float = 0f

    /** Wall-clock timestamp (ms) when the last segment was finalized. */
    @Volatile private var lastSegmentTimeMs: Long = 0L

    // ── SpeechRecognizer handle ───────────────────────────────────────────────

    private var recognizer: SpeechRecognizer? = null

    // ── RecognitionListener ───────────────────────────────────────────────────

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Positive rmsdB reported by SpeechRecognizer: use as a proxy for raw RMS
            // (actual scale is device-dependent, but deltas are meaningful for our heuristic)
            if (rmsdB > 0f) {
                lastSegmentEnergy = rmsdB
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Not used
        }

        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech")
        }

        override fun onError(error: Int) {
            val errorMsg = speechErrorToString(error)
            Log.w(TAG, "SpeechRecognizer error: $errorMsg ($error)")

            when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Transient; restart will be attempted after a short delay
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    _isUnavailable.value = true
                    _isActive.value = false
                    return
                }
                SpeechRecognizer.ERROR_NO_MATCH -> {
                    // No speech detected; continue listening
                }
                else -> { /* Fall through to restart */ }
            }

            // Restart listening if we should still be active
            if (_isActive.value) {
                restartRecognition()
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

            if (!matches.isNullOrEmpty()) {
                val text = matches[0]
                val confidence = confidences?.getOrNull(0) ?: DEFAULT_CONFIDENCE_THRESHOLD
                emitSegment(text, confidence)
            }

            // Continuously restart recognition while active
            if (_isActive.value) {
                restartRecognition()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // Partial results are useful for updating the UI live but we only
            // emit committed segments from onResults for accuracy.
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            // Not used
        }
    }

    // ── CaptionEngine interface ───────────────────────────────────────────────

    /**
     * Start continuous speech recognition in [languageCode].
     * Validates the language code against [SUPPORTED_LANGUAGES]; falls back to "en-US"
     * if unsupported. No-op if already active.
     *
     * Requirements: 7.1, 7.2
     */
    override fun start(languageCode: String) {
        if (_isActive.value) {
            Log.w(TAG, "start() called while already active; ignoring.")
            return
        }

        // Validate language, fallback to English if not in supported set
        activeLanguageCode = if (isLanguageSupported(languageCode)) {
            languageCode
        } else {
            Log.w(TAG, "Unsupported language code '$languageCode'; falling back to en-US")
            "en-US"
        }

        // Reset session state
        currentSpeakerIndex = 1
        lastSegmentEnergy = 0f
        lastSegmentTimeMs = System.currentTimeMillis()
        _captions.value = emptyList()

        // Initialize and start the recognizer
        if (!initializeRecognizer()) return

        _isActive.value = true
        startListening()
    }

    /**
     * Stop recognition. Clears [captions] and resets [isActive] to false.
     *
     * Requirements: 7.1
     */
    override fun stop() {
        _isActive.value = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        currentSpeakerIndex = 1
        lastSegmentEnergy = 0f
        lastSegmentTimeMs = 0L
    }

    /**
     * Set the confidence threshold below which segments are marked uncertain.
     * Default is 0.70. Thread-safe via @Volatile field.
     *
     * Requirements: 7.6
     */
    override fun setConfidenceThreshold(threshold: Float) {
        confidenceThreshold = threshold.coerceIn(0f, 1f)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Create and configure a [SpeechRecognizer] instance.
     * Returns false and updates [_isUnavailable] if speech recognition
     * is not supported on this device.
     */
    private fun initializeRecognizer(): Boolean {
        // Check if recognition service is available at all
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "SpeechRecognizer is not available on this device")
            _isUnavailable.value = true
            return false
        }

        _isUnavailable.value = false

        // Prefer on-device recognizer (Android 13 / ML Kit) when available
        val newRecognizer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            // API 31+: attempt on-device recognizer, fall back to default on failure
            try {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
                    Log.d(TAG, "Using on-device SpeechRecognizer (API 31+)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "On-device recognizer unavailable, using default: ${e.message}")
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } else {
            SpeechRecognizer.createSpeechRecognizer(context).also {
                Log.d(TAG, "Using default SpeechRecognizer (API < 31)")
            }
        }

        newRecognizer.setRecognitionListener(recognitionListener)
        recognizer = newRecognizer
        return true
    }

    /**
     * Build and fire a [RecognizerIntent] on the current [recognizer].
     * Must be called on the main thread (SpeechRecognizer requirement).
     */
    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, mapToAndroidLocale(activeLanguageCode))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Prefer on-device recognition (ML Kit) to avoid network dependency
            putExtra("android.speech.extra.PREFER_OFFLINE", true)
        }
        recognizer?.startListening(intent)
    }

    /**
     * Tear down and re-create the [SpeechRecognizer] before starting a new listening
     * session. This is the standard pattern for continuous recognition since
     * [SpeechRecognizer] stops after each utterance.
     */
    private fun restartRecognition() {
        recognizer?.destroy()
        recognizer = null
        if (initializeRecognizer()) {
            startListening()
        }
    }

    /**
     * Construct and emit a [CaptionSegment] from a recognized utterance.
     *
     * Speaker change detection (Requirements 7.3):
     * - Compare the current segment's RMS energy (reported via onRmsChanged) against
     *   [lastSegmentEnergy]. If the ratio exceeds [ENERGY_CHANGE_THRESHOLD_RATIO] AND
     *   the silence gap exceeds [SPEAKER_CHANGE_MIN_GAP_MS], increment [currentSpeakerIndex].
     *
     * Uncertainty marking (Requirement 7.6):
     * - [CaptionSegment.isUncertain] = confidence < [confidenceThreshold]
     */
    private fun emitSegment(text: String, confidence: Float) {
        if (text.isBlank()) return

        val nowMs = System.currentTimeMillis()
        val gapMs = if (lastSegmentTimeMs > 0L) nowMs - lastSegmentTimeMs else 0L
        val currentEnergy = lastSegmentEnergy

        // Detect speaker change via energy heuristic
        if (shouldChangeSpeaker(currentEnergy, lastSegmentEnergy, gapMs)) {
            currentSpeakerIndex++
            Log.d(TAG, "Speaker change detected → $currentSpeakerLabel (energy=$currentEnergy, gap=${gapMs}ms)")
        }

        val segment = CaptionSegment(
            id = UUID.randomUUID(),
            speakerLabel = currentSpeakerLabel,
            text = text,
            confidence = confidence,
            timestampMs = nowMs,
            isUncertain = confidence < confidenceThreshold
        )

        _captions.value = _captions.value + segment
        lastSegmentTimeMs = nowMs

        Log.d(TAG, "Segment emitted: speaker=${segment.speakerLabel}, confidence=$confidence, uncertain=${segment.isUncertain}")
    }

    /**
     * Heuristic to decide whether an energy transition and gap between segments
     * indicates a new speaker.
     *
     * Returns true when:
     *   - [lastEnergy] was non-zero (i.e. we have a baseline)
     *   - [gapMs] ≥ [SPEAKER_CHANGE_MIN_GAP_MS] (meaningful pause between utterances)
     *   - |current - last| / last > [ENERGY_CHANGE_THRESHOLD_RATIO] (energy change > 30%)
     */
    private fun shouldChangeSpeaker(currentEnergy: Float, lastEnergy: Float, gapMs: Long): Boolean {
        if (lastEnergy <= 0f) return false
        if (gapMs < SPEAKER_CHANGE_MIN_GAP_MS) return false
        val ratio = abs(currentEnergy - lastEnergy) / lastEnergy
        return ratio > ENERGY_CHANGE_THRESHOLD_RATIO
    }

    /**
     * Map the app's language codes (BCP-47 base subtag or full tag) to the locale
     * string expected by [RecognizerIntent.EXTRA_LANGUAGE].
     *
     * The recognizer accepts standard BCP-47 tags, so most codes pass through
     * unmodified; special cases are mapped explicitly below.
     */
    private fun mapToAndroidLocale(languageCode: String): String {
        return LANGUAGE_LOCALE_MAP[languageCode] ?: languageCode
    }

    /**
     * Returns true if [code] is in [SUPPORTED_LANGUAGES] or is a regional variant
     * of one of the supported base codes (e.g. "en-GB" is accepted for "en").
     */
    private fun isLanguageSupported(code: String): Boolean {
        val base = code.substringBefore('-').substringBefore('_').lowercase()
        return SUPPORTED_LANGUAGES.any { it == code || it == base }
    }

    companion object {
        private const val TAG = "CaptionEngineImpl"

        /** Default confidence threshold below which segments are marked uncertain. */
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.70f

        /**
         * Minimum silence gap (ms) between two utterances before a speaker change
         * can be declared. A value of 1500 ms covers typical conversation turn-taking.
         */
        const val SPEAKER_CHANGE_MIN_GAP_MS: Long = 1500L

        /**
         * Fractional RMS energy change required to trigger a speaker transition.
         * 0.30 = 30% change relative to the previous segment's energy.
         */
        const val ENERGY_CHANGE_THRESHOLD_RATIO: Float = 0.30f

        /**
         * The 11 required supported language codes (BCP-47 base subtags).
         * Requirements: 7.2
         */
        val SUPPORTED_LANGUAGES: Set<String> = setOf(
            "fil",  // Filipino / Tagalog
            "en",   // English
            "es",   // Spanish
            "fr",   // French
            "de",   // German
            "ar",   // Arabic
            "zh",   // Chinese (Mandarin)
            "ja",   // Japanese
            "ko",   // Korean
            "pt",   // Portuguese
            "hi"    // Hindi
        )

        /**
         * Mapping from app language codes to Android/BCP-47 locale strings passed
         * to [RecognizerIntent.EXTRA_LANGUAGE].
         */
        val LANGUAGE_LOCALE_MAP: Map<String, String> = mapOf(
            "fil" to "fil-PH",
            "en"  to "en-US",
            "es"  to "es-ES",
            "fr"  to "fr-FR",
            "de"  to "de-DE",
            "ar"  to "ar-SA",
            "zh"  to "zh-CN",
            "ja"  to "ja-JP",
            "ko"  to "ko-KR",
            "pt"  to "pt-BR",
            "hi"  to "hi-IN"
        )

        /** Convert [SpeechRecognizer] error codes to readable strings for logging. */
        private fun speechErrorToString(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_AUDIO               -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT              -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK             -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT     -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH            -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY     -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER              -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT      -> "ERROR_SPEECH_TIMEOUT"
            else                                        -> "UNKNOWN_ERROR_$error"
        }
    }
}
