package com.example.tryhearingaid.ai

import com.example.tryhearingaid.data.CaptionSegment
import com.example.tryhearingaid.data.ImportantPoint
import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.data.StudyNotes
import com.example.tryhearingaid.data.VocabTerm
import com.example.tryhearingaid.data.VocabTermEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.UUID

/**
 * Concrete implementation of [AIAssistant] that:
 *
 * - Uses [cloudClient] (Gemini REST API) when online.
 * - Falls back to [onDeviceClient] (Gemini Nano / on-device) when offline.
 * - Monitors connectivity via [networkCallback]; switches [isOnlineMode] automatically.
 * - On cloud 5xx errors retries once after 2 s, then falls back to on-device.
 * - Tracks vocabulary via [vocabularyDao]; inserts new words with AI-generated definitions.
 * - Emits [importantPoints] for dates, deadlines, action items, and proper nouns.
 *
 * All public suspend functions apply timeouts:
 * - [summarize], [translate], [define], [describeSound] → 5 s
 * - [generateStudyNotes] → 10 s
 *
 * On-device availability is guarded by [isGeminiAvailable]; when false the
 * [onDeviceClient] throws [GeminiUnavailableException] and a best-effort
 * offline stub message is returned.
 */
class AIAssistantImpl(
    private val cloudClient: GeminiClient,
    private val onDeviceClient: GeminiClient,
    private val vocabularyDao: VocabularyDao,
    private val networkCallback: NetworkCallback,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Time allowed for a summary, translation, definition. A small model on a phone needs longer. */
    private val shortTimeoutMs: Long = 5_000L,
    /** Time allowed for study notes. */
    private val longTimeoutMs: Long = 10_000L,
    /** The most transcript text one request may carry; longer text is condensed in pieces first. */
    private val maxPromptChars: Int = Int.MAX_VALUE,
    private val onDeviceReady: () -> Boolean = { true }
) : AIAssistant, NetworkStateListener {

    // ── State ─────────────────────────────────────────────────────────────────

    private val _isOnlineMode = MutableStateFlow(false)
    override val isOnlineMode: StateFlow<Boolean> = _isOnlineMode.asStateFlow()

    private val _importantPoints = MutableSharedFlow<ImportantPoint>(extraBufferCapacity = 64)
    override val importantPoints: SharedFlow<ImportantPoint> = _importantPoints.asSharedFlow()

    override fun isOnDeviceReady(): Boolean = onDeviceReady()

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        networkCallback.registerListener(this)
    }

    // ── NetworkStateListener ──────────────────────────────────────────────────

    override fun onNetworkAvailable() {
        _isOnlineMode.value = true
    }

    override fun onNetworkLost() {
        _isOnlineMode.value = false
    }

    // ── AIAssistant suspend functions ─────────────────────────────────────────

    override suspend fun summarize(transcript: List<CaptionSegment>): String =
        withTimeout(shortTimeoutMs) {
            val text = transcript.joinToString(" ") { it.text }
            val body = condense(text)
            if (body == text) {
                executeWithFallback("Summarize the following transcript concisely:\n\n$text")
            } else {
                // Long transcript: the pieces were summarised; now join those into one summary.
                executeWithFallback("Summarize the following notes concisely:\n\n$body")
            }
        }

    override suspend fun translate(text: String, targetLanguage: String): String =
        withTimeout(shortTimeoutMs) {
            val prompt = "Translate the following text to $targetLanguage:\n\n$text"
            executeWithFallback(prompt)
        }

    override suspend fun define(word: String): String =
        withTimeout(shortTimeoutMs) {
            // Check vocabulary cache first
            val cached = vocabularyDao.getAll().firstOrNull {
                it.word.equals(word, ignoreCase = true)
            }
            if (cached != null) return@withTimeout cached.definition

            val prompt = "Provide a short plain-language definition of the word: $word"
            executeWithFallback(prompt)
        }

    override suspend fun generateStudyNotes(transcript: List<CaptionSegment>): StudyNotes =
        withTimeout(longTimeoutMs) {
            val text = condense(transcript.joinToString("\n") { "[${it.speakerLabel}] ${it.text}" })
            val prompt = """Generate structured study notes from the following transcript.
Format:
TITLE: <title>
KEY_TOPICS: <comma-separated topics>
ACTION_ITEMS: <comma-separated action items>
SUMMARY: <brief summary>

Transcript:
$text"""
            val response = executeWithFallback(prompt)
            parseStudyNotes(response, transcript)
        }

    /**
     * Sound descriptions are written ahead of time and need no model, download or internet, so
     * they are instant and identical online and offline. [context] may carry "confidence NN percent".
     */
    override suspend fun describeSound(category: SoundCategory, context: String): String =
        com.example.tryhearingaid.environmental.SoundDescriptions.describe(
            category,
            com.example.tryhearingaid.environmental.SoundDescriptions.confidenceFrom(context)
        )

    // ── Vocabulary tracking ───────────────────────────────────────────────────

    /**
     * Process a [segment] for vocabulary tracking and important-point detection.
     * Called internally whenever new captions arrive.
     */
    suspend fun processSegment(segment: CaptionSegment, sessionId: UUID = UUID.randomUUID()) {
        // Vocabulary tracking: extract words and look up unknown ones
        val words = segment.text
            .split(Regex("\\s+"))
            .map { it.trim().lowercase().filter { c -> c.isLetter() } }
            .filter { it.length > 3 }   // ignore short function words

        for (word in words) {
            if (word.isEmpty()) continue
            if (!vocabularyDao.contains(word)) {
                try {
                    val definition = withTimeout(shortTimeoutMs) {
                        val prompt = "Define the word '$word' in one simple sentence."
                        executeWithFallback(prompt)
                    }
                    val entity = VocabTermEntity(
                        id = UUID.randomUUID(),
                        sessionId = sessionId,
                        word = word,
                        definition = definition,
                        contextSnippet = segment.text.take(100)
                    )
                    vocabularyDao.insert(entity)
                } catch (_: Exception) {
                    // Best-effort — skip on failure
                }
            }
        }

        // Important-point scanning
        scanForImportantPoints(segment)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Make [text] short enough for one request: when it is too long, summarise it piece by piece
     * (a few rounds at most) and return what is left. Short text is returned unchanged.
     */
    private suspend fun condense(text: String): String {
        var current = text
        var rounds = 0
        while (current.length > maxPromptChars && rounds < 3) {
            current = TranscriptChunker.chunk(current, maxPromptChars).joinToString("\n") { piece ->
                executeWithFallback("Summarize the following transcript concisely:\n\n$piece")
            }
            rounds++
        }
        return if (current.length > maxPromptChars) current.take(maxPromptChars) else current
    }

    /**
     * Execute [prompt] against the appropriate backend.
     * When online: try cloud, on 5xx retry once after 2 s, then fall back to on-device.
     * When offline: use on-device directly.
     */
    private suspend fun executeWithFallback(prompt: String): String {
        return if (_isOnlineMode.value) {
            try {
                cloudClient.generate(prompt)
            } catch (e: GeminiServerException) {
                // Retry once after 2 s
                delay(2_000L)
                try {
                    cloudClient.generate(prompt)
                } catch (_: GeminiServerException) {
                    // Cloud still failing — fall back to on-device
                    onDeviceGenerate(prompt)
                }
            } catch (_: GeminiUnavailableException) {
                onDeviceGenerate(prompt)
            }
        } else {
            onDeviceGenerate(prompt)
        }
    }

    private suspend fun onDeviceGenerate(prompt: String): String {
        return try {
            onDeviceClient.generate(prompt)
        } catch (_: Exception) {
            "[AI unavailable: ${prompt.take(30)}…]"
        }
    }

    /**
     * Scan [segment] text for dates, deadlines, action items, and proper nouns
     * and emit [ImportantPoint] events for any found.
     */
    private fun scanForImportantPoints(segment: CaptionSegment) {
        val text = segment.text

        // Date patterns (simple heuristics)
        val datePattern = Regex(
            """\b(?:January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{1,2}|\b\d{1,2}/\d{1,2}/\d{2,4}\b|\btoday\b|\btomorrow\b|\bnext\s+\w+\b""",
            RegexOption.IGNORE_CASE
        )
        if (datePattern.containsMatchIn(text)) {
            scope.launch {
                _importantPoints.emit(
                    ImportantPoint(text = text, type = "date", timestampMs = segment.timestampMs)
                )
            }
            return
        }

        // Deadline / urgency keywords
        val deadlinePattern = Regex(
            """\b(?:deadline|due|submit|by\s+\w+|urgent|asap|by\s+end)\b""",
            RegexOption.IGNORE_CASE
        )
        if (deadlinePattern.containsMatchIn(text)) {
            scope.launch {
                _importantPoints.emit(
                    ImportantPoint(text = text, type = "deadline", timestampMs = segment.timestampMs)
                )
            }
            return
        }

        // Action items
        val actionPattern = Regex(
            """\b(?:please|make sure|remember|don't forget|need to|must|should|will|action item|follow up|send|review|complete)\b""",
            RegexOption.IGNORE_CASE
        )
        if (actionPattern.containsMatchIn(text)) {
            scope.launch {
                _importantPoints.emit(
                    ImportantPoint(text = text, type = "action_item", timestampMs = segment.timestampMs)
                )
            }
            return
        }

        // Proper nouns (capitalized words that are not at sentence start and not common words)
        val words = text.split(Regex("\\s+"))
        val properNouns = words.drop(1).filter { word ->
            word.isNotEmpty() && word[0].isUpperCase() &&
                    word.all { c -> c.isLetter() || c == '-' } &&
                    word.length > 2
        }
        if (properNouns.isNotEmpty()) {
            scope.launch {
                _importantPoints.emit(
                    ImportantPoint(text = text, type = "proper_noun", timestampMs = segment.timestampMs)
                )
            }
        }
    }

    /**
     * Parse a structured study-notes response string into a [StudyNotes] object.
     */
    private fun parseStudyNotes(response: String, transcript: List<CaptionSegment>): StudyNotes {
        val lines = response.lines()

        // Small models often add bullets or bold marks around the labels, so allow for them.
        fun extractField(prefix: String): String {
            val line = lines.map { it.trim().trimStart('*', '#', '-', ' ').replace("**", "") }
                .firstOrNull { it.startsWith(prefix, ignoreCase = true) } ?: return ""
            return line.substring(prefix.length).trim()
        }

        val title    = extractField("TITLE:").ifEmpty { "Lecture Notes" }
        val topics   = extractField("KEY_TOPICS:").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val actions  = extractField("ACTION_ITEMS:").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val summary  = extractField("SUMMARY:").ifEmpty { response.take(200) }

        // Collect vocabulary terms from the transcript text (words with definitions in vocab store)
        val vocabTerms = mutableListOf<VocabTerm>()

        return StudyNotes(
            sessionId = UUID.randomUUID(),
            title = title,
            keyTopics = topics.ifEmpty { listOf("Key Topic") },
            vocabularyTerms = vocabTerms,
            summary = summary,
            generatedAt = Instant.now()
        )
    }

    /** Guard for on-device Gemini availability (Android 14+ with AI Core). */
    @Suppress("FunctionName")
    private fun isGeminiAvailable(): Boolean {
        // In a real implementation this would check android.os.Build.VERSION.SDK_INT >= 34
        // and verify the on-device model is loaded.
        return true
    }
}
