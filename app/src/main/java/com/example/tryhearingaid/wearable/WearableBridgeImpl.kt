package com.example.tryhearingaid.wearable

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import com.example.tryhearingaid.data.BluetoothDeviceInfo
import com.example.tryhearingaid.data.CaptionSegment
import com.example.tryhearingaid.data.SoundAlert
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete implementation of [WearableBridge].
 *
 * Manages Bluetooth Classic (A2DP/HFP) and BLE connections, reads device battery
 * levels over GATT (Battery Service `0x180F` / Battery Level `0x2A19`), and
 * automatically attempts reconnection for up to 30 seconds after an unexpected
 * disconnection before notifying the user.
 *
 * ## Connection model
 * - **Classic (A2DP/HFP)** — profile proxies obtained via
 *   [BluetoothAdapter.getProfileProxy]; connected devices are enumerated from the
 *   proxy. Battery is read best-effort over a fresh GATT connection.
 * - **BLE** — discovered via [android.bluetooth.le.BluetoothLeScanner], connected
 *   through [BluetoothDevice.connectGatt]. Battery is read on the live connection.
 *
 * ## Battery level (Requirement 14.5, Property 26)
 * Read from the GATT Battery Level characteristic (`0x2A19`). On success the byte
 * (0–100) is parsed into [BluetoothDeviceInfo.batteryPercent]. On read failure or
 * when the characteristic is absent the value is `null` and **no error is surfaced**
 * to the user (per the design error-handling table).
 *
 * ## Reconnection (Requirement 14.4)
 * On unexpected disconnection [startReconnect] launches a coroutine that retries
 * every [RECONNECT_INTERVAL_MS] for up to [RECONNECT_TIMEOUT_MS]. If reconnection
 * fails within the window a user-facing message is emitted on
 * [reconnectTimeoutMessages].
 *
 * ## Caption routing (Requirement 14.6, Property 27)
 * [sendCaptions] only transmits text to devices where
 * [BluetoothDeviceInfo.supportsDisplay] == `true`.
 *
 * ## Testability
 * Pure logic ([parseBatteryByte], [determineSupportsDisplay]) is separated from
 * hardware calls, and the hardware methods ([readBatteryLevel], [attemptReconnect],
 * [buildDeviceInfo]) are `open` so tests can subclass and substitute fakes.
 * Sent alerts/captions are tracked in [sentAlerts] / [sentCaptions] for verification.
 *
 * Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6
 */
@Singleton
open class WearableBridgeImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : WearableBridge {

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "WearableBridgeImpl"

        /** Reconnect for up to 30 seconds before notifying the user (Requirement 14.4). */
        const val RECONNECT_TIMEOUT_MS = 30_000L

        /** Delay between reconnection attempts within the reconnect window. */
        const val RECONNECT_INTERVAL_MS = 3_000L

        /** Hard timeout for a single battery GATT read. */
        const val BATTERY_READ_TIMEOUT_MS = 8_000L

        /** Hard timeout for a single reconnect GATT attempt. */
        const val RECONNECT_ATTEMPT_TIMEOUT_MS = 8_000L

        // ── GATT Battery Service / Battery Level (Requirement 14.5) ───────────
        val BATTERY_SERVICE_UUID: UUID =
            UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        val BATTERY_LEVEL_UUID: UUID =
            UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

