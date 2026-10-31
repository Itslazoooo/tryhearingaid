package com.example.tryhearingaid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.tryhearingaid.captions.ModelState
import com.example.tryhearingaid.captions.VoskCatalog
import com.example.tryhearingaid.ui.captions.SUPPORTED_CAPTION_LANGUAGES
import com.example.tryhearingaid.ui.settings.OfflineCaptionsViewModel

/** Settings section for downloading the offline speech models used by Live Text. */
@Composable
fun OfflineCaptionsSection(viewModel: OfflineCaptionsViewModel = hiltViewModel()) {
    val states by viewModel.states.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Offline captions", style = MaterialTheme.typography.titleMedium)
        Text(
            "Download a speech model once (it uses the internet), and Live Text then works " +
                "without internet and while the amplifier runs. Languages without a download " +
                "use the phone's built-in recognizer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        VoskCatalog.entries.forEach { entry ->
            val name = SUPPORTED_CAPTION_LANGUAGES.firstOrNull { it.code == entry.language }?.displayName
                ?: entry.language
            val state = states[entry.language] ?: ModelState.NotDownloaded
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name)
                    Text(
                        when (state) {
                            ModelState.NotDownloaded -> "About ${entry.approxMb} MB"
                            is ModelState.Downloading -> "Downloading… ${state.percent}%"
                            ModelState.Ready -> "Ready, works offline"
                            is ModelState.Failed -> "Failed: ${state.message}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state is ModelState.Failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when (state) {
                    ModelState.Ready ->
                        TextButton(onClick = { viewModel.delete(entry.language) }) { Text("Remove") }
                    is ModelState.Downloading -> Unit
                    else ->
                        OutlinedButton(onClick = { viewModel.download(entry.language) }) { Text("Download") }
                }
            }
        }
    }
}
