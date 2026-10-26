package com.example.tryhearingaid.emergency

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A GPS fix: coordinates plus when it was taken. */
data class SosLocation(
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long
)

/**
 * Builds the emergency SMS text (Requirement 10.3, 10.4).
 *
 * Pure Kotlin so it can be unit tested on the JVM.
 */
object SosMessageBuilder {

    const val DEFAULT_TEXT = "EMERGENCY: I need help. Please contact me or call emergency services."

    /**
     * @param current   A fresh location fix, or null if GPS is unavailable.
     * @param lastKnown The last stored fix, used only when [current] is null.
     * @param zone      Zone for the "as of" time in the stale-location note.
     */
    fun build(
        current: SosLocation?,
        lastKnown: SosLocation?,
        baseText: String = DEFAULT_TEXT,
        zone: ZoneId = ZoneId.systemDefault()
    ): String = when {
        current != null -> "$baseText Location: ${coordinates(current)}"
        lastKnown != null -> {
            val asOf = TIME_FORMAT.withZone(zone).format(Instant.ofEpochMilli(lastKnown.timestampMs))
            "$baseText Last known location (as of $asOf, may be outdated): ${coordinates(lastKnown)}"
        }
        else -> "$baseText Location unavailable."
    }

    private fun coordinates(location: SosLocation): String {
        val lat = String.format(Locale.US, "%.6f", location.latitude)
        val lon = String.format(Locale.US, "%.6f", location.longitude)
        return "$lat,$lon https://maps.google.com/?q=$lat,$lon"
    }

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
}

/** Sends one SMS. Returns true on success, false on failure. May throw; callers treat a throw as failure. */
fun interface SmsSender {
    fun send(phoneNumber: String, message: String): Boolean
}

/** Outcome of an SOS attempt. */
sealed class SosResult {
    /** No usable contacts were configured; nothing was sent (Design: Emergency SOS errors). */
    object NoContacts : SosResult()

    /** [sentTo] received the message; [failed] did not, after all retries. */
    data class Completed(val sentTo: List<String>, val failed: List<String>) : SosResult() {
        /** True when every contact failed, so the screen should offer manual dialling. */
        val allFailed: Boolean get() = sentTo.isEmpty()
    }
}

/**
 * Sends the SOS message to up to [maxContacts] contacts, retrying each failed send up
 * to [maxRetries] times (Requirement 10.3, Design: Emergency SOS errors).
 */
class SosCoordinator(
    private val sms: SmsSender,
    private val maxContacts: Int = 5,
    private val maxRetries: Int = 3
) {
    /** Blank and duplicate numbers are ignored; only the first [maxContacts] are used. */
    fun trigger(contacts: List<String>, message: String): SosResult {
        val numbers = contacts.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(maxContacts)
        if (numbers.isEmpty()) return SosResult.NoContacts

        val sent = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (number in numbers) {
            if (sendWithRetry(number, message)) sent += number else failed += number
        }
        return SosResult.Completed(sentTo = sent, failed = failed)
    }

    private fun sendWithRetry(number: String, message: String): Boolean {
        repeat(1 + maxRetries) {
            val ok = try {
                sms.send(number, message)
            } catch (e: Exception) {
                false
            }
            if (ok) return true
        }
        return false
    }
}
