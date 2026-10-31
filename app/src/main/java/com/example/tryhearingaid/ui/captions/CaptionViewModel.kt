package com.example.tryhearingaid.ui.captions

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tryhearingaid.ai.AIAssistant
import com.example.tryhearingaid.captions.CaptionEngine
import com.example.tryhearingaid.captions.TranscriptStore
import com.example.tryhearingaid.data.LocalUserFilesImpl
import com.example.tryhearingaid.data.CaptionSegment
import com.example.tryhearingaid.translation.NoTranslation
import com.example.tryhearingaid.translation.TextTranslator
import com.example.tryhearingaid.translation.TranslationResult
import com.example.tryhearingaid.data.StudyNotes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

// ─── Supported languages ──────────────────────────────────────────────────────

/**
 * Each supported caption language as a (BCP-47 code, display name) pair.
 * Satisfies Requirement 7.2: at least 11 languages including the listed ones.
 */
data class CaptionLanguage(val code: String, val displayName: String)

val SUPPORTED_CAPTION_LANGUAGES = listOf(
    CaptionLanguage("fil", "Filipino"),
    CaptionLanguage("en",  "English"),
    CaptionLanguage("es",  "Spanish"),
    CaptionLanguage("fr",  "French"),
    CaptionLanguage("de",  "German"),
    CaptionLanguage("ar",  "Arabic"),
    CaptionLanguage("zh",  "Chinese"),
    CaptionLanguage("ja",  "Japanese"),
    CaptionLanguage("ko",  "Korean"),
    CaptionLanguage("pt",  "Portuguese"),
    CaptionLanguage("hi",  "Hindi")
)

// ─── UI State ─────────────────────────────────────────────────────────────────

/**
 * Snapshot of everything [CaptionsScreen] needs to render.
 *
 * - [captions]         — ordered list of transcribed segments for the current session.
 * - [isActive]         — true while the [CaptionEngine] is actively transcribing.
 * - [selectedLanguage] — currently selected [CaptionLanguage].
 * - [isUnavailable]    — true when the device's SpeechRecognizer is unavailable.
 * - [isClassroomMode]  — when true, captions + AI summarization + recording are
 *                        all active simultaneously (Requirement 13.1).
 * - [studyNotes]       — non-null once study notes have been generated after a session.
 * - [keywordContext]   — segments surrounding a selected keyword (Requirement 13.6).
 * - [exportedFile]     — non-null after [exportTranscript] completes.
 * - [audioFilePath]    — path to the audio recording saved during classroom mode
 *                        (Requirement 13.5), null when not recording.
 */
