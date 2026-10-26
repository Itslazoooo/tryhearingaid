package com.example.tryhearingaid.audio

/**
 * Decides which [AudioDevice] processed audio should be sent to, given the set of
 * currently connected Bluetooth A2DP audio devices (Requirement 14.2).
 *
 * Kept free of Android and coroutine dependencies so it can be unit tested on the JVM.
 */
object AudioRoutePolicy {

    /**
     * Choose the output route.
     *
     * - If at least one A2DP device is connected, route to [AudioDevice.Bluetooth].
     *   When several are connected the lowest address is chosen so the result is
     *   deterministic and does not flip-flop between devices.
     * - Otherwise fall back to [AudioDevice.Default] (speaker / wired headset), which
     *   is also the fallback required by the design's A2DP error-handling row.
     */
    fun choose(connectedA2dpAddresses: Set<String>): AudioDevice {
        val address = connectedA2dpAddresses.minOrNull()
        return if (address != null) AudioDevice.Bluetooth(address) else AudioDevice.Default
    }
}
