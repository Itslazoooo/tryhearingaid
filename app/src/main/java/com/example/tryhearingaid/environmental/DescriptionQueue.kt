package com.example.tryhearingaid.environmental

import com.example.tryhearingaid.data.SoundCategory
import kotlin.math.abs

/**
 * One on-screen card describing a sound event (Requirement 15.2).
 *
 * @property id               Stable id for Compose list keys.
 * @property category         The detected sound category.
 * @property text             Natural-language description shown to the user.
 * @property firstTimestampMs When the first merged event happened.
 * @property lastTimestampMs  When the most recent merged event happened.
 * @property count            Number of events merged into this card (1 = not merged).
 */
data class DescriptionCard(
    val id: Long,
    val category: SoundCategory,
    val text: String,
    val firstTimestampMs: Long,
    val lastTimestampMs: Long,
    val count: Int = 1
)

/**
 * Bounded queue of sound-description cards (Requirement 15.3, 15.5).
 *
 * - Holds at most [maxDepth] cards. Adding to a full queue discards the oldest card
 *   (Property 29).
 * - Two events of the same category whose timestamps are at most [mergeWindowMs]
 *   apart share one card instead of creating a duplicate (Property 30).
 * - Cards older than [displayMs] are removed by [expire] (Requirement 15.2).
 *
 * Pure Kotlin with no Android dependencies. Not thread-safe: call from one coroutine
 * or guard externally.
 */
class DescriptionQueue(
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    val mergeWindowMs: Long = DEFAULT_MERGE_WINDOW_MS,
    val displayMs: Long = DEFAULT_DISPLAY_MS
) {
    init {
        require(maxDepth >= 1) { "maxDepth must be at least 1" }
        require(mergeWindowMs >= 0) { "mergeWindowMs must not be negative" }
        require(displayMs >= 0) { "displayMs must not be negative" }
    }

    private val cards = ArrayDeque<DescriptionCard>()
    private var nextId = 0L

    /** Current cards, oldest first. */
    val items: List<DescriptionCard> get() = cards.toList()

    /**
     * Add a described event. Merges into an existing same-category card when the
     * timestamps are within [mergeWindowMs]; otherwise appends a new card and drops
     * the oldest one if the queue would exceed [maxDepth].
     *
     * @return the queue contents after the change, oldest first.
     */
    fun add(category: SoundCategory, text: String, timestampMs: Long): List<DescriptionCard> {
        // Search newest-first so the most recent matching card absorbs the event.
        val mergeIndex = cards.indexOfLast {
            it.category == category && abs(timestampMs - it.lastTimestampMs) <= mergeWindowMs
        }
        if (mergeIndex >= 0) {
            val old = cards[mergeIndex]
            cards[mergeIndex] = old.copy(
                text = text,
                firstTimestampMs = minOf(old.firstTimestampMs, timestampMs),
                lastTimestampMs = maxOf(old.lastTimestampMs, timestampMs),
                count = old.count + 1
            )
        } else {
            cards.addLast(
                DescriptionCard(
                    id = nextId++,
                    category = category,
                    text = text,
                    firstTimestampMs = timestampMs,
                    lastTimestampMs = timestampMs
                )
            )
            while (cards.size > maxDepth) cards.removeFirst()
        }
        return items
    }

    /**
     * Remove cards that have been on screen for at least [displayMs].
     *
     * @return the queue contents after removal, oldest first.
     */
    fun expire(nowMs: Long): List<DescriptionCard> {
        cards.removeAll { nowMs - it.lastTimestampMs >= displayMs }
        return items
    }

    /** Remove every card. */
    fun clear() = cards.clear()

    companion object {
        const val DEFAULT_MAX_DEPTH = 5
        const val DEFAULT_MERGE_WINDOW_MS = 3_000L
        const val DEFAULT_DISPLAY_MS = 5_000L
    }
}
