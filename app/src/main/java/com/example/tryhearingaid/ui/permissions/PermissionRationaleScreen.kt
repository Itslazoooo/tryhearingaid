package com.example.tryhearingaid.ui.permissions

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

// ─── Permission metadata ───────────────────────────────────────────────────────

private data class PermissionInfo(
    val permission: String,
    val name: String,
    val rationale: String
)

private val permissionList = listOf(
    PermissionInfo(
        permission = Manifest.permission.RECORD_AUDIO,
        name = "Microphone",
        rationale = "Required for real-time audio amplification and captioning"
    ),
    PermissionInfo(
        permission = Manifest.permission.ACCESS_FINE_LOCATION,
        name = "Location",
        rationale = "Used only for emergency SOS to send your location"
    ),
    PermissionInfo(
        permission = Manifest.permission.CAMERA,
        name = "Camera",
        rationale = "Used to flash the torch for sound alerts"
    ),
    PermissionInfo(
        permission = Manifest.permission.BLUETOOTH_CONNECT,
        name = "Bluetooth",
        rationale = "Required to connect to Bluetooth hearing aids and earbuds"
    ),
    PermissionInfo(
        permission = Manifest.permission.POST_NOTIFICATIONS,
        name = "Notifications",
        rationale = "Required to deliver sound alerts and emergency notifications"
    )
)

// ─── Screen ───────────────────────────────────────────────────────────────────

/**
 * Explains each required permission and provides a per-permission "Grant" button.
 * A "Continue" button (enabled only when RECORD_AUDIO is granted) advances past
 * this screen by invoking [onAllRequiredGranted].
 *
 * Satisfies Requirement 1.4 and 16.1: displays a rationale screen with a clear
 * human-readable justification before requesting each permission.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionRationaleScreen(
    onAllRequiredGranted: () -> Unit
) {
    val recordAudioState = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val locationState = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)
    val cameraState = rememberPermissionState(Manifest.permission.CAMERA)
    val bluetoothState = rememberPermissionState(Manifest.permission.BLUETOOTH_CONNECT)
    val notificationsState = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)

    val permissionStates: Map<String, PermissionState> = mapOf(
        Manifest.permission.RECORD_AUDIO to recordAudioState,
        Manifest.permission.ACCESS_FINE_LOCATION to locationState,
        Manifest.permission.CAMERA to cameraState,
        Manifest.permission.BLUETOOTH_CONNECT to bluetoothState,
        Manifest.permission.POST_NOTIFICATIONS to notificationsState
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Permissions Required",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "This app needs the following permissions to provide hearing assistance features.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        permissionList.forEach { info ->
            val permState = permissionStates[info.permission]
            PermissionCard(
                permissionInfo = info,
                isGranted = permState?.status?.isGranted == true,
                onGrant = { permState?.launchPermissionRequest() }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onAllRequiredGranted,
            enabled = recordAudioState.status.isGranted,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
    }
}

// ─── Permission card ──────────────────────────────────────────────────────────

@Composable
private fun PermissionCard(
    permissionInfo: PermissionInfo,
    isGranted: Boolean,
    onGrant: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = permissionInfo.name,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = permissionInfo.rationale,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isGranted) {
                Text(
                    text = "Granted",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            } else {
                OutlinedButton(
                    onClick = onGrant,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text("Grant")
                }
            }
        }
    }
}
