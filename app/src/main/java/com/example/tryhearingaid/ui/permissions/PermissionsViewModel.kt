package com.example.tryhearingaid.ui.permissions

import android.Manifest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── UI state ─────────────────────────────────────────────────────────────────

/**
 * Represents the current grant state of every permission the app requests.
 * Computed properties reflect whether each feature group is usable.
 *
 * Satisfies Requirement 16.3: dependent features are disabled when the corresponding
 * permission is not granted.
 */
data class PermissionsUiState(
    val isRecordAudioGranted: Boolean = false,
    val isLocationGranted: Boolean = false,
    val isCameraGranted: Boolean = false,
    val isBluetoothConnectGranted: Boolean = false,
    val isPostNotificationsGranted: Boolean = false
) {
    /** Audio amplification, live captions, sound alerts, and analytics require microphone. */
    val isAudioFeatureEnabled: Boolean get() = isRecordAudioGranted

    /** Emergency SOS GPS transmission requires location. */
    val isLocationFeatureEnabled: Boolean get() = isLocationGranted

    /** Flashlight alerts require camera (torch) access. */
    val isCameraFeatureEnabled: Boolean get() = isCameraGranted

    /** Bluetooth hearing-aid / earbud routing requires BLUETOOTH_CONNECT. */
    val isBluetoothFeatureEnabled: Boolean get() = isBluetoothConnectGranted

    /** Whether [permission] is currently granted. Unknown permissions count as not granted. */
    fun isGranted(permission: String): Boolean = when (permission) {
        Manifest.permission.RECORD_AUDIO -> isRecordAudioGranted
        Manifest.permission.ACCESS_FINE_LOCATION -> isLocationGranted
        Manifest.permission.CAMERA -> isCameraGranted
        Manifest.permission.BLUETOOTH_CONNECT -> isBluetoothConnectGranted
        Manifest.permission.POST_NOTIFICATIONS -> isPostNotificationsGranted
        else -> false
    }

    /**
     * Names of every feature that can be used right now: a feature is enabled only while the
     * permission it depends on is granted (Requirement 16.3, Property 31).
     */
    val enabledFeatures: Set<String>
        get() = permissionFeatureMap.filterKeys { isGranted(it) }.values.flatten().toSet()
}

// ─── Events ───────────────────────────────────────────────────────────────────

/**
 * One-shot events emitted when a previously-granted permission is revoked at runtime.
 * Satisfies Requirement 16.3: the app notifies the user of the features that are
 * now unavailable.
 */
sealed class PermissionUiEvent {
    data class PermissionRevoked(
        val permission: String,
        val affectedFeatures: List<String>
    ) : PermissionUiEvent()
}

// ─── Feature dependency map ───────────────────────────────────────────────────

/** Which features stop working when each permission is missing. Each feature depends on one permission. */
internal val permissionFeatureMap: Map<String, List<String>> = mapOf(
    Manifest.permission.RECORD_AUDIO to
            listOf("Audio Amplification", "Live Captions", "Sound Alerts", "Analytics"),
    Manifest.permission.ACCESS_FINE_LOCATION to
            listOf("Emergency SOS"),
    Manifest.permission.CAMERA to
            listOf("Flashlight Alerts"),
    Manifest.permission.BLUETOOTH_CONNECT to
            listOf("Bluetooth/Wearable Integration"),
    Manifest.permission.POST_NOTIFICATIONS to
            listOf("Sound Alert Notifications")
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

/**
 * Tracks the grant state of all 5 runtime permissions and exposes events when a
 * previously-granted permission is later revoked.
 *
 * Satisfies Requirements 1.4, 16.1, and 16.3.
 */
@HiltViewModel
class PermissionsViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(PermissionsUiState())
    val uiState: StateFlow<PermissionsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PermissionUiEvent>()
    val events: SharedFlow<PermissionUiEvent> = _events.asSharedFlow()

    /**
     * Update the grant state for [permission].
     *
     * If [granted] is false AND the permission was previously granted, emits a
     * [PermissionUiEvent.PermissionRevoked] event listing every feature that is
     * now unavailable.
     */
    fun onPermissionResult(permission: String, granted: Boolean) {
        val currentState = _uiState.value
        val wasGranted = getGrantedState(currentState, permission)

        // Update the state first
        _uiState.value = currentState.withPermission(permission, granted)

        // Emit revocation event only when going from granted → revoked
        if (wasGranted && !granted) {
            val affectedFeatures = permissionFeatureMap[permission] ?: emptyList()
            viewModelScope.launch {
                _events.emit(
                    PermissionUiEvent.PermissionRevoked(
                        permission = permission,
                        affectedFeatures = affectedFeatures
                    )
                )
            }
        }
    }

    /**
     * Re-read all five permissions from the system and apply any change, so a permission the
     * user switched off in system settings is noticed. Call it each time the app returns to the
     * foreground, passing for example
     * `{ ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }`.
     * Revoked permissions emit [PermissionUiEvent.PermissionRevoked] as usual.
     */
    fun refresh(isGranted: (String) -> Boolean) {
        permissionFeatureMap.keys.forEach { onPermissionResult(it, isGranted(it)) }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Read the current grant state for a specific [permission]. */
    private fun getGrantedState(state: PermissionsUiState, permission: String): Boolean =
        state.isGranted(permission)

    /** Return a copy of [PermissionsUiState] with [permission] set to [granted]. */
    private fun PermissionsUiState.withPermission(permission: String, granted: Boolean): PermissionsUiState =
        when (permission) {
            Manifest.permission.RECORD_AUDIO -> copy(isRecordAudioGranted = granted)
            Manifest.permission.ACCESS_FINE_LOCATION -> copy(isLocationGranted = granted)
            Manifest.permission.CAMERA -> copy(isCameraGranted = granted)
            Manifest.permission.BLUETOOTH_CONNECT -> copy(isBluetoothConnectGranted = granted)
            Manifest.permission.POST_NOTIFICATIONS -> copy(isPostNotificationsGranted = granted)
            else -> this
        }
}
