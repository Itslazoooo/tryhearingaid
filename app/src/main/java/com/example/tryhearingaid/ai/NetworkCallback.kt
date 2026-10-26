package com.example.tryhearingaid.ai

/**
 * Abstraction over [android.net.ConnectivityManager.NetworkCallback] so that
 * [AIAssistantImpl] can be tested without Android framework dependencies.
 */
interface NetworkCallback {
    /** Register a listener for connectivity changes. */
    fun registerListener(listener: NetworkStateListener)

    /** Stop receiving connectivity updates. */
    fun unregisterListener()
}

/** Callback interface for network availability changes. */
interface NetworkStateListener {
    fun onNetworkAvailable()
    fun onNetworkLost()
}

/**
 * Simple in-memory [NetworkCallback] for unit tests.
 * Call [simulateConnected] / [simulateDisconnected] to drive state changes.
 */
class FakeNetworkCallback : NetworkCallback {
    private var listener: NetworkStateListener? = null
    var isConnected: Boolean = true

    override fun registerListener(listener: NetworkStateListener) {
        this.listener = listener
        // Immediately deliver current state
        if (isConnected) listener.onNetworkAvailable() else listener.onNetworkLost()
    }

    override fun unregisterListener() {
        listener = null
    }

    /** Simulate the network coming back online. */
    fun simulateConnected() {
        isConnected = true
        listener?.onNetworkAvailable()
    }

    /** Simulate the network going offline. */
    fun simulateDisconnected() {
        isConnected = false
        listener?.onNetworkLost()
    }
}
