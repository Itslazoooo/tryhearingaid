package com.example.tryhearingaid.captions

/**
 * The small offline speech models published by the Vosk project (https://alphacephei.com/vosk/models).
 * Filipino and Arabic have no small model, so they keep using the phone's built-in recognizer.
 */
object VoskCatalog {

    data class Entry(val language: String, val folder: String, val approxMb: Int) {
        val url: String get() = "https://alphacephei.com/vosk/models/$folder.zip"
    }

    val entries: List<Entry> = listOf(
        Entry("en", "vosk-model-small-en-us-0.15", 40),
        Entry("es", "vosk-model-small-es-0.42", 39),
        Entry("fr", "vosk-model-small-fr-0.22", 41),
        Entry("de", "vosk-model-small-de-0.15", 45),
        Entry("zh", "vosk-model-small-cn-0.22", 42),
        Entry("ja", "vosk-model-small-ja-0.22", 48),
        Entry("ko", "vosk-model-small-ko-0.22", 82),
        Entry("pt", "vosk-model-small-pt-0.3", 31),
        Entry("hi", "vosk-model-small-hi-0.22", 42)
    )

    /** The model for a language code such as "en", "en-US" or "pt_BR", or null if there is none. */
    fun forLanguage(code: String): Entry? {
        val base = code.substringBefore('-').substringBefore('_').lowercase()
        return entries.firstOrNull { it.language == base }
    }
}
