package com.example.tryhearingaid.profiles

import com.example.tryhearingaid.data.HearingProfile
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

interface ProfileManager {
    val profiles: StateFlow<List<HearingProfile>>
    val activeProfile: StateFlow<HearingProfile?>

    /**
     * Persist [profile] (and its EQ bands / audiogram) to the database.
     * If a profile with the same ID already exists it is updated; otherwise a new record is created.
     */
    suspend fun saveProfile(profile: HearingProfile)

    /**
     * Set [profileId] as the active profile:
     * 1. Persists the selection to DataStore.
     * 2. Pushes the profile's EQ bands to the Equalizer.
     * 3. Applies adaptive-amplification setting to AudioEngine.
     * All three steps complete within 200 ms.
     */
    suspend fun activateProfile(profileId: UUID)

    /**
     * Remove [profileId] and all child EQ-band / audiogram rows from the database.
     * If the deleted profile was active the active profile is cleared.
     */
    suspend fun deleteProfile(profileId: UUID)
}
