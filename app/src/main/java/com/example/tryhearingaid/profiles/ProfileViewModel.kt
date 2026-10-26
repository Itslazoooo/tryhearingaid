package com.example.tryhearingaid.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tryhearingaid.data.Audiogram
import com.example.tryhearingaid.data.EqBand
import com.example.tryhearingaid.data.EnvironmentPreset
import com.example.tryhearingaid.data.HearingProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

// ─── UI state ─────────────────────────────────────────────────────────────────

data class ProfileUiState(
    val profiles: List<HearingProfile> = emptyList(),
    val activeProfile: HearingProfile? = null,
    val hearingTestState: HearingTestState = HearingTestState.Idle,
    val audiologistRecommended: Boolean = false,
    val errorMessage: String? = null
)

// ─── User intents ─────────────────────────────────────────────────────────────

sealed class ProfileIntent {
    data class CreateProfile(val name: String) : ProfileIntent()
    data class RenameProfile(val profileId: UUID, val newName: String) : ProfileIntent()
    data class DeleteProfile(val profileId: UUID) : ProfileIntent()
    data class ActivateProfile(val profileId: UUID) : ProfileIntent()
    data class StartHearingTest(val profileId: UUID = UUID.randomUUID()) : ProfileIntent()
    object RecordResponse : ProfileIntent()
    object RecordNoResponse : ProfileIntent()
    object CancelTest : ProfileIntent()
    object DismissError : ProfileIntent()
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

/**
 * [ProfileViewModel] exposes a single [StateFlow]<[ProfileUiState]> that the
 * Profiles screen collects.  All user actions are dispatched via [handleIntent].
 */
class ProfileViewModel(
    private val profileManager: ProfileManager,
    private val hearingTestEngine: HearingTestEngine
) : ViewModel() {

    private val _errorMessage = MutableStateFlow<String?>(null)

    /** Combined UI state derived from ProfileManager + HearingTestEngine flows. */
    val uiState: StateFlow<ProfileUiState> = combine(
        profileManager.profiles,
        profileManager.activeProfile,
        hearingTestEngine.testState,
        hearingTestEngine.audiologistRecommended,
        _errorMessage
    ) { profiles, active, testState, audiologistRecommended, error ->
        ProfileUiState(
            profiles = profiles,
            activeProfile = active,
            hearingTestState = testState,
            audiologistRecommended = audiologistRecommended,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProfileUiState()
    )

    // ── Intent handling ───────────────────────────────────────────────────────

    fun handleIntent(intent: ProfileIntent) {
        viewModelScope.launch {
            runCatching {
                when (intent) {
                    is ProfileIntent.CreateProfile -> createProfile(intent.name)
                    is ProfileIntent.RenameProfile -> renameProfile(intent.profileId, intent.newName)
                    is ProfileIntent.DeleteProfile -> profileManager.deleteProfile(intent.profileId)
                    is ProfileIntent.ActivateProfile -> profileManager.activateProfile(intent.profileId)
                    is ProfileIntent.StartHearingTest -> hearingTestEngine.startTest(intent.profileId)
                    ProfileIntent.RecordResponse -> hearingTestEngine.recordResponse()
                    ProfileIntent.RecordNoResponse -> hearingTestEngine.recordNoResponse()
                    ProfileIntent.CancelTest -> hearingTestEngine.cancelTest()
                    ProfileIntent.DismissError -> _errorMessage.value = null
                }
            }.onFailure { e ->
                _errorMessage.value = e.message ?: "An unexpected error occurred"
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun createProfile(name: String) {
        val now = Instant.now()
        val defaultBands = listOf(
            EqBand(250, 0f), EqBand(500, 0f), EqBand(1000, 0f), EqBand(2000, 0f),
            EqBand(3000, 0f), EqBand(4000, 0f), EqBand(6000, 0f), EqBand(8000, 0f)
        )
        val profile = HearingProfile(
            id = UUID.randomUUID(),
            name = name,
            eqBands = defaultBands,
            adaptiveAmplificationEnabled = false,
            noiseReductionPreset = EnvironmentPreset.DEFAULT,
            audiogram = null,
            createdAt = now,
            updatedAt = now
        )
        profileManager.saveProfile(profile)
    }

    private suspend fun renameProfile(profileId: UUID, newName: String) {
        val existing = profileManager.profiles.value.firstOrNull { it.id == profileId }
            ?: return
        profileManager.saveProfile(existing.copy(name = newName, updatedAt = Instant.now()))
    }
}
