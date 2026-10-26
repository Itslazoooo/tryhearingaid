package com.example.tryhearingaid.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.tryhearingaid.data.HearingProfile
import com.example.tryhearingaid.profiles.HearingTestState
import com.example.tryhearingaid.profiles.ProfileIntent
import com.example.tryhearingaid.profiles.ProfileUiState
import com.example.tryhearingaid.profiles.ProfileViewModel

// ─── Root screen composable ───────────────────────────────────────────────────

/**
 * Profiles screen — shows the list of hearing profiles, hearing test controls,
 * and an audiologist recommendation card when thresholds exceed 40 dBHL.
 *
 * Binds to [ProfileViewModel] via [collectAsState].
 *
 * Validates: Requirements 12.1, 5.1, 5.2
 */
@Composable
fun ProfilesScreen(viewModel: ProfileViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    // Dialog visibility state
    var showNewProfileDialog by remember { mutableStateOf(false) }

    // Snackbar for error messages
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.handleIntent(ProfileIntent.DismissError)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Section header + New Profile button ──────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Hearing Profiles", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = { showNewProfileDialog = true }) {
                        Text("New Profile")
                    }
                }
            }

            // ── Profile list ─────────────────────────────────────────────────
            if (uiState.profiles.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No profiles yet. Tap \"New Profile\" to get started.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(uiState.profiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    isActive = profile.id == uiState.activeProfile?.id,
                    onActivate = { viewModel.handleIntent(ProfileIntent.ActivateProfile(profile.id)) },
                    onDelete = { viewModel.handleIntent(ProfileIntent.DeleteProfile(profile.id)) }
                )
            }

            // ── Hearing test section ─────────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                HearingTestSection(
                    testState = uiState.hearingTestState,
                    onStartTest = {
                        viewModel.handleIntent(ProfileIntent.StartHearingTest())
                    },
                    onCanHearIt = { viewModel.handleIntent(ProfileIntent.RecordResponse) },
                    onSkip = { viewModel.handleIntent(ProfileIntent.RecordNoResponse) },
                    onCancel = { viewModel.handleIntent(ProfileIntent.CancelTest) }
                )
            }

            // ── Audiologist recommendation card ──────────────────────────────
            if (uiState.audiologistRecommended) {
                item {
                    AudiologistRecommendationCard()
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    // ── New Profile dialog ────────────────────────────────────────────────────
    if (showNewProfileDialog) {
        NewProfileDialog(
            onSave = { name ->
                if (name.isNotBlank()) {
                    viewModel.handleIntent(ProfileIntent.CreateProfile(name.trim()))
                }
                showNewProfileDialog = false
            },
            onDismiss = { showNewProfileDialog = false }
        )
    }
}

// ─── Profile card ─────────────────────────────────────────────────────────────

@Composable
private fun ProfileCard(
    profile: HearingProfile,
    isActive: Boolean,
    onActivate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Name + Active badge
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isActive)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isActive) {
                    Spacer(Modifier.height(4.dp))
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "Active",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Activate button — disabled when already active
                OutlinedButton(
                    onClick = onActivate,
                    enabled = !isActive
                ) {
                    Text("Activate")
                }

                // Delete icon button
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete profile ${profile.name}",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

// ─── New Profile dialog ───────────────────────────────────────────────────────

@Composable
private fun NewProfileDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var profileName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Profile") },
        text = {
            OutlinedTextField(
                value = profileName,
                onValueChange = { profileName = it },
                label = { Text("Profile name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(profileName) },
                enabled = profileName.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ─── Hearing test section ─────────────────────────────────────────────────────

@Composable
private fun HearingTestSection(
    testState: HearingTestState,
    onStartTest: () -> Unit,
    onCanHearIt: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Hearing Test", style = MaterialTheme.typography.titleMedium)

            when (testState) {
                // ── IDLE ─────────────────────────────────────────────────────
                is HearingTestState.Idle -> {
                    Text(
                        text = "Take a quick audiometric test to calibrate your hearing profile.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onStartTest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start Hearing Test")
                    }
                }

                // ── IN PROGRESS ──────────────────────────────────────────────
                is HearingTestState.InProgress -> {
                    val freq = testState.currentFrequencyHz
                    val level = testState.currentLevelDbhl
                    val ear = testState.ear.name.lowercase()
                        .replaceFirstChar { it.uppercaseChar() }

                    Text(
                        text = "Testing $ear ear",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Frequency: $freq Hz",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Level: $level dBHL",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Spacer(Modifier.height(8.dp))

                    // Large "I Can Hear It" button
                    Button(
                        onClick = onCanHearIt,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            text = "I Can Hear It",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    // Skip and Cancel row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onSkip,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Skip")
                        }
                        TextButton(
                            onClick = onCancel,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }
                    }
                }

                // ── COMPLETE ─────────────────────────────────────────────────
                is HearingTestState.Complete -> {
                    Icon(
                        imageVector = Icons.Default.MedicalServices,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Test Complete",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Your audiogram has been saved and your equalizer settings have been updated.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onStartTest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Retake Test")
                    }
                }

                // ── ERROR ────────────────────────────────────────────────────
                is HearingTestState.Error -> {
                    Text(
                        text = "Test error: ${testState.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(
                        onClick = onStartTest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Try Again")
                    }
                }
            }
        }
    }
}

// ─── Audiologist recommendation card ─────────────────────────────────────────

/**
 * Shown when [ProfileUiState.audiologistRecommended] is true.
 * Requirement 12.6: thresholds exceeding 40 dBHL at any frequency trigger this card.
 */
@Composable
private fun AudiologistRecommendationCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MedicalServices,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Column {
                Text(
                    text = "Audiologist Recommendation",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Your hearing test detected thresholds above 40 dBHL. " +
                            "We recommend consulting a licensed audiologist.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}
