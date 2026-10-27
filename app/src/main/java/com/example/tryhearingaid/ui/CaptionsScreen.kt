package com.example.tryhearingaid.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tryhearingaid.data.CaptionSegment
import com.example.tryhearingaid.ui.captions.CaptionLanguage
import com.example.tryhearingaid.ui.captions.CaptionUiState
import com.example.tryhearingaid.ui.captions.CaptionViewModel
import com.example.tryhearingaid.ui.captions.SUPPORTED_CAPTION_LANGUAGES

// ─── Amber / warning colour ───────────────────────────────────────────────────

private val UncertainAmber = Color(0xFFFFB300)

// ─── Screen entry point ───────────────────────────────────────────────────────

/**
 * Live Captions screen.
 *
 * Displays a scrollable list of [CaptionSegment]s, a language selector, start/stop
 * controls, an export button (Android share sheet), and a classroom-mode FAB.
 *
 * Bound to [CaptionViewModel] via [collectAsStateWithLifecycle].
 *
 * Satisfies Requirements 7.1, 7.2, 7.5, 8.2 (via classroom mode toggle → Req 13.1).
 */
@Composable
fun CaptionsScreen(
    viewModel: CaptionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // ── Share-sheet trigger ───────────────────────────────────────────────────
    // When exportedFile becomes non-null the UI launches a share sheet and then
    // clears the value so the effect doesn't re-trigger.
    LaunchedEffect(uiState.exportedFile) {
        val file = uiState.exportedFile ?: return@LaunchedEffect
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider", // must match the <provider> in AndroidManifest.xml
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension.equals("pdf", ignoreCase = true)) "application/pdf" else "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Export Transcript"))
        viewModel.clearExportedFile()
    }

    CaptionsScreenContent(
        uiState = uiState,
        onStartStop = {
            if (uiState.isActive) viewModel.stopCaptions() else viewModel.startCaptions()
        },
        onLanguageSelected = viewModel::selectLanguage,
        onExport = viewModel::exportTranscript,
        onToggleClassroom = viewModel::toggleClassroomMode
    )
}

// ─── Stateless content composable ────────────────────────────────────────────

/**
 * Stateless content layer — accepts callbacks instead of a ViewModel so it can
 * be previewed and tested independently.
 */
@Composable
internal fun CaptionsScreenContent(
    uiState: CaptionUiState,
    onStartStop: () -> Unit,
    onLanguageSelected: (CaptionLanguage) -> Unit,
    onExport: () -> Unit,
    onToggleClassroom: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            ClassroomModeFab(
                isActive = uiState.isClassroomMode,
                onClick = onToggleClassroom
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Language selector ─────────────────────────────────────────────
            LanguageSelector(
                selected = uiState.selectedLanguage,
                languages = SUPPORTED_CAPTION_LANGUAGES,
                onSelected = onLanguageSelected,
                enabled = !uiState.isActive
            )

            // ── Start / Stop + Export row ─────────────────────────────────────
            ControlRow(
                isActive = uiState.isActive,
                hasSegments = uiState.captions.isNotEmpty(),
                onStartStop = onStartStop,
                onExport = onExport
            )

            // ── Caption list ──────────────────────────────────────────────────
            CaptionList(
                segments = uiState.captions,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ─── Language selector ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelector(
    selected: CaptionLanguage,
    languages: List<CaptionLanguage>,
    onSelected: (CaptionLanguage) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Recognition language") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            languages.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.displayName) },
                    onClick = {
                        onSelected(language)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

// ─── Control row (Start/Stop + Export) ───────────────────────────────────────

@Composable
private fun ControlRow(
    isActive: Boolean,
    hasSegments: Boolean,
    onStartStop: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Start / Stop button
        Button(
            onClick = onStartStop,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isActive)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.weight(1f)
        ) {
            Text(if (isActive) "Stop" else "Start")
        }

        // Export button — only enabled when there is something to export
        Button(
            onClick = onExport,
            enabled = hasSegments,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("Export")
        }
    }
}

// ─── Caption list ─────────────────────────────────────────────────────────────

@Composable
private fun CaptionList(
    segments: List<CaptionSegment>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Auto-scroll to the newest caption when a new segment arrives.
    LaunchedEffect(segments.size) {
        if (segments.isNotEmpty()) {
            listState.animateScrollToItem(segments.lastIndex)
        }
    }

    if (segments.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No captions yet. Tap Start to begin.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items = segments, key = { it.id.toString() }) { segment ->
                CaptionSegmentCard(segment = segment)
            }
        }
    }
}

// ─── Individual caption segment card ─────────────────────────────────────────

/**
 * Renders a single [CaptionSegment].
 *
 * - Speaker label displayed in bold (Requirement 7.3).
 * - Uncertain segments (confidence < 0.70) show an amber warning icon and
 *   "(uncertain)" suffix (Requirement 7.6).
 */
@Composable
private fun CaptionSegmentCard(
    segment: CaptionSegment,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (segment.isUncertain)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Speaker label row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${segment.speakerLabel}:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (segment.isUncertain) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Low confidence",
                        tint = UncertainAmber,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            // Caption text (+ "(uncertain)" suffix when applicable)
            val displayText = if (segment.isUncertain) {
                "${segment.text} (uncertain)"
            } else {
                segment.text
            }
            Text(
                text = displayText,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = if (segment.isUncertain) FontStyle.Italic else FontStyle.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─── Classroom mode FAB ───────────────────────────────────────────────────────

/**
 * Floating action button that toggles classroom mode on/off (Requirement 13.1).
 *
 * The FAB uses a school icon and changes its label/colour to reflect the current state.
 */
@Composable
private fun ClassroomModeFab(
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = {
            Icon(
                imageVector = Icons.Default.School,
                contentDescription = if (isActive) "Exit classroom mode" else "Enter classroom mode"
            )
        },
        text = {
            Text(if (isActive) "Classroom: On" else "Classroom")
        },
        containerColor = if (isActive)
            MaterialTheme.colorScheme.tertiaryContainer
        else
            MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (isActive)
            MaterialTheme.colorScheme.onTertiaryContainer
        else
            MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
    )
}
