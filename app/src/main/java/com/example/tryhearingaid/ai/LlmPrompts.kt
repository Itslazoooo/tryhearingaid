package com.example.tryhearingaid.ai

/** Wraps a request in the chat format the offline model was trained on, and tidies its answer. */
object ChatMl {
    const val SYSTEM_PROMPT =
        "You are a careful assistant that writes short, clear, accurate notes in plain language. " +
            "Use only what the text says. Do not invent facts."

    fun format(userPrompt: String, system: String = SYSTEM_PROMPT): String =
        "<|im_start|>system\n$system<|im_end|>\n" +
            "<|im_start|>user\n$userPrompt<|im_end|>\n" +
            "<|im_start|>assistant\n"

    /** Removes chat markers the model may leave in its answer. */
    fun clean(raw: String): String =
        raw.substringBefore("<|im_end|>")
            .replace("<|im_start|>", "")
            .replace("<|endoftext|>", "")
            .trim()
}

/** Splits long text into pieces the small offline model can read in one go. */
object TranscriptChunker {

    private val sentenceBreak = Regex("(?<=[.!?])\\s+|\\n+")

    /** Pieces of at most [maxChars] characters, split at sentence ends where possible. */
    fun chunk(text: String, maxChars: Int): List<String> {
        require(maxChars > 0) { "maxChars must be positive" }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= maxChars) return listOf(trimmed)

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                chunks += current.toString().trim()
                current.clear()
            }
        }
        for (sentence in trimmed.split(sentenceBreak).filter { it.isNotBlank() }) {
            if (sentence.length > maxChars) {
                flush()
                sentence.chunked(maxChars).forEach { chunks += it.trim() }
                continue
            }
            if (current.length + sentence.length + 1 > maxChars) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        flush()
        return chunks.filter { it.isNotEmpty() }
    }
}
