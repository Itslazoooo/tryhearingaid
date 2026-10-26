package com.example.tryhearingaid.wearable

import android.content.Context
import com.example.tryhearingaid.data.BluetoothDeviceInfo
import com.example.tryhearingaid.data.CaptionSegment
import com.example.tryhearingaid.data.SoundAlert
import io.mockk.mockk

/**
 * Test double for [WearableBridgeImpl] that needs no Bluetooth hardware.
 *
 * - [readBatteryLevel] returns the value configured in [batteryByAddress]
 *   (`null` models a device that does not expose the GATT battery characteristic)
 *   or throws when [failBatteryRead] is set (models a GATT read failure).
 * - Per-device deliveries are recorded in [captionDeliveries] / [alertDeliveries]
 *   so tests can check *which* devices received something, not just that something
 *   was sent.
 */
class RecordingWearableBridge(
    private val batteryByAddress: Map<String, Int?> = emptyMap(),
    private val failBatteryRead: Boolean = false
) : WearableBridgeImpl(mockk<Context>(relaxed = true)) {

    /** (device address, segment) for every caption handed to a device. */
    val captionDeliveries = mutableListOf<Pair<String, CaptionSegment>>()

    /** (device address, alert) for every alert handed to a device. */
    val alertDeliveries = mutableListOf<Pair<String, SoundAlert>>()

    override suspend fun readBatteryLevel(deviceAddress: String): Int? {
        if (failBatteryRead) throw IllegalStateException("simulated GATT read failure")
        return batteryByAddress[deviceAddress]
    }

    override fun transmitCaptionToDevice(device: BluetoothDeviceInfo, segment: CaptionSegment) {
        captionDeliveries += device.address to segment
    }

    override fun transmitAlertToDevice(device: BluetoothDeviceInfo, alert: SoundAlert) {
        alertDeliveries += device.address to alert
    }
}
