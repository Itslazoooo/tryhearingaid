package com.example.tryhearingaid.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [AudioRoutePolicy] (Requirement 14.2).
 */
class AudioRoutePolicyTest {

    @Test
    fun `no A2DP device routes to the default output`() {
        assertEquals(AudioDevice.Default, AudioRoutePolicy.choose(emptySet()))
    }

    @Test
    fun `a connected A2DP device is chosen`() {
        assertEquals(
            AudioDevice.Bluetooth("AA:01"),
            AudioRoutePolicy.choose(setOf("AA:01"))
        )
    }

    @Test
    fun `with several A2DP devices the choice is deterministic`() {
        val expected = AudioDevice.Bluetooth("AA:01")

        assertEquals(expected, AudioRoutePolicy.choose(setOf("BB:02", "AA:01")))
        assertEquals(expected, AudioRoutePolicy.choose(setOf("AA:01", "BB:02")))
    }

    @Test
    fun `disconnecting the only A2DP device falls back to the default output`() {
        val connected = AudioRoutePolicy.choose(setOf("AA:01"))
        val afterDisconnect = AudioRoutePolicy.choose(emptySet())

        assertEquals(AudioDevice.Bluetooth("AA:01"), connected)
        assertEquals(AudioDevice.Default, afterDisconnect)
    }
}
