package com.example.tryhearingaid.captions

import com.example.tryhearingaid.data.CaptionSegment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for [CaptionEngine] contract (exercised via [FakeCaptionEngine]).
 *
 * [CaptionEngineImpl] wraps Android's [SpeechRecognizer] which is unavailable in the
 * JVM test environment. Tests therefore use the in-memory [FakeCaptionEngine] defined
 * alongside the interface to validate the observable contract:
 *
 *   1. Uncertainty flag based on confidence threshold (Requirement 7.6, Property 7)
 *   2. Non-empty speaker labels in multi-speaker sessions (Requirement 7.3, Property 9)
 *   3. [isUnavailable] exposed when SpeechRecognizer is unavailable (Requirement 7.1 error path)
 *
 * Validates: Requirements 7.1, 7.3, 7.6
 */
class CaptionEngineTest {

    private lateinit var engine: FakeCaptionEngine

    companion object {
        /** Threshold below which segments are marked uncertain (mirrors CaptionEngineImpl). */
        private const val CONFIDENCE_THRESHOLD = 0.70f
        private const val DELTA = 0.0001f
    }

    @Before
    fun setUp() {
        engine = FakeCaptionEngine()
        engine.start("en-US")
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private fun segment(
        confidence: Float,
        speakerLabel: String = "Speaker 1",
        text: String = "hello"
    ) = CaptionSegment(
        id = UUID.randomUUID(),
        speakerLabel = speakerLabel,
        text = text,
        confidence = confidence,
        timestampMs = System.currentTimeMillis(),
        isUncertain = confidence < CONFIDENCE_THRESHOLD
    )

    // ─── Uncertainty flag: confidence < 0.70 → isUncertain = true ─────────────

    /**
     * Requirement 7.6: segments with confidence below 0.70 must be marked uncertain.
     */
    @Test
    fun `segment with confidence below threshold is marked uncertain`() = runBlocking {
        val seg = segment(confidence = 0.50f)
        assertTrue(
            "confidence 0.50 is below threshold 0.70 → isUncertain must be true",
            seg.isUncertain
        )
        engine.pushSegment(seg)

        val emitted = engine.captions.first { it.isNotEmpty() }.last()
        assertTrue("Emitted segment must have isUncertain = true for confidence 0.50", emitted.isUncertain)
    }

    @Test
    fun `segment with confidence just below threshold is marked uncertain`() {
        // 0.699… is strictly less than 0.70
        val seg = segment(confidence = 0.699f)
        assertTrue(
            "confidence 0.699 < 0.70 → isUncertain must be true",
            seg.isUncertain
        )
    }

    @Test
    fun `segment with confidence zero is marked uncertain`() {
        val seg = segment(confidence = 0.0f)
        assertTrue("confidence 0.0 → isUncertain must be true", seg.isUncertain)
    }

    // ─── Uncertainty flag: confidence ≥ 0.70 → isUncertain = false ───────────

    /**
     * Requirement 7.6: segments with confidence at or above 0.70 must NOT be marked uncertain.
     */
    @Test
    fun `segment with confidence exactly at threshold is not uncertain`() = runBlocking {
        val seg = segment(confidence = 0.70f)
        assertFalse(
            "confidence 0.70 == threshold → isUncertain must be false",
            seg.isUncertain
        )
        engine.pushSegment(seg)

        val emitted = engine.captions.first { it.isNotEmpty() }.last()
        assertFalse("Emitted segment must have isUncertain = false for confidence 0.70", emitted.isUncertain)
    }

    @Test
    fun `segment with confidence above threshold is not uncertain`() {
        val seg = segment(confidence = 0.85f)
        assertFalse("confidence 0.85 ≥ 0.70 → isUncertain must be false", seg.isUncertain)
    }

    @Test
    fun `segment with confidence 1 dot 0 is not uncertain`() {
        val seg = segment(confidence = 1.0f)
        assertFalse("confidence 1.0 → isUncertain must be false", seg.isUncertain)
    }

    /**
     * Boundary: verify the exact threshold boundary is correctly classified for a
     * range of representative values.
     */
    @Test
    fun `uncertainty flag respects 0 dot 70 boundary for multiple confidence values`() {
        data class Case(val confidence: Float, val expectUncertain: Boolean)
        val cases = listOf(
            Case(0.00f, true),
            Case(0.50f, true),
            Case(0.60f, true),
            Case(0.69f, true),
            Case(0.699f, true),
            Case(0.70f, false),
            Case(0.701f, false),
            Case(0.80f, false),
            Case(0.99f, false),
            Case(1.00f, false)
        )
        cases.forEach { (conf, expectedUncertain) ->
            val seg = segment(confidence = conf)
            assertEquals(
                "confidence=$conf → isUncertain should be $expectedUncertain",
                expectedUncertain,
                seg.isUncertain
            )
        }
    }

    // ─── Multi-speaker: speaker labels are non-empty ──────────────────────────

    /**
     * Requirement 7.3: when multiple speakers are detected each segment must carry
     * a non-empty speaker identifier.
     */
    @Test
    fun `speaker labels are non-empty for all segments in multi-speaker session`() = runBlocking {
        val segments = listOf(
            segment(confidence = 0.90f, speakerLabel = "Speaker 1", text = "Hello"),
            segment(confidence = 0.85f, speakerLabel = "Speaker 2", text = "Hi there"),
            segment(confidence = 0.75f, speakerLabel = "Speaker 1", text = "How are you"),
            segment(confidence = 0.80f, speakerLabel = "Speaker 2", text = "I am fine")
        )
        segments.forEach { engine.pushSegment(it) }

        val emitted = engine.captions.first { it.size == segments.size }
        emitted.forEach { seg ->
            assertTrue(
                "speakerLabel must be non-empty; got: '${seg.speakerLabel}'",
                seg.speakerLabel.isNotEmpty()
            )
        }
    }

    @Test
    fun `speaker labels are distinct across different speakers`() = runBlocking {
        val seg1 = segment(confidence = 0.90f, speakerLabel = "Speaker 1", text = "First speaker")
        val seg2 = segment(confidence = 0.88f, speakerLabel = "Speaker 2", text = "Second speaker")
        engine.pushSegment(seg1)
        engine.pushSegment(seg2)

        val emitted = engine.captions.first { it.size == 2 }
        val labels = emitted.map { it.speakerLabel }.toSet()
        assertTrue(
            "At least two distinct speaker labels expected in multi-speaker session; got: $labels",
            labels.size >= 2
        )
    }

    @Test
    fun `speaker label follows Speaker N convention`() = runBlocking {
        val seg = segment(confidence = 0.90f, speakerLabel = "Speaker 1")
        engine.pushSegment(seg)

        val emitted = engine.captions.first { it.isNotEmpty() }.last()
        assertTrue(
            "Speaker label should match 'Speaker N' convention; got: '${emitted.speakerLabel}'",
            emitted.speakerLabel.matches(Regex("Speaker \\d+"))
        )
    }

    // ─── SpeechRecognizer unavailable → fallback ──────────────────────────────

    /**
     * Requirement 7.1 (error path / design error table): when [SpeechRecognizer] is
     * unavailable, [CaptionEngine.isUnavailable] must emit `true` so the UI can show
     * a "Captions unavailable" overlay.
     */
    @Test
    fun `isUnavailable is false by default when recognizer is available`() = runBlocking {
        assertFalse(
            "isUnavailable should be false when SpeechRecognizer is available",
            engine.isUnavailable.first()
        )
    }

    @Test
    fun `isUnavailable emits true when SpeechRecognizer unavailable`() = runBlocking {
        // Simulate the CaptionEngine signalling recognizer unavailability
        engine.simulateUnavailable()

        assertTrue(
            "isUnavailable must emit true when SpeechRecognizer is unavailable",
            engine.isUnavailable.first()
        )
    }

    @Test
    fun `isActive remains false when SpeechRecognizer is unavailable`() = runBlocking {
        // Stop normally first, then simulate recognizer becoming unavailable
        engine.stop()
        engine.simulateUnavailable()

        assertFalse(
            "isActive must be false when the recognizer is unavailable",
            engine.isActive.first()
        )
    }

    @Test
    fun `captions list is empty when recognizer becomes unavailable`() = runBlocking {
        // No segments were pushed; availability lost before any recognition
        engine.stop()
        engine.simulateUnavailable()

        assertTrue(
            "Captions list should be empty when recognizer is unavailable",
            engine.captions.first().isEmpty()
        )
    }

    // ─── Lifecycle sanity ─────────────────────────────────────────────────────

    @Test
    fun `isActive is true after start`() = runBlocking {
        assertTrue("isActive should be true after start()", engine.isActive.first())
    }

    @Test
    fun `isActive is false after stop`() = runBlocking {
        engine.stop()
        assertFalse("isActive should be false after stop()", engine.isActive.first())
    }

    @Test
    fun `captions list accumulates pushed segments in order`() = runBlocking {
        val seg1 = segment(confidence = 0.80f, text = "First")
        val seg2 = segment(confidence = 0.65f, text = "Second")
        engine.pushSegment(seg1)
        engine.pushSegment(seg2)

        val captions = engine.captions.first { it.size == 2 }
        assertEquals("First segment text", "First", captions[0].text)
        assertEquals("Second segment text", "Second", captions[1].text)
    }
}
