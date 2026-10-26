package com.example.tryhearingaid.environmental

import com.example.tryhearingaid.ai.AIAssistant
import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.data.SoundDetection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Produces the plain-language description shown for each detected sound
 * (Requirement 15.1, Property 28).
 *
 * Asks [AIAssistant.describeSound] first. The result is always usable:
 * - If the AI call fails or returns blank text, a built-in fallback sentence is used.
 * - If the AI text does not mention the sound category, the category label is appended
 *   so the description always references what was detected.
 */
class SoundNarrator(private val assistant: AIAssistant) {

    /** Describe [detection]. Never returns blank text; throws only if the calling coroutine is cancelled. */
    suspend fun describe(detection: SoundDetection): String {
        val label = categoryLabel(detection.category)
        val context = "confidence ${(detection.confidence * 100).toInt()} percent"
        val raw = try {
            assistant.describeSound(detection.category, context).trim()
        } catch (e: Exception) {
            // A timeout inside describeSound is also a CancellationException, but it must
            // fall back rather than propagate. Rethrow only if *this* coroutine was cancelled.
            currentCoroutineContext().ensureActive()
            ""
        }
        return when {
            raw.isEmpty() -> fallback(label)
            raw.contains(label, ignoreCase = true) -> raw
            else -> "$raw ($label)"
        }
    }

    companion object {
        /** Lower-case, space-separated label, e.g. `BABY_CRYING` becomes "baby crying". */
        fun categoryLabel(category: SoundCategory): String =
            category.name.lowercase().replace('_', ' ')

        internal fun fallback(label: String): String = "A $label was detected nearby"
    }
}
