package com.example.tryhearingaid.wearable

import com.example.tryhearingaid.data.BluetoothDeviceInfo
import com.example.tryhearingaid.data.CaptionSegment
import com.example.tryhearingaid.data.SoundAlert
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages Bluetooth Classic (A2DP/HFP) and BLE connections with automatic reconnection.
 *
 * Concrete implementation ([WearableBridgeImpl]) is provided in Task 15.
 * This interface is declared here so that [NotificationDispatcherImpl] can reference it
 * via an optional dependency (null-safe injection).
 *
 * Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6
 * Properties 26, 27
 */
interface WearableBridge {

    /**
     * The list of currently connected Bluetooth devices.
     * Updated when devices connect or disconnect.
     */
    val connectedDevices: StateFlow<List<BluetoothDeviceInfo>>

    /**
     * Forward a [SoundAlert] to all connected wearables.
     *
     * For smartwatches this triggers a vibration or haptic pattern.
     * No-op if no devices are connected.
     *
     * Requirement 14.4
     */
    fun sendAlert(alert: SoundAlert)

    /**
     * Transmit a [CaptionSegment] to display-capable wearables only
     * (i.e. [BluetoothDeviceInfo.supportsDisplay] == true).
     *
     * Requirement 14.6, Property 27
     */
    fun sendCaptions(segment: CaptionSegment)

    /**
     * Initiate a reconnection attempt to the device at [deviceAddress].
     *
     * Called automatically by the bridge on unexpected disconnection.
     * Retries for up to 30 seconds before notifying the user.
     *
     * Requirement 14.3
     */
    fun startReconnect(deviceAddress: String)
}
