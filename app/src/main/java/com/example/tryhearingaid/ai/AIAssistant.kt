package com.example.tryhearingaid.ai

import com.example.tryhearingaid.data.CaptionSegment
import com.example.tryhearingaid.data.ImportantPoint
import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.data.StudyNotes
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * AI assistant that wraps on-device (Gemini Nano) and cloud (Gemini API) inference.
 *
 * - [isOnlineMode] reflects current network + cloud-AI availability.
 * - [importantPoints] emits highlighted points detected in transcript segments
 *   (dates, deadlines, action items, proper nouns).
 */
interface AIAssistant {
    /** True when cloud AI is available; false when offline / cloud unavailable. */
    val isOnlineMode: StateFlow<Boolean>

    /** Emits important points extracted from transcript segments in real time. */
    val importantPoints: SharedFlow<ImportantPoint>

    /**
     * Produce a concise summary of [transcript].
     * 5-second timeout; cloud 5xx retries once after 2 s then falls back to on-device.
     */
    suspend fun summarize(transcript: List<CaptionSegment>): String

    /**
     * Translate [text] into [targetLanguage].
     * 5-second timeout; cloud 5xx retries once after 2 s then falls back to on-device.
     */
    suspend fun translate(text: String, targetLanguage: String): String

    /**
     * Return a plain-language definition of [word].
     * 5-second timeout; returns cached definition immediately if already known.
     */
    suspend fun define(word: String): String

    /**
     * Generate structured study notes from [transcript].
     * 10-second timeout; cloud 5xx retries once after 2 s then falls back to on-device.
     */
    suspend fun generateStudyNotes(transcript: List<CaptionSegment>): StudyNotes

    /**
     * Return a natural-language description for a detected [category] event.
     * 5-second timeout; cloud 5xx retries once after 2 s then falls back to on-device.
     */
    suspend fun describeSound(category: SoundCategory, context: String): String
}
