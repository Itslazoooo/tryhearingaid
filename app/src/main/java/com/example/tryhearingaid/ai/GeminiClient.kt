package com.example.tryhearingaid.ai

/**
 * Abstraction over the actual Gemini inference backend so that
 * [AIAssistantImpl] can be tested without the real SDK.
 */
interface GeminiClient {
    /**
     * Send [prompt] to this backend and return the response text.
     *
     * @throws GeminiServerException when the cloud backend returns a 5xx error.
     * @throws GeminiUnavailableException when this client cannot service the request
     *         (e.g. Nano model not loaded, network unavailable for cloud client).
     */
    suspend fun generate(prompt: String): String
}

/** Thrown when the Gemini cloud API responds with a 5xx error. */
class GeminiServerException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Thrown when the client itself is unavailable (model not loaded, offline, etc.). */
class GeminiUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

// ─── Fake implementation used in tests ───────────────────────────────────────

/**
 * Deterministic fake that returns predictable responses based on the prompt content.
 * Used in unit tests and as a stand-in when no real SDK is available.
 *
 * @param simulateServerError When true the next [generate] call throws [GeminiServerException].
 * @param simulateUnavailable When true every [generate] call throws [GeminiUnavailableException].
 */
class FakeGeminiClient(
    var simulateServerError: Boolean = false,
    var simulateUnavailable: Boolean = false
) : GeminiClient {

    /** Ordered list of responses to return sequentially; cycles back to last when exhausted. */
    val responses = mutableListOf<String>()
    private var callIndex = 0
    val calls = mutableListOf<String>()

    override suspend fun generate(prompt: String): String {
        calls.add(prompt)

        if (simulateUnavailable) throw GeminiUnavailableException("Client unavailable")
        if (simulateServerError) {
            simulateServerError = false // reset after one use
            throw GeminiServerException("HTTP 500")
        }

        return when {
            responses.isNotEmpty() -> {
                val idx = callIndex.coerceAtMost(responses.lastIndex)
                callIndex++
                responses[idx]
            }
            prompt.contains("summarize", ignoreCase = true) ||
                    prompt.contains("summary", ignoreCase = true) ->
                "This is a summary of the conversation."
            prompt.contains("translate", ignoreCase = true) ->
                "Translated text."
            prompt.contains("define", ignoreCase = true) ||
                    prompt.contains("definition", ignoreCase = true) ->
                "A clear plain-language definition of the word."
            prompt.contains("study notes", ignoreCase = true) ||
                    prompt.contains("StudyNotes", ignoreCase = true) ->
                """TITLE: Lecture Notes
KEY_TOPICS: Topic 1, Topic 2, Topic 3
ACTION_ITEMS: Review chapter 5, Submit assignment
SUMMARY: This lecture covered the main concepts."""
            prompt.contains("describe", ignoreCase = true) ||
                    prompt.contains("sound", ignoreCase = true) ->
                "A sound was detected in the environment."
            else -> "Response for: ${prompt.take(40)}"
        }
    }
}
