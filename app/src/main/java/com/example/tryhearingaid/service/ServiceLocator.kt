package com.example.tryhearingaid.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application-scoped singleton that holds the bound [AudioProcessingService] reference.
 *
 * ViewModels and other components resolve the service through this locator rather
 * than holding a direct reference to an Activity or Context, keeping the
 * MVVM layering clean.
 *
 * The [serviceState] flow emits the current bound service (or null when unbound),
 * allowing collectors to react to connect/disconnect events.
 */
object ServiceLocator {
    private val _serviceState = MutableStateFlow<AudioProcessingService?>(null)

    /** Emits the currently bound [AudioProcessingService], or null when not connected. */
    val serviceState: StateFlow<AudioProcessingService?> = _serviceState.asStateFlow()

    /** Convenience accessor — null when the service is not bound. */
    val audioProcessingService: AudioProcessingService? get() = _serviceState.value

    /** Called by [MainActivity] when the service is successfully connected. */
    internal fun onServiceConnected(service: AudioProcessingService) {
        _serviceState.value = service
    }

    /** Called by [MainActivity] when the service disconnects unexpectedly. */
    internal fun onServiceDisconnected() {
        _serviceState.value = null
    }
}
