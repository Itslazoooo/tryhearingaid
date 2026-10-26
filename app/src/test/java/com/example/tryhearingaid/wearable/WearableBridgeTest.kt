package com.example.tryhearingaid.wearable

import android.bluetooth.BluetoothProfile
import com.example.tryhearingaid.data.AlertPriority
import com.example.tryhearingaid.data.BluetoothDeviceInfo
import com.example.tryhearingaid.data.CaptionSegment
import com.example.tryhearingaid.data.SoundAlert
import com.example.tryhearingaid.data.SoundCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WearableBridgeImpl] (Task 15.4).
 *
 * Validates Requirements: 14.3, 14.5, 14.6
 */
class WearableBridgeTest {

    private fun segment(text: String = "hello") = CaptionSegment(
        speakerLabel = "Speaker 1",
        text = text,
        confidence = 0.9f,
        timestampMs = 0L,
        isUncertain = false
    )

    private fun alert() = SoundAlert(
        category = SoundCategory.SMOKE_ALARM,
        confidence = 0.95f,
        priority = AlertPriority.HIGH,
        description = null,
        detectedAtMs = 1_000L
    )

    private fun device(address: String, display: Boolean) = BluetoothDeviceInfo(
        address = address,
        name = "device-$address",
        batteryPercent = null,
        supportsDisplay = display
    )

    // ── Battery level (Requirement 14.5) ─────────────────────────────────────

    @Test
    fun `battery is non-null and within 0 to 100 when the GATT characteristic is present`() = runBlocking {
        val bridge = RecordingWearableBridge(batteryByAddress = mapOf("AA" to 73))

        val info = bridge.buildDeviceInfo("AA", "Earbuds", -1)

        assertNotNull(info.batteryPercent)
        assertEquals(73, info.batteryPercent)
        assertTrue(info.batteryPercent!! in 0..100)
    }

    @Test
    fun `battery is null when the device does not expose the characteristic`() = runBlocking {
        val bridge = RecordingWearableBridge(batteryByAddress = mapOf("AA" to null))

        val info = bridge.buildDeviceInfo("AA", "Earbuds", -1)

        assertNull(info.batteryPercent)
    }

    @Test
    fun `battery read failure yields null and does not throw`() = runBlocking {
        val bridge = RecordingWearableBridge(failBatteryRead = true)

        val info = bridge.buildDeviceInfo("AA", "Earbuds", -1)

        assertNull(info.batteryPercent)
    }

    @Test
    fun `parseBatteryByte accepts 0 to 100 and rejects everything else`() {
        val bridge = RecordingWearableBridge()

        assertEquals(0, bridge.parseBatteryByte(0))
        assertEquals(100, bridge.parseBatteryByte(100))
        assertNull(bridge.parseBatteryByte(101))
        assertNull(bridge.parseBatteryByte(255))
        // A signed byte of -1 is 0xFF = 255, which is out of range.
        assertNull(bridge.parseBatteryByte(-1))
    }

    // ── Display detection ────────────────────────────────────────────────────

    @Test
    fun `watches and glasses are display capable but earbuds are not`() {
        val bridge = RecordingWearableBridge()

        assertTrue(bridge.determineSupportsDisplay("Galaxy Watch 6", -1))
        assertTrue(bridge.determineSupportsDisplay("Smart GLASSES", -1))
        assertFalse(bridge.determineSupportsDisplay("AirPods Pro", -1))
    }

    // ── Caption routing (Requirement 14.6) ───────────────────────────────────

    @Test
    fun `caption is sent to a display-capable device`() {
        val bridge = RecordingWearableBridge()
        bridge.addConnectedDevice(device("WATCH", display = true))
        val seg = segment("lecture starts now")

        bridge.sendCaptions(seg)

        assertEquals(listOf("WATCH" to seg), bridge.captionDeliveries)
        assertEquals(listOf(seg), bridge.sentCaptions)
    }

    @Test
    fun `caption is not sent to a device without a display`() {
        val bridge = RecordingWearableBridge()
        bridge.addConnectedDevice(device("EARBUDS", display = false))

        bridge.sendCaptions(segment())

        assertTrue(bridge.captionDeliveries.isEmpty())
        assertTrue(bridge.sentCaptions.isEmpty())
    }

    @Test
    fun `with a watch and earbuds connected only the watch receives the caption`() {
        val bridge = RecordingWearableBridge()
        bridge.addConnectedDevice(device("EARBUDS", display = false))
        bridge.addConnectedDevice(device("WATCH", display = true))

        bridge.sendCaptions(segment())

        assertEquals(listOf("WATCH"), bridge.captionDeliveries.map { it.first })
    }

    @Test
    fun `caption with no connected devices is dropped quietly`() {
        val bridge = RecordingWearableBridge()

        bridge.sendCaptions(segment())

        assertTrue(bridge.captionDeliveries.isEmpty())
    }

    // ── Alert forwarding (Requirement 14.3) ──────────────────────────────────

    @Test
    fun `alert is forwarded to a connected smartwatch`() {
        val bridge = RecordingWearableBridge()
        bridge.addConnectedDevice(device("WATCH", display = true))
        val sound = alert()

        bridge.sendAlert(sound)

        assertEquals(listOf("WATCH" to sound), bridge.alertDeliveries)
        assertEquals(sound, bridge.lastSentAlert)
        assertEquals(listOf(sound), bridge.sentAlerts)
    }

    @Test
    fun `alert with no connected devices is dropped`() {
        val bridge = RecordingWearableBridge()

        bridge.sendAlert(alert())

        assertTrue(bridge.alertDeliveries.isEmpty())
        assertNull(bridge.lastSentAlert)
    }

    // ── A2DP audio-output tracking (Requirement 14.2) ────────────────────────

    @Test
    fun `a connected A2DP device becomes an audio output`() {
        val bridge = RecordingWearableBridge()

        val result = bridge.applyA2dpState(emptySet(), "AA", BluetoothProfile.STATE_CONNECTED)

        assertEquals(setOf("AA"), result)
    }

    @Test
    fun `a disconnecting or disconnected A2DP device is removed from the audio outputs`() {
        val bridge = RecordingWearableBridge()
        val start = setOf("AA", "BB")

        assertEquals(setOf("BB"), bridge.applyA2dpState(start, "AA", BluetoothProfile.STATE_DISCONNECTED))
        assertEquals(setOf("BB"), bridge.applyA2dpState(start, "AA", BluetoothProfile.STATE_DISCONNECTING))
    }

    @Test
    fun `a device that is only connecting is not yet an audio output`() {
        val bridge = RecordingWearableBridge()

        val result = bridge.applyA2dpState(emptySet(), "AA", BluetoothProfile.STATE_CONNECTING)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `setAudioOutputAddresses is reflected in the audioOutputAddresses flow`() {
        val bridge = RecordingWearableBridge()
        assertTrue(bridge.audioOutputAddresses.value.isEmpty())

        bridge.setAudioOutputAddresses(setOf("AA"))

        assertEquals(setOf("AA"), bridge.audioOutputAddresses.value)
    }
}
