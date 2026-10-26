package com.example.tryhearingaid.wearable

import com.example.tryhearingaid.data.CaptionSegment
import java.util.UUID

/**
 * Turns the growing caption list emitted by the caption engine into a stream of
 * segments that have not been forwarded to wearables yet (Requirement 14.6).
 *
 * The caption engine publishes the *whole* list every time a segment is added and
 * resets it to empty when a new session starts, so forwarding the whole list on each
 * emission would resend old captions. This class remembers the ids of the last list
 * it saw and returns only segments whose id is not in it, in their original order.
 *
 * Memory use equals one UUID per segment of the current session, which is the same
 * order of size as the list the engine already holds.
 *
 * Not thread-safe; call from a single collector.
 */
class CaptionForwarder {

    private var seen: Set<UUID> = emptySet()

    /** Return the segments in [segments] that have not been returned before. */
    fun newSegments(segments: List<CaptionSegment>): List<CaptionSegment> {
        val fresh = segments.filter { it.id !in seen }
        seen = segments.mapTo(HashSet()) { it.id }
        return fresh
    }
}
