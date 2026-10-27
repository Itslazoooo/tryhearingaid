package com.example.tryhearingaid.environmental

import com.example.tryhearingaid.data.SoundDetection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Environmental awareness mode: turns sound detections into on-screen description cards
 * (Requirements 15.1 to 15.5).
 *
 * For each detection it asks [narrator] for a description, adds it to a [DescriptionQueue]
 * (depth 5, same-category merge within 3 s) and publishes the result as [cards]. A timer
 * removes cards after the queue's display time (5 s). When a *new* card is created (not a
 * merge into an existing one) [speak] is called with its text so a text-to-speech engine
 * can read it aloud (Requirement 15.4).
 *
 * Detections are described concurrently, so a slow AI answer for one sound does not delay
 * the cards for the next. Merging uses event timestamps, so the order in which the
 * descriptions finish does not matter.
 *
 * @param scope   Scope that owns the collector and the expiry timer; cancelling it stops everything.
 * @param detections Flow of classified sounds, normally `SoundClassifier.detections`.
 * @param clock   Current time in ms, comparable with [SoundDetection.timestampMs]. Injectable for tests.
 * @param tickMs  How often expired cards are removed.
 * @param speak   Called once per new card. Pass a function that checks the user's text-to-speech
 *                setting, so turning the setting off silences it without restarting the controller.
 */
class NarrationController(
    private val scope: CoroutineScope,
    private val detections: Flow<SoundDetection>,
    private val narrator: SoundNarrator,
    private val queue: DescriptionQueue = DescriptionQueue(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val tickMs: Long = DEFAULT_TICK_MS,
    private val speak: (String) -> Unit = {}
) {
    private val _cards = MutableStateFlow<List<DescriptionCard>>(emptyList())

    /** Cards currently on screen, oldest first. */
    val cards: StateFlow<List<DescriptionCard>> = _cards.asStateFlow()

    private var job: Job? = null

    /** Start narrating. Calling it while already running does nothing. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { expireLoop() }
            coroutineScope {
                detections.collect { detection -> launch { handle(detection) } }
            }
        }
    }

    /** Stop narrating and clear every card. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        synchronized(queue) {
            queue.clear()
            _cards.value = emptyList()
        }
    }

    private suspend fun handle(detection: SoundDetection) {
        // Slow (may call the AI), so it runs outside the lock.
        val text = narrator.describe(detection)
        val isNewCard = synchronized(queue) {
            val before = queue.items.mapTo(HashSet()) { it.id }
            val after = queue.add(detection.category, text, detection.timestampMs)
            _cards.value = after
            after.any { it.id !in before }
        }
        if (isNewCard) runCatching { speak(text) }
    }

    private suspend fun expireLoop() {
        while (true) {
            delay(tickMs)
            synchronized(queue) {
                if (queue.items.isNotEmpty()) _cards.value = queue.expire(clock())
            }
        }
    }

    companion object {
        const val DEFAULT_TICK_MS = 250L
    }
}
