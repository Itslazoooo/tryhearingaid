package com.example.tryhearingaid.wearable

import com.example.tryhearingaid.data.CaptionSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CaptionForwarder] (Requirement 14.6).
 */
class CaptionForwarderTest {

    private fun segment(text: String) = CaptionSegment(
        speakerLabel = "Speaker 1",
        text = text,
        confidence = 0.9f,
        timestampMs = 0L,
        isUncertain = false
    )

    @Test
    fun `an empty list forwards nothing`() {
        assertTrue(CaptionForwarder().newSegments(emptyList()).isEmpty())
    }

    @Test
    fun `a segment is forwarded once even if the same list is emitted again`() {
        val forwarder = CaptionForwarder()
        val a = segment("one")

        assertEquals(listOf(a), forwarder.newSegments(listOf(a)))
        assertTrue(forwarder.newSegments(listOf(a)).isEmpty())
    }

    @Test
    fun `only newly appended segments are forwarded and in order`() {
        val forwarder = CaptionForwarder()
        val a = segment("one")
        val b = segment("two")
        val c = segment("three")
        val d = segment("four")

        forwarder.newSegments(listOf(a))

        assertEquals(listOf(b), forwarder.newSegments(listOf(a, b)))
        assertEquals(listOf(c, d), forwarder.newSegments(listOf(a, b, c, d)))
    }

    @Test
    fun `segments after the engine clears its list for a new session are forwarded`() {
        val forwarder = CaptionForwarder()
        val old = segment("old session")
        val fresh = segment("new session")
        forwarder.newSegments(listOf(old))

        assertTrue(forwarder.newSegments(emptyList()).isEmpty())
        assertEquals(listOf(fresh), forwarder.newSegments(listOf(fresh)))
    }

    @Test
    fun `a long session never resends old segments`() {
        val forwarder = CaptionForwarder()
        val many = (1..1000).map { segment("s$it") }
        val extra = segment("s1001")

        assertEquals(1000, forwarder.newSegments(many).size)
        assertEquals(listOf(extra), forwarder.newSegments(many + extra))
    }
}
