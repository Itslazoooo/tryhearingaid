package com.example.tryhearingaid.ui

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.tryhearingaid.ui.settings.SettingsViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ToggleRow(
            title = "Read sound descriptions aloud",
            subtitle = "Uses text-to-speech in Awareness Mode.",
            checked = state.ttsEnabled,
            onChange = viewModel::setTtsEnabled
        )
        ToggleRow(
            title = "Cloud AI",
            subtitle = "Allow online AI for summaries and translation. Off keeps everything on this phone.",
            checked = state.cloudAiEnabled,
            onChange = viewModel::setCloudAiEnabled
        )

        Text("Bluetooth devices", style = MaterialTheme.typography.titleMedium)
        if (devices.isEmpty()) {
            Text(
                "No Bluetooth hearing aids, earbuds or watches connected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        devices.forEach { device ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(device.name.ifBlank { device.address }, modifier = Modifier.weight(1f))
                Text(
                    device.batteryPercent?.let { "Battery $it%" } ?: "Battery unknown",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        OfflineCaptionsSection()

        if (Build.VERSION.SDK_INT >= 34) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.canUseFullScreenIntent()) {
                Text(
                    "Emergency alerts cannot wake the screen yet. Allow full-screen alerts for " +
                        "this app so a fire alarm lights up a locked phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                                .setData(Uri.fromParts("package", context.packageName, null))
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Allow full-screen alerts") }
            }
        }

        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", context.packageName, null))
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Manage permissions") }

        Button(
            onClick = { confirmDelete = true },
            enabled = !state.deleting,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (state.deleting) "Deleting…" else "Delete all my data") }

        state.message?.let { message ->
            Text(message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
        }

        Text(
            "The hearing test and amplifier are not a medical device or a clinical hearing test. " +
                "See an audiologist if you are worried about your hearing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all data?") },
            text = {
                Text(
                    "Profiles, hearing tests, transcripts, recordings, exposure history, " +
                        "contacts and settings will be removed. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteAllData()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
