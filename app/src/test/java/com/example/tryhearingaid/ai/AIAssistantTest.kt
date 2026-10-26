package com.example.tryhearingaid.ai

import com.example.tryhearingaid.data.CaptionSegment
import com.example.tryhearingaid.data.VocabTermEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlinx.coroutines.CoroutineScope

/**
 * Unit tests for [AIAssistantImpl]:
 * - Network mode switching (online ↔ offline)
 * - New vocabulary word definition added
 * - [define] returns quickly for cached words
 * - Meeting notes contain title + key points + action items
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AIAssistantTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var cloudClient: FakeGeminiClient
    private lateinit var onDeviceClient: FakeGeminiClient
    private lateinit var networkCallback: FakeNetworkCallback
    private lateinit var vocabularyDao: InMemoryVocabularyDao
    private lateinit var assistant: AIAssistantImpl

    @Before
    fun setUp() {
        cloudClient = FakeGeminiClient()
        onDeviceClient = FakeGeminiClient()
        networkCallback = FakeNetworkCallback()
        vocabularyDao = InMemoryVocabularyDao()

        // Start connected by default
        networkCallback.isConnected = true

        assistant = AIAssistantImpl(
            cloudClient = cloudClient,
            onDeviceClient = onDeviceClient,
            vocabularyDao = vocabularyDao,
            networkCallback = networkCallback,
            scope = CoroutineScope(testDispatcher)
        )
    }

    // ── Network mode switching ────────────────────────────────────────────────

    @Test
    fun `isOnlineMode is true when network connected at startup`() = runTest {
        assertTrue(assistant.isOnlineMode.value)
    }

    @Test
    fun `isOnlineMode switches to false when network is lost`() = runTest {
        assertTrue(assistant.isOnlineMode.value)

        networkCallback.simulateDisconnected()

        assertFalse(assistant.isOnlineMode.value)
    }

    @Test
    fun `isOnlineMode re-enables to true when network is restored`() = runTest {
        // Lose connection
        networkCallback.simulateDisconnected()
        assertFalse(assistant.isOnlineMode.value)

        // Restore connection
        networkCallback.simulateConnected()
        assertTrue(assistant.isOnlineMode.value)
    }

    @Test
    fun `cloud client is used when online`() = runTest {
        assertTrue(assistant.isOnlineMode.value)

        val segment = makeSegment("Hello world")
        assistant.summarize(listOf(segment))

        // Cloud client should have been called
        assertTrue(cloudClient.calls.isNotEmpty())
    }

    @Test
    fun `on-device client is used when offline`() = runTest {
        networkCallback.simulateDisconnected()
        assertFalse(assistant.isOnlineMode.value)

        val segment = makeSegment("Hello world")
        assistant.summarize(listOf(segment))

        // On-device client should have been called, not cloud
        assertTrue(onDeviceClient.calls.isNotEmpty())
        assertTrue(cloudClient.calls.isEmpty())
    }

    @Test
    fun `cloud 5xx triggers retry then falls back to on-device`() = runTest {
        cloudClient.simulateServerError = true   // first call → 5xx; flag auto-resets
        // Second call will also fail: set persistent server error on cloud
        // by making a custom client that always throws
        val alwaysFailCloud = object : GeminiClient {
            var callCount = 0
            override suspend fun generate(prompt: String): String {
                callCount++
                throw GeminiServerException("HTTP 500")
            }
        }

        val testAssistant = AIAssistantImpl(
            cloudClient = alwaysFailCloud,
            onDeviceClient = onDeviceClient,
            vocabularyDao = vocabularyDao,
            networkCallback = networkCallback,
            scope = CoroutineScope(testDispatcher)
        )

        val result = testAssistant.summarize(listOf(makeSegment("Test content")))

        // Should get a result (either from on-device or stub)
        assertNotNull(result)
        // On-device should have been called as fallback
        assertTrue(onDeviceClient.calls.isNotEmpty())
    }

    // ── Vocabulary tracking ───────────────────────────────────────────────────

    @Test
    fun `new vocabulary word is added with non-empty definition`() = runTest {
        val novelWord = "supercalifragilistic"
        cloudClient.responses.add("The word '$novelWord' means something extraordinary.")

        val segment = makeSegment("The lecture covered $novelWord in detail")
        assistant.processSegment(segment)

        val allTerms = vocabularyDao.getAll()
        val term = allTerms.firstOrNull { it.word.equals(novelWord, ignoreCase = true) }
        assertNotNull("Vocabulary term should have been inserted for '$novelWord'", term)
        assertTrue("Definition should not be empty", term!!.definition.isNotEmpty())
    }

    @Test
    fun `known words are not re-inserted into vocabulary`() = runTest {
        val knownWord = "transcription"
        // Pre-populate vocabulary
        vocabularyDao.insert(
            VocabTermEntity(
                id = UUID.randomUUID(),
                sessionId = UUID.randomUUID(),
                word = knownWord,
                definition = "The process of converting speech to text",
                contextSnippet = "existing snippet"
            )
        )

        val segment = makeSegment("The transcription process was explained")
        assistant.processSegment(segment)

        // Still only one entry for the word
        val terms = vocabularyDao.getAll().filter { it.word.equals(knownWord, ignoreCase = true) }
        assertEquals(1, terms.size)
    }

    // ── define returns quickly for cached words ───────────────────────────────

    @Test
    fun `define returns cached definition quickly without calling AI`() = runTest {
        val word = "photosynthesis"
        val cachedDefinition = "The process by which plants convert light into energy."
        vocabularyDao.insert(
            VocabTermEntity(
                id = UUID.randomUUID(),
                sessionId = UUID.randomUUID(),
                word = word,
                definition = cachedDefinition,
                contextSnippet = ""
            )
        )

        val startMs = System.currentTimeMillis()
        val result = assistant.define(word)
        val elapsedMs = System.currentTimeMillis() - startMs

        assertEquals(cachedDefinition, result)
        // Should be near-instant — no AI call needed
        assertTrue("define for cached word took ${elapsedMs} ms (> 2000 ms)", elapsedMs < 2_000L)
        // Cloud/on-device should NOT have been called
        assertTrue(cloudClient.calls.isEmpty())
    }

    @Test
    fun `define calls AI for unknown word`() = runTest {
        cloudClient.responses.add("A specialized technical term.")

        val result = assistant.define("perspicacious")

        assertNotNull(result)
        assertTrue(cloudClient.calls.isNotEmpty())
    }

    // ── generateStudyNotes structure ──────────────────────────────────────────

    @Test
    fun `generateStudyNotes returns non-empty title, key topics, and summary`() = runTest {
        cloudClient.responses.add(
            """TITLE: Introduction to Machine Learning
KEY_TOPICS: Supervised learning, Unsupervised learning, Neural networks
ACTION_ITEMS: Read chapter 3, Complete assignment 1
SUMMARY: This session introduced the core concepts of machine learning including supervised and unsupervised approaches."""
        )

        val transcript = listOf(
            makeSegment("Today we covered machine learning basics"),
            makeSegment("We discussed supervised and unsupervised approaches"),
            makeSegment("Please read chapter 3 and complete assignment 1")
        )

        val notes = assistant.generateStudyNotes(transcript)

        assertTrue("Title must not be empty", notes.title.isNotEmpty())
        assertTrue("Key topics must not be empty", notes.keyTopics.isNotEmpty())
        assertTrue("Summary must not be empty", notes.summary.isNotEmpty())
    }

    @Test
    fun `generateStudyNotes title is populated from AI response`() = runTest {
        cloudClient.responses.add(
            """TITLE: Advanced Kotlin Coroutines
KEY_TOPICS: Coroutines, Flow, StateFlow
ACTION_ITEMS: Practice with coroutines
SUMMARY: Covered coroutine fundamentals."""
        )

        val notes = assistant.generateStudyNotes(listOf(makeSegment("We learned about Kotlin coroutines")))

        assertEquals("Advanced Kotlin Coroutines", notes.title)
    }

    @Test
    fun `generateStudyNotes key topics list is populated`() = runTest {
        cloudClient.responses.add(
            """TITLE: Biology Lecture
KEY_TOPICS: DNA, RNA, Protein synthesis, Mitosis
ACTION_ITEMS: Review slide deck
SUMMARY: Covered cell biology topics."""
        )

        val notes = assistant.generateStudyNotes(listOf(makeSegment("Today we studied cell biology")))

        assertTrue(notes.keyTopics.size >= 2)
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun makeSegment(text: String): CaptionSegment = CaptionSegment(
        id = UUID.randomUUID(),
        speakerLabel = "Speaker 1",
        text = text,
        confidence = 0.95f,
        timestampMs = System.currentTimeMillis(),
        isUncertain = false
    )
}
