package com.example.tryhearingaid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.tryhearingaid.ai.LlmCatalog
import com.example.tryhearingaid.captions.ModelState
import com.example.tryhearingaid.ui.settings.OfflineAiViewModel

/** Settings section for downloading the offline AI that writes summaries and study notes. */
@Composable
fun OfflineAiSection(viewModel: OfflineAiViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val entry = LlmCatalog.DEFAULT

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Offline AI", style = MaterialTheme.typography.titleMedium)
        Text(
            "A small AI that runs on this phone, with no internet. It writes summaries and study " +
                "notes from your captions. Download it once over Wi-Fi (about ${entry.approxMb} MB). " +
                "It is small, so results are short and simple, and can take a minute or two.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.name)
                Text(
                    when (val s = state) {
                        ModelState.NotDownloaded -> "Not downloaded"
                        is ModelState.Downloading -> "Downloading… ${s.percent}%"
                        ModelState.Ready -> "Ready, works offline"
                        is ModelState.Failed -> "Failed: ${s.message}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is ModelState.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when (state) {
                ModelState.Ready -> TextButton(onClick = viewModel::delete) { Text("Remove") }
                is ModelState.Downloading -> Unit
                is ModelState.Failed -> OutlinedButton(onClick = viewModel::download) { Text("Try again") }
                ModelState.NotDownloaded -> OutlinedButton(onClick = viewModel::download) { Text("Download") }
            }
        }
        (state as? ModelState.Downloading)?.let {
            LinearProgressIndicator(progress = { it.percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
}
