package com.example.tryhearingaid.emergency

import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the SOS gets the user's position. [SosLocationProvider] is the Android implementation. */
interface SosLocationSource {
    /** A fresh fix, or null if none could be obtained in time. */
    suspend fun current(): SosLocation?

    /** The newest stored fix, or null if there is none. */
    fun lastKnown(): SosLocation?
}

/**
 * Runs the whole SOS: check contacts, find a location, build the text, send it
 * (Requirement 10.3, 10.4).
 *
 * The order matters. Contacts are checked first, so with no contacts the user is told straight
 * away instead of waiting for a GPS fix. A fresh fix is preferred; the last known one is used
 * only when no fresh fix is available, and [SosMessageBuilder] then adds the "may be outdated"
 * note.
 *
 * @param contacts   Supplies the saved numbers; read each time so edits take effect at once.
 * @param dispatcher Where the blocking SMS sends run.
 */
class SosController(
    private val contacts: suspend () -> List<String>,
    private val location: SosLocationSource,
    private val coordinator: SosCoordinator,
    private val baseText: String = SosMessageBuilder.DEFAULT_TEXT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    /** Send the SOS. Returns what happened; throws only if the calling coroutine is cancelled. */
    suspend fun trigger(): SosResult {
        val numbers = SosContacts.normalize(contacts())
        if (numbers.isEmpty()) return SosResult.NoContacts

        val current = location.current()
        val lastKnown = if (current == null) location.lastKnown() else null
        val message = SosMessageBuilder.build(current, lastKnown, baseText, zone)

        return withContext(dispatcher) { coordinator.trigger(numbers, message) }
    }
}