data class CaptionUiState(
    val captions: List<CaptionSegment> = emptyList(),
    val isActive: Boolean = false,
    val selectedLanguage: CaptionLanguage = SUPPORTED_CAPTION_LANGUAGES[1], // default: English
    val isUnavailable: Boolean = false,
    val engineError: String? = null,
    val isClassroomMode: Boolean = false,
    val studyNotes: StudyNotes? = null,
    val keywordContext: List<CaptionSegment> = emptyList(),
    val exportedFile: File? = null,
    val audioFilePath: String? = null,
    /** Show a translation under each caption (Requirement 7: original and translated text). */
    val translationEnabled: Boolean = false,
    val translationTarget: CaptionLanguage = SUPPORTED_CAPTION_LANGUAGES[1],
    /** Translated text by caption id; a caption without an entry is not translated yet. */
    val translations: Map<UUID, String> = emptyMap(),
    /** Why translation is not producing text right now, or null. */
    val translationStatus: String? = null,
    /** A summary of the captions so far, written by the offline AI. */
    val summary: String? = null,
    /** True while the offline AI is writing a summary or study notes. */
    val aiBusy: Boolean = false,
    /** Why the AI has nothing to show right now (no model, no captions, failure), or null. */
    val aiMessage: String? = null
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

/**
 * Bridges [CaptionsScreen] with [CaptionEngine], [TranscriptStore], and [AIAssistant].
 *
 * Responsibilities:
 * - Forwards start/stop commands to the engine with the selected language code.
 * - Mirrors [CaptionEngine.captions] and [CaptionEngine.isActive] into [uiState].
 * - Persists segments to [TranscriptStore] as they arrive.
 * - Exports the current session transcript to a shareable [File] via [exportTranscript].
 * - Implements classroom mode (Requirement 13.1):
 *   [activateClassroomMode] atomically starts all three sub-systems in a single coroutine.
 *   [deactivateClassroomMode] stops recording, generates study notes, and persists them.
 * - Keyword context lookup: [lookupKeywordContext] queries [TranscriptStore] and returns
 *   surrounding segments within 1 second (Requirement 13.6).
 * - Audio recording via [MediaRecorder] alongside the text transcript (Requirement 13.5).
 *
 * Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 13.6
 */
@HiltViewModel
class CaptionViewModel @Inject constructor(
    private val captionEngine: CaptionEngine,
    private val transcriptStore: TranscriptStore,
    private val aiAssistant: AIAssistant,
    @ApplicationContext private val appContext: Context,
    private val translator: TextTranslator = NoTranslation
) : ViewModel() {

    companion object {
        private const val TAG = "CaptionViewModel"

        /**
         * Number of segments to include before and after the keyword match when
         * returning keyword context (Requirement 13.6).
         */
        const val KEYWORD_CONTEXT_WINDOW = 2

        const val MODEL_MISSING_MESSAGE =
            "Download the offline AI in Settings to get summaries and study notes."
        const val AI_FAILED_MESSAGE =
            "The offline AI could not finish. Try again in a moment."
    }

    /** Session identifier; stable for the lifetime of this ViewModel instance. */
    val sessionId: UUID = UUID.randomUUID()

    private val _uiState = MutableStateFlow(CaptionUiState())
    val uiState: StateFlow<CaptionUiState> = _uiState.asStateFlow()

    /** MediaRecorder instance used during classroom audio recording (Requirement 13.5). */
    private var mediaRecorder: MediaRecorder? = null

    /** Path of the audio file being recorded during classroom mode. */
    private var currentAudioFilePath: String? = null

    /** Job that collects AI importantPoints during classroom mode. */
    private var importantPointsJob: Job? = null

    init {
        // Mirror engine caption list into UI state and persist new segments.
        viewModelScope.launch {
            captionEngine.captions.collect { segments ->
                _uiState.value = _uiState.value.copy(captions = segments)
                translateNew(segments)
                // Persist the latest segment added (if any).
                segments.lastOrNull()?.let { latest ->
                    transcriptStore.saveSegment(latest, sessionId)
                }
            }
        }

        // Mirror engine active state into UI state.
        viewModelScope.launch {
            captionEngine.isActive.collect { active ->
                _uiState.value = _uiState.value.copy(isActive = active)
            }
        }

        // Mirror the engine's "why is there no text" message into UI state.
        viewModelScope.launch {
            captionEngine.lastError.collect { message ->
                _uiState.value = _uiState.value.copy(engineError = message)
            }
        }

        // Mirror engine unavailability into UI state.
        viewModelScope.launch {
            captionEngine.isUnavailable.collect { unavailable ->
                _uiState.value = _uiState.value.copy(isUnavailable = unavailable)
            }
        }
    }

    // ── Standard caption commands ─────────────────────────────────────────────

    /** Start caption recognition using the currently selected language. */
    fun startCaptions() {
        captionEngine.start(_uiState.value.selectedLanguage.code)
    }

    /** Stop caption recognition. */
    fun stopCaptions() {
        captionEngine.stop()
    }

    /** Select a new recognition language. If active, restarts the engine. */
    fun selectLanguage(language: CaptionLanguage) {
        val wasActive = _uiState.value.isActive
        if (wasActive) captionEngine.stop()
        _uiState.value = _uiState.value.copy(selectedLanguage = language)
        if (wasActive) captionEngine.start(language.code)
    }

    // ── Translation ───────────────────────────────────────────────────────────

    private val pendingTranslations = mutableSetOf<UUID>()

    fun setTranslationEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(translationEnabled = enabled, translationStatus = null)
        if (enabled) translateNew(_uiState.value.captions)
    }

    fun selectTranslationTarget(language: CaptionLanguage) {
        pendingTranslations.clear()
        _uiState.value = _uiState.value.copy(
            translationTarget = language,
            translations = emptyMap(),
            translationStatus = null
        )
        translateNew(_uiState.value.captions)
    }

    /** Translates each caption that has no translation yet. A failure shows a short reason. */
    private fun translateNew(segments: List<CaptionSegment>) {
        val state = _uiState.value
        if (!state.translationEnabled) return
        val from = state.selectedLanguage.code
        val to = state.translationTarget.code
        segments
            .filter { it.id !in state.translations && pendingTranslations.add(it.id) }
            .forEach { segment ->
                viewModelScope.launch {
                    val result = translator.translate(segment.text, from, to)
                    pendingTranslations.remove(segment.id)
                    val current = _uiState.value
                    _uiState.value = when (result) {
                        is TranslationResult.Text -> current.copy(
                            translations = current.translations + (segment.id to result.text),
                            translationStatus = null
                        )
                        is TranslationResult.Unavailable -> current.copy(translationStatus = result.reason)
                    }
                }
            }
    }

    // ── Classroom mode ────────────────────────────────────────────────────────

    /**
     * Atomically activates classroom mode by starting:
     * 1. [CaptionEngine] — speech-to-text transcription
     * 2. [AIAssistant] importantPoints subscription — real-time summarization
     * 3. [TranscriptStore] session recording — persists the transcript session
     * 4. [MediaRecorder] — audio file recording alongside the text transcript
     *
     * All sub-systems are activated within a single coroutine to satisfy
     * Requirement 13.1 (single-action enablement).
     */
    fun activateClassroomMode() {
        if (_uiState.value.isClassroomMode) return
        viewModelScope.launch {
            // 1. Start caption engine
            captionEngine.start(_uiState.value.selectedLanguage.code)

            // 2. Subscribe to AI important points
            importantPointsJob?.cancel()
            importantPointsJob = viewModelScope.launch {
                aiAssistant.importantPoints.collect { point ->
                    Log.d(TAG, "Important point detected: [${point.type}] ${point.text}")
                    // Important points are surfaced in the UI through the shared flow;
                    // future UI work can display these in a dedicated card list.
                }
            }

            // 3. Ensure session exists in TranscriptStore
            //    (The existing collect-captions coroutine in init{} already calls
            //    saveSegment which creates the session on first segment, but we
            //    also explicitly record the session start here for audio path tracking.)

            // 4. Start audio recording
            val audioPath = startAudioRecording()
            currentAudioFilePath = audioPath

            // Persist audio file path if recording started successfully
            if (audioPath != null) {
                transcriptStore.saveAudioFilePath(sessionId, audioPath)
            }

            _uiState.value = _uiState.value.copy(
                isClassroomMode = true,
                audioFilePath = audioPath,
                studyNotes = null,
                keywordContext = emptyList()
            )

            Log.d(TAG, "Classroom mode activated for session $sessionId, audio: $audioPath")
        }
    }

    /**
     * Deactivates classroom mode:
     * 1. Stops the [CaptionEngine].
     * 2. Stops the [MediaRecorder] and finalises the audio file.
     * 3. Cancels the AI important-points subscription.
     * 4. Generates structured study notes via [AIAssistant.generateStudyNotes].
     * 5. Persists the [StudyNotes] result and updates UI state.
     *
     * Study notes are generated within 30 seconds of session end (Requirement 13.2).
     */
    fun deactivateClassroomMode() {
        if (!_uiState.value.isClassroomMode) return
        viewModelScope.launch {
            // 1. Stop caption engine
            captionEngine.stop()

            // 2. Stop audio recording
            stopAudioRecording()

            // 3. Cancel important-points subscription
            importantPointsJob?.cancel()
            importantPointsJob = null

            _uiState.value = _uiState.value.copy(isClassroomMode = false)

            // 4. Generate study notes from the session transcript (Requirement 13.2)
            val transcript = transcriptStore.getSession(sessionId)
            if (transcript.isNotEmpty()) {
                if (!aiAssistant.isOnDeviceReady()) {
                    _uiState.value = _uiState.value.copy(aiMessage = MODEL_MISSING_MESSAGE)
                } else {
                    _uiState.value = _uiState.value.copy(aiBusy = true, aiMessage = null)
                    try {
                        val notes = aiAssistant.generateStudyNotes(transcript)
                        _uiState.value = _uiState.value.copy(studyNotes = notes)
                        Log.d(TAG, "Study notes generated for session $sessionId: ${notes.title}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to generate study notes: ${e.message}")
                        _uiState.value = _uiState.value.copy(aiMessage = AI_FAILED_MESSAGE)
                    } finally {
                        _uiState.value = _uiState.value.copy(aiBusy = false)
                    }
                }
            }

            Log.d(TAG, "Classroom mode deactivated for session $sessionId")
        }
    }

    /**
     * Legacy toggle for backward-compatibility. Delegates to [activateClassroomMode]
     * or [deactivateClassroomMode] depending on current state.
     */
    fun toggleClassroomMode() {
        if (_uiState.value.isClassroomMode) {
            deactivateClassroomMode()
        } else {
            activateClassroomMode()
        }
    }

    // ── Keyword context lookup ────────────────────────────────────────────────

    /**
     * Look up the surrounding transcript context for [keyword] within 1 second
     * (Requirement 13.6).
     *
     * Returns the [KEYWORD_CONTEXT_WINDOW] segments before and after the first
     * segment whose text contains [keyword] (case-insensitive). The result is
     * reflected in [CaptionUiState.keywordContext].
     *
     * If no matching segment is found, [keywordContext] is set to an empty list.
     */
    fun lookupKeywordContext(keyword: String) {
        viewModelScope.launch {
            val context = getKeywordContext(keyword)
            _uiState.value = _uiState.value.copy(keywordContext = context)
        }
    }

    /**
     * Internal implementation that queries [TranscriptStore] and filters segments
     * by [keyword]. Returns surrounding segments for the first match.
     */
    internal suspend fun getKeywordContext(keyword: String): List<CaptionSegment> {
        val allSegments = transcriptStore.getSession(sessionId)
        if (allSegments.isEmpty() || keyword.isBlank()) return emptyList()

        val matchIndex = allSegments.indexOfFirst { segment ->
            segment.text.contains(keyword, ignoreCase = true)
        }
        if (matchIndex < 0) return emptyList()

        val start = (matchIndex - KEYWORD_CONTEXT_WINDOW).coerceAtLeast(0)
        val end = (matchIndex + KEYWORD_CONTEXT_WINDOW + 1).coerceAtMost(allSegments.size)
        return allSegments.subList(start, end)
    }

    /** Clear the keyword context result (called after UI has consumed it). */
    fun clearKeywordContext() {
        _uiState.value = _uiState.value.copy(keywordContext = emptyList())
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Export the current session as a plain-text file and signal the UI to show
     * the Android share sheet (Requirement 7.5).
     *
     * After the UI handles the share event it should call [clearExportedFile].
     */
    fun exportTranscript() {
        viewModelScope.launch {
            val file = transcriptStore.exportAsText(sessionId)
            _uiState.value = _uiState.value.copy(exportedFile = file)
        }
    }

    /** Writes a short summary of the captions so far with the offline AI. */
    fun summarizeNow() = runAi { transcript ->
        val text = aiAssistant.summarize(transcript)
        _uiState.value = _uiState.value.copy(summary = text)
    }

    /** Writes study notes from the captions so far with the offline AI. */
    fun makeNotesNow() = runAi { transcript ->
        val notes = aiAssistant.generateStudyNotes(transcript)
        _uiState.value = _uiState.value.copy(studyNotes = notes)
    }

    private fun runAi(work: suspend (List<CaptionSegment>) -> Unit) {
        val state = _uiState.value
        if (state.aiBusy) return
        val transcript = state.captions
        when {
            transcript.isEmpty() ->
                _uiState.value = state.copy(aiMessage = "There are no captions to work from yet.")
            !aiAssistant.isOnDeviceReady() ->
                _uiState.value = state.copy(aiMessage = MODEL_MISSING_MESSAGE)
            else -> {
                _uiState.value = state.copy(aiBusy = true, aiMessage = null)
                viewModelScope.launch {
                    try {
                        work(transcript)
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        _uiState.value = _uiState.value.copy(
                            aiMessage = "This took too long. Try again, or with fewer captions."
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "The offline AI failed: ${e.message}")
                        _uiState.value = _uiState.value.copy(aiMessage = AI_FAILED_MESSAGE)
                    } finally {
                        _uiState.value = _uiState.value.copy(aiBusy = false)
                    }
                }
            }
        }
    }

    /** Called by the UI after it has consumed the exported-file share event. */
    fun clearExportedFile() {
        _uiState.value = _uiState.value.copy(exportedFile = null)
    }

    // ── Audio recording (MediaRecorder) ───────────────────────────────────────

    /**
     * Starts audio recording to an MP4/AAC file in the app's internal files directory.
     * Returns the absolute path of the output file, or null if recording could not start.
     *
     * Requirement 13.5: audio recorded alongside text transcript.
     */
    private fun startAudioRecording(): String? {
        return try {
            val audioDir = File(appContext.filesDir, LocalUserFilesImpl.CLASSROOM_AUDIO_DIR).apply { mkdirs() }
            val outputFile = File(audioDir, "session_${sessionId}_${Instant.now().epochSecond}.m4a")

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(appContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            Log.d(TAG, "Audio recording started: ${outputFile.absolutePath}")
            outputFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording: ${e.message}")
            mediaRecorder?.release()
            mediaRecorder = null
            null
        }
    }

    /**
     * Stops the active [MediaRecorder] and releases its resources.
     * Silently no-ops if no recording is active.
     */
    private fun stopAudioRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recording: ${e.message}")
            mediaRecorder?.release()
        } finally {
            mediaRecorder = null
            currentAudioFilePath = null
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        // Ensure resources are released even if the ViewModel is cleared mid-session.
        if (_uiState.value.isClassroomMode) {
            captionEngine.stop()
            stopAudioRecording()
            importantPointsJob?.cancel()
        } else {
            captionEngine.stop()
        }
    }
}