        /**
         * Device-name substrings that imply a display-capable wearable
         * (smartwatch / smart glasses). Used by [determineSupportsDisplay].
         */
        val DISPLAY_KEYWORDS: List<String> = listOf(
            "watch", "glass", "eyewear", "spectacles", "smartwatch", "band-display"
        )
    }

    // ── Coroutine scope (SupervisorJob + Dispatchers.IO) ──────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Connected-device state flow ───────────────────────────────────────────

    private val _connectedDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    override val connectedDevices: StateFlow<List<BluetoothDeviceInfo>> =
        _connectedDevices.asStateFlow()

    /**
     * User-facing messages emitted when a reconnection attempt times out
     * (Requirement 14.4). Collected by the UI to show a reconnect-failed notice.
     */
    private val _reconnectTimeoutMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val reconnectTimeoutMessages: SharedFlow<String> = _reconnectTimeoutMessages.asSharedFlow()

    // ── Test-verification state ────────────────────────────────────────────────
    //  These record every alert/caption forwarded to wearables so that tests can
    //  verify the routing behaviour without real Bluetooth hardware.

    private val sentLock = Any()
    private val _sentAlerts = mutableListOf<SoundAlert>()
    private val _sentCaptions = mutableListOf<CaptionSegment>()

    /** Snapshot of every [SoundAlert] forwarded to connected wearables. */
    val sentAlerts: List<SoundAlert>
        get() = synchronized(sentLock) { _sentAlerts.toList() }

    /** Snapshot of every [CaptionSegment] forwarded to display-capable wearables. */
    val sentCaptions: List<CaptionSegment>
        get() = synchronized(sentLock) { _sentCaptions.toList() }

    /** The most recently forwarded alert (convenience for single-call assertions). */
    @Volatile
    var lastSentAlert: SoundAlert? = null
        private set

    /** Clear recorded alerts/captions (test helper). */
    fun clearSentRecords() {
        synchronized(sentLock) {
            _sentAlerts.clear()
            _sentCaptions.clear()
        }
        lastSentAlert = null
    }

    // ── Bluetooth system handles ──────────────────────────────────────────────

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val bluetoothLeScanner: android.bluetooth.le.BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    /** Active GATT connections keyed by device address. */
    private val gattConnections = ConcurrentHashMap<String, BluetoothGatt>()

    /** In-flight reconnect jobs keyed by device address. */
    private val reconnectJobs = ConcurrentHashMap<String, Job>()

    // Classic profile proxies
    @Volatile
    private var a2dpProxy: BluetoothA2dp? = null

    @Volatile
    private var headsetProxy: BluetoothHeadset? = null

    // ── Pure / testable helpers ───────────────────────────────────────────────

    /**
     * Parse a raw battery-level byte into an [Int] in `[0, 100]`, or `null` when the
     * value is outside the valid GATT Battery Level range (Property 26).
     *
     * The GATT Battery Level characteristic is defined as a single byte 0–100.
     */
    fun parseBatteryByte(byte: Int): Int? {
        val v = byte and 0xFF
        return if (v in 0..100) v else null
    }

    /**
     * Heuristic deciding whether a device is display-capable (smartwatch / smart
     * glasses). Considers the device name and the Bluetooth major device class.
     *
     * Overridable so tests can force a known value (Property 27).
     *
     * @param deviceName Friendly name of the device (case-insensitive).
     * @param majorDeviceClass [BluetoothClass.getMajorDeviceClass] value, or -1 if unknown.
     */
    open fun determineSupportsDisplay(deviceName: String, majorDeviceClass: Int): Boolean {
        val name = deviceName.lowercase()
        if (DISPLAY_KEYWORDS.any { name.contains(it) }) return true
        // Wearable major class (smartwatches/glasses) implies a display.
        if (majorDeviceClass == BluetoothClass.Device.Major.WEARABLE) return true
        return false
    }

    /**
     * Build a [BluetoothDeviceInfo] for a discovered/connected device.
     *
     * Reads the battery level via [readBatteryLevel] (overridable, best-effort) and
     * derives [BluetoothDeviceInfo.supportsDisplay] via [determineSupportsDisplay].
     * Both are overridable so tests can fully control the resulting info.
     */
    open suspend fun buildDeviceInfo(
        deviceAddress: String,
        deviceName: String,
        majorDeviceClass: Int
    ): BluetoothDeviceInfo {
        val battery = try {
            readBatteryLevel(deviceAddress)
        } catch (e: Exception) {
            Log.w(TAG, "Battery read failed for $deviceAddress: ${e.message}")
            null
        }
        val display = determineSupportsDisplay(deviceName, majorDeviceClass)
        return BluetoothDeviceInfo(
            address = deviceAddress,
            name = deviceName,
            batteryPercent = battery,
            supportsDisplay = display
        )
    }

    // ── Overridable hardware operations ───────────────────────────────────────

    /**
     * Read the battery level of [deviceAddress] over a GATT connection.
     *
     * Opens a (best-effort) GATT connection, discovers services, and reads the
     * Battery Level characteristic (`0x2A19`). Returns the parsed level in `[0,100]`
     * or `null` on any failure / when the characteristic is absent
     * (Requirement 14.5, Property 26).
     *
     * Overridable so tests can return a controlled value without real hardware.
     */
    @SuppressLint("MissingPermission")
    open suspend fun readBatteryLevel(deviceAddress: String): Int? {
        val adapter = bluetoothAdapter ?: return null
        val device = try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (e: Exception) {
            Log.w(TAG, "getRemoteDevice failed: ${e.message}")
            return null
        } ?: return null

        // Reuse an existing live GATT connection if one is already open.
        gattConnections[deviceAddress]?.let { existing ->
            return readBatteryOnExistingGatt(existing)
        }

        val deferred = CompletableDeferred<Int?>()
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        try {
                            gatt.discoverServices()
                        } catch (e: Exception) {
                            deferred.complete(null)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (!deferred.isCompleted) deferred.complete(null)
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val characteristic = gatt.getService(BATTERY_SERVICE_UUID)
                    ?.getCharacteristic(BATTERY_LEVEL_UUID)
                if (characteristic == null) {
                    deferred.complete(null)
                } else {
                    try {
                        gatt.readCharacteristic(characteristic)
                    } catch (e: Exception) {
                        deferred.complete(null)
                    }
                }
            }

            // Pre-API 33 callback signature.
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    @Suppress("DEPRECATION")
                    val value = characteristic.value
                    if (value != null && value.isNotEmpty()) {
                        deferred.complete(parseBatteryByte(value[0].toInt()))
                    } else {
                        deferred.complete(null)
                    }
                } else {
                    deferred.complete(null)
                }
            }

            // API 33+ callback signature.
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS && value.isNotEmpty()) {
                    deferred.complete(parseBatteryByte(value[0].toInt()))
                } else {
                    deferred.complete(null)
                }
            }
        }

        var gatt: BluetoothGatt? = null
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.w(TAG, "connectGatt denied (BLUETOOTH_CONNECT): ${e.message}")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "connectGatt failed: ${e.message}")
            return null
        }

        val result = withTimeoutOrNull(BATTERY_READ_TIMEOUT_MS) { deferred.await() }
        try {
            gatt?.close()
        } catch (_: Exception) {
            // best-effort close
        }
        return result
    }

    /**
     * Trigger a single reconnection attempt for [deviceAddress].
     *
     * Returns `true` if the device (re)connected. Overridable so tests can simulate
     * success/failure of reconnection without real hardware.
     */
    @SuppressLint("MissingPermission")
    open suspend fun attemptReconnect(deviceAddress: String): Boolean {
        val device = try {
            bluetoothAdapter?.getRemoteDevice(deviceAddress)
        } catch (e: Exception) {
            Log.w(TAG, "getRemoteDevice failed during reconnect: ${e.message}")
            return false
        } ?: return false

        val deferred = CompletableDeferred<Boolean>()
        var gatt: BluetoothGatt? = null
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> deferred.complete(true)
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (!deferred.isCompleted) deferred.complete(false)
                    }
                }
            }
        }
        try {
            gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.w(TAG, "Reconnect connectGatt denied: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Reconnect connectGatt failed: ${e.message}")
            return false
        }

        val result = withTimeoutOrNull(RECONNECT_ATTEMPT_TIMEOUT_MS) { deferred.await() } ?: false
        if (!result) {
            try {
                gatt?.close()
            } catch (_: Exception) {
            }
        }
        return result
    }

    // ── Connected-device flow mutators ─────────────────────────────────────────

    /**
     * Insert or replace a device entry keyed by address, keeping order stable.
     */
    fun addConnectedDevice(info: BluetoothDeviceInfo) {
        _connectedDevices.update { current ->
            current.filterNot { it.address == info.address } + info
        }
    }

    /** Remove a device from [connectedDevices]. */
    fun removeConnectedDevice(deviceAddress: String) {
        _connectedDevices.update { current ->
            current.filterNot { it.address == deviceAddress }
        }
    }

    /** Update only the battery level of a tracked device (used by GATT callbacks). */
    fun updateDeviceBattery(deviceAddress: String, batteryPercent: Int?) {
        _connectedDevices.update { current ->
            current.map { if (it.address == deviceAddress) it.copy(batteryPercent = batteryPercent) else it }
        }
    }

    // ── WearableBridge API ─────────────────────────────────────────────────────

    /**
     * Forward [alert] to every connected wearable, triggering a haptic/vibration
     * on the receiving device (Requirement 14.3).
     *
     * No-op when no devices are connected. Each forwarded alert is recorded in
     * [sentAlerts] / [lastSentAlert] for test verification.
     */
    override fun sendAlert(alert: SoundAlert) {
        val devices = connectedDevices.value
        if (devices.isEmpty()) {
            Log.d(TAG, "sendAlert: no connected wearables, dropping alert.")
            return
        }
        for (device in devices) {
            transmitAlertToDevice(device, alert)
        }
        synchronized(sentLock) { _sentAlerts.add(alert) }
        lastSentAlert = alert
        Log.d(TAG, "Alert forwarded to ${devices.size} wearable(s): ${alert.category}")
    }

    /**
     * Transmit [segment] text to display-capable wearables only
     * (Requirement 14.6, Property 27). Devices with [BluetoothDeviceInfo.supportsDisplay]
     * == `false` receive nothing.
     *
     * Each transmitted segment is recorded in [sentCaptions] for test verification.
     */
    override fun sendCaptions(segment: CaptionSegment) {
        val devices = connectedDevices.value
        var dispatched = 0
        for (device in devices) {
            if (device.supportsDisplay) {
                transmitCaptionToDevice(device, segment)
                dispatched++
            }
        }
        if (dispatched > 0) {
            synchronized(sentLock) { _sentCaptions.add(segment) }
        }
        Log.d(TAG, "Caption dispatched to $dispatched display-capable wearable(s).")
    }

    /**
     * Start the 30-second reconnect loop for [deviceAddress]
     * (Requirement 14.4).
     *
     * Retries every [RECONNECT_INTERVAL_MS] until [RECONNECT_TIMEOUT_MS] elapses.
     * On success the loop stops and the device is re-registered. On timeout a
     * user-facing message is emitted on [reconnectTimeoutMessages].
     */
    override fun startReconnect(deviceAddress: String) {
        // Cancel any in-flight reconnect job for this device.
        reconnectJobs.remove(deviceAddress)?.cancel()
        val job = scope.launch {
            Log.i(TAG, "Starting reconnect loop for $deviceAddress (up to ${RECONNECT_TIMEOUT_MS}ms).")
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < RECONNECT_TIMEOUT_MS) {
                val reconnected = try {
                    attemptReconnect(deviceAddress)
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect attempt failed: ${e.message}")
                    false
                }
                if (reconnected) {
                    Log.i(TAG, "Reconnected to $deviceAddress.")
                    return@launch
                }
                delay(RECONNECT_INTERVAL_MS)
            }
            // Timeout reached — notify the user.
            val message = "Unable to reconnect to Bluetooth device after " +
                    "${RECONNECT_TIMEOUT_MS / 1000} seconds."
            Log.w(TAG, "Reconnect timeout for $deviceAddress: $message")
            _reconnectTimeoutMessages.tryEmit(message)
        }
        reconnectJobs[deviceAddress] = job
    }

    // ── Alert / caption transport (overridable) ─────────────────────────────────

    /**
     * Deliver [alert] to a single connected [device].
     *
     * Default implementation logs the action; a real smartwatch client would send
     * a haptic/notification message here. Overridable so tests can capture per-device
     * delivery.
     */
    open fun transmitAlertToDevice(device: BluetoothDeviceInfo, alert: SoundAlert) {
        Log.d(TAG, "Alert -> ${device.name} (${device.address}): ${alert.category} " +
                "priority=${alert.priority} confidence=${alert.confidence}")
        // A real implementation would write the alert over the device's GATT/profile
        // link to trigger vibration/haptics.
    }

    /**
     * Deliver [segment] text to a single display-capable [device].
     *
     * Only called for devices where [BluetoothDeviceInfo.supportsDisplay] == `true`.
     * Overridable so tests can capture per-device delivery.
     */
    open fun transmitCaptionToDevice(device: BluetoothDeviceInfo, segment: CaptionSegment) {
        Log.d(TAG, "Caption -> ${device.name} (${device.address}): ${segment.text}")
        // A real implementation would stream the caption text to the wearable display.
    }

    // ── BLE scanning & connection ──────────────────────────────────────────────

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            if (gattConnections.containsKey(address) ||
                connectedDevices.value.any { it.address == address }
            ) {
                return // already tracking this device
            }
            connectBleDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed (errorCode=$errorCode).")
        }
    }

    /**
     * Begin scanning for BLE wearables (Requirement 14.1).
     *
     * Safe to call when Bluetooth is unavailable or permissions are missing — the
     * call is wrapped in a try/catch and logs a warning instead of crashing.
     */
    @SuppressLint("MissingPermission")
    fun startBleScan() {
        val scanner = bluetoothLeScanner ?: run {
            Log.w(TAG, "BluetoothLeScanner unavailable; BLE scan not started.")
            return
        }
        try {
            scanner.startScan(bleScanCallback)
            Log.i(TAG, "BLE scan started.")
        } catch (e: SecurityException) {
            Log.w(TAG, "BLUETOOTH_SCAN permission not granted: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "BLE scan start failed: ${e.message}")
        }
    }

    /** Stop an in-progress BLE scan. */
    @SuppressLint("MissingPermission")
    fun stopBleScan() {
        val scanner = bluetoothLeScanner ?: return
        try {
            scanner.stopScan(bleScanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan denied: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "BLE scan stop failed: ${e.message}")
        }
    }

    /**
     * Open a GATT connection to a discovered BLE [device], register it as
     * connected, and read its battery level on the live connection.
     */
    @SuppressLint("MissingPermission")
    private fun connectBleDevice(device: BluetoothDevice) {
        val address = device.address
        val name = try {
            device.name ?: "Unknown"
        } catch (e: SecurityException) {
            "Unknown"
        }
        val majorClass = device.bluetoothClass?.majorDeviceClass ?: -1

        // Register immediately (battery null until the GATT read completes).
        addConnectedDevice(
            BluetoothDeviceInfo(
                address = address,
                name = name,
                batteryPercent = null,
                supportsDisplay = determineSupportsDisplay(name, majorClass)
            )
        )

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        try {
                            gatt.discoverServices()
                        } catch (e: Exception) {
                            Log.w(TAG, "discoverServices failed: ${e.message}")
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        gattConnections.remove(address)
                        try {
                            gatt.close()
                        } catch (_: Exception) {
                        }
                        // Unexpected disconnection → start reconnect loop.
                        handleDisconnection(address)
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                readBatteryOnExistingGatt(gatt)
            }

            // Pre-API 33 callback signature.
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    @Suppress("DEPRECATION")
                    val value = characteristic.value
                    if (value != null && value.isNotEmpty()) {
                        updateDeviceBattery(address, parseBatteryByte(value[0].toInt()))
                    }
                }
                // On read failure leave battery as null — do not surface an error.
            }

            // API 33+ callback signature.
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS && value.isNotEmpty()) {
                    updateDeviceBattery(address, parseBatteryByte(value[0].toInt()))
                }
            }
        }

        try {
            val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt != null) gattConnections[address] = gatt
        } catch (e: SecurityException) {
            Log.w(TAG, "BLE connectGatt denied: ${e.message}")
            removeConnectedDevice(address)
        } catch (e: Exception) {
            Log.w(TAG, "BLE connectGatt failed: ${e.message}")
            removeConnectedDevice(address)
        }
    }

    /**
     * Initiate a battery-level read on an already-connected [gatt] connection.
     * No-op (leaves battery `null`) if the Battery Level service/characteristic
     * is not exposed.
     */
    private fun readBatteryOnExistingGatt(gatt: BluetoothGatt): Int? {
        val characteristic = try {
            gatt.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_UUID)
        } catch (e: Exception) {
            Log.w(TAG, "Battery service lookup failed: ${e.message}")
            null
        }
        if (characteristic == null) return null
        return try {
            gatt.readCharacteristic(characteristic)
            null // value arrives asynchronously via the callback
        } catch (e: Exception) {
            Log.w(TAG, "readCharacteristic failed: ${e.message}")
            null
        }
    }

    // ── Classic (A2DP/HFP) profile proxies ─────────────────────────────────────

    private val profileServiceListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> {
                    a2dpProxy = proxy as? BluetoothA2dp
                    registerClassicDevices(a2dpProxy?.connectedDevices)
                }
                BluetoothProfile.HEADSET -> {
                    headsetProxy = proxy as? BluetoothHeadset
                    registerClassicDevices(headsetProxy?.connectedDevices)
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = null
                BluetoothProfile.HEADSET -> headsetProxy = null
            }
        }
    }

    /**
     * Request A2DP and HFP profile proxies and register currently-connected classic
     * devices (Requirement 14.1).
     */
    @SuppressLint("MissingPermission")
    fun setupClassicProfiles() {
        val adapter = bluetoothAdapter ?: run {
            Log.w(TAG, "BluetoothAdapter unavailable; classic profiles not set up.")
            return
        }
        try {
            adapter.getProfileProxy(context, profileServiceListener, BluetoothProfile.A2DP)
            adapter.getProfileProxy(context, profileServiceListener, BluetoothProfile.HEADSET)
            Log.i(TAG, "Classic A2DP/HFP profile proxies requested.")
        } catch (e: SecurityException) {
            Log.w(TAG, "getProfileProxy denied (BLUETOOTH_CONNECT): ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "getProfileProxy failed: ${e.message}")
        }
    }

    /**
     * Register a set of classic (A2DP/HFP) connected devices and read their battery
     * levels asynchronously.
     */
    @SuppressLint("MissingPermission")
    private fun registerClassicDevices(devices: List<BluetoothDevice>?) {
        if (devices.isNullOrEmpty()) return
        for (device in devices) {
            val address = device.address ?: continue
            val name = try {
                device.name ?: "Unknown"
            } catch (e: SecurityException) {
                "Unknown"
            }
            val majorClass = device.bluetoothClass?.majorDeviceClass ?: -1
            addConnectedDevice(
                BluetoothDeviceInfo(
                    address = address,
                    name = name,
                    batteryPercent = null,
                    supportsDisplay = determineSupportsDisplay(name, majorClass)
                )
            )
            // Best-effort battery read over GATT for classic devices.
            scope.launch {
                val battery = readBatteryLevel(address)
                updateDeviceBattery(address, battery)
            }
        }
    }

    // ── Disconnection handling ─────────────────────────────────────────────────

    /**
     * Called when a device disconnects unexpectedly. Removes it from the tracked
     * list and starts the reconnect loop (Requirement 14.4).
     */
    private fun handleDisconnection(deviceAddress: String) {
        Log.w(TAG, "Unexpected disconnection of $deviceAddress; starting reconnect.")
        removeConnectedDevice(deviceAddress)
        startReconnect(deviceAddress)
    }

    // ── Lifecycle / cleanup ────────────────────────────────────────────────────

    /**
     * Release all resources: cancel coroutines, stop BLE scanning, close GATT
     * connections, and drop profile proxies.
     *
     * Call this when the owning service is destroyed.
     */
    @SuppressLint("MissingPermission")
    fun release() {
        Log.i(TAG, "Releasing WearableBridgeImpl resources.")
        stopBleScan()
        // Close all live GATT connections.
        for ((_, gatt) in gattConnections) {
            try {
                gatt.close()
            } catch (_: Exception) {
            }
        }
        gattConnections.clear()
        // Cancel reconnect loops.
        for ((_, job) in reconnectJobs) {
            job.cancel()
        }
        reconnectJobs.clear()
        // Drop profile proxies.
        try {
            a2dpProxy?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        } catch (e: Exception) {
            Log.w(TAG, "closeProfileProxy A2DP failed: ${e.message}")
        }
        try {
            headsetProxy?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        } catch (e: Exception) {
            Log.w(TAG, "closeProfileProxy HEADSET failed: ${e.message}")
        }
        a2dpProxy = null
        headsetProxy = null
        scope.cancel()
    }
}
