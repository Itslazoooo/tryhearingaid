package com.example.tryhearingaid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tryhearingaid.data.DataRepository
import com.example.tryhearingaid.data.PreferencesRepository
import com.example.tryhearingaid.profiles.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val ttsEnabled: Boolean = false,
    val cloudAiEnabled: Boolean = false,
    val deleting: Boolean = false,
    val message: String? = null
)

/** Settings: speech output, cloud AI consent and deleting all stored data (Requirements 15.4, 16). */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    private val dataRepository: DataRepository,
    private val profileManager: ProfileManager
) : ViewModel() {

    private val transient = MutableStateFlow(SettingsUiState())

    val uiState: StateFlow<SettingsUiState> =
        kotlinx.coroutines.flow.combine(preferences.userPreferences, transient) { prefs, t ->
            t.copy(ttsEnabled = prefs.ttsEnabled, cloudAiEnabled = prefs.cloudAiEnabled)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTtsEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.updateTtsEnabled(enabled) }
    }

    fun setCloudAiEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.updateCloudAiEnabled(enabled) }
    }

    /** Removes every profile, then everything else the app stores. Fails loudly if a file stays. */
    fun deleteAllData() {
        viewModelScope.launch {
            transient.value = SettingsUiState(deleting = true)
            transient.value = try {
                profileManager.profiles.value.map { it.id }.forEach { profileManager.deleteProfile(it) }
                dataRepository.deleteAllData()
                SettingsUiState(message = "All your data was deleted.")
            } catch (e: Exception) {
                SettingsUiState(message = "Some data could not be deleted: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun dismissMessage() {
        transient.value = transient.value.copy(message = null)
    }
}
