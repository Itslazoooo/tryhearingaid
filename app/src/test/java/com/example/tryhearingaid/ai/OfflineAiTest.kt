package com.example.tryhearingaid.ai

import com.example.tryhearingaid.data.CaptionSegment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAiTest {

    // ── ChatMl ────────────────────────────────────────────────────────────────

    @Test
    fun `format wraps the request in the chat markers`() {
        val text = ChatMl.format("Hello", system = "Be brief.")
        assertEquals(
            "<|im_start|>system\nBe brief.<|im_end|>\n<|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\n",
            text
        )
    }

    @Test
    fun `clean removes markers and trims`() {
        assertEquals("Short answer.", ChatMl.clean("  Short answer.<|im_end|>\n<|im_start|>user junk"))
        assertEquals("Text", ChatMl.clean("Text<|endoftext|>"))
    }

    // ── TranscriptChunker ─────────────────────────────────────────────────────

    @Test
    fun `short text is one chunk and empty text none`() {
        assertEquals(listOf("Hello there."), TranscriptChunker.chunk("Hello there.", 100))
        assertTrue(TranscriptChunker.chunk("   ", 100).isEmpty())
    }

    @Test
    fun `chunks never exceed the limit and keep every sentence`() {
        val sentences = (1..40).map { "This is sentence number $it." }
        val chunks = TranscriptChunker.chunk(sentences.joinToString(" "), 120)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 120 })
        val rejoined = chunks.joinToString(" ")
        sentences.forEach { assertTrue(rejoined.contains(it)) }
    }

    @Test
    fun `a sentence longer than the limit is split by length`() {
        val chunks = TranscriptChunker.chunk("x".repeat(250), 100)
        assertEquals(listOf(100, 100, 50), chunks.map { it.length })
    }

    // ── Catalog ───────────────────────────────────────────────────────────────

    @Test
    fun `the model download is a secure task file`() {
        val e = LlmCatalog.DEFAULT
        assertTrue(e.url.startsWith("https://"))
        assertTrue(e.url.endsWith(".task"))
        assertTrue(e.minBytes > 0 && e.minBytes <= e.approxMb * 1_000_000L)
    }

    // ── Assistant with long text and readiness ────────────────────────────────

    private class RecordingClient : GeminiClient {
        val prompts = mutableListOf<String>()
        override suspend fun generate(prompt: String): String {
            prompts += prompt
            return "short"
        }
    }

    private fun segment(text: String) = CaptionSegment(
        speakerLabel = "Speaker 1", text = text, confidence = 0.9f,
        timestampMs = 0L, isUncertain = false
    )

    private fun assistant(client: GeminiClient, maxChars: Int, ready: Boolean = true) = AIAssistantImpl(
        cloudClient = client,
        onDeviceClient = client,
        vocabularyDao = InMemoryVocabularyDao(),
        networkCallback = FakeNetworkCallback(),
        maxPromptChars = maxChars,
        onDeviceReady = { ready }
    )

    @Test
    fun `short transcript is summarised in one request`() = runTest {
        val client = RecordingClient()
        assistant(client, 500).summarize(listOf(segment("A short talk.")))
        assertEquals(1, client.prompts.size)
    }

    @Test
    fun `long transcript is condensed in pieces first and no request is too long`() = runTest {
        val client = RecordingClient()
        val long = (1..60).map { segment("This is a fairly long sentence number $it about the topic.") }
        assistant(client, 300).summarize(long)
        assertTrue(client.prompts.size > 2)
        // Each request holds at most the allowed transcript text plus the short instruction.
        assertTrue(client.prompts.all { it.length <= 300 + 100 })
    }

    @Test
    fun `readiness follows the model`() {
        assertFalse(assistant(RecordingClient(), 500, ready = false).isOnDeviceReady())
        assertTrue(assistant(RecordingClient(), 500, ready = true).isOnDeviceReady())
    }

    @Test
    fun `study notes labels with bullets and bold are still understood`() = runTest {
        val client = object : GeminiClient {
            override suspend fun generate(prompt: String) =
                "**TITLE:** Photosynthesis\n- KEY_TOPICS: light, chlorophyll\nSummary: Plants make sugar."
        }
        val notes = assistant(client, 2000).generateStudyNotes(listOf(segment("Plants use light.")))
        assertEquals("Photosynthesis", notes.title)
        assertEquals(listOf("light", "chlorophyll"), notes.keyTopics)
        assertEquals("Plants make sugar.", notes.summary)
    }
}
