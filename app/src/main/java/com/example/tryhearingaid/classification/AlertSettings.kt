package com.example.tryhearingaid.classification

import com.example.tryhearingaid.data.AlertChannel
import com.example.tryhearingaid.data.FlashlightPattern
import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.data.UserPreferences

/**
 * The user's alert choices as typed values, read from the saved [UserPreferences]
 * (Requirements 9.3, 9.4, 9.5).
 *
 * Preferences store enum names as text. This class turns them back into enums and ignores
 * any name it does not recognise (for example one left over from an older app version), so
 * a bad stored value can never crash the audio service.
 *
 * @property categories        Sounds the user wants alerts for.
 * @property channels          Where alerts are delivered.
 * @property flashlightPattern Flash pattern for the flashlight channel.
 */
data class AlertSettings(
    val categories: Set<SoundCategory>,
    val channels: Set<AlertChannel>,
    val flashlightPattern: FlashlightPattern
) {
    /**
     * What the classifier must listen for: the user's categories plus every life-safety sound.
     *
     * Life-safety sounds are always included, so turning a category off in the Alerts screen
     * silences its ordinary alert but can never stop the emergency detector from hearing it
     * (Requirement 10.1, 10.5).
     */
    val classifierCategories: Set<SoundCategory>
        get() = categories + EmergencyDetectorImpl.LIFE_SAFETY_CATEGORIES

    companion object {
        /** Build from saved preferences. Unknown names are dropped; an unknown pattern becomes SHORT. */
        fun from(preferences: UserPreferences): AlertSettings = AlertSettings(
            categories = preferences.enabledSoundCategories.mapNotNull { name ->
                SoundCategory.entries.firstOrNull { it.name == name }
            }.toSet(),
            channels = preferences.alertChannels.mapNotNull { name ->
                AlertChannel.entries.firstOrNull { it.name == name }
            }.toSet(),
            flashlightPattern = FlashlightPattern.entries
                .firstOrNull { it.name == preferences.flashlightPattern }
                ?: FlashlightPattern.SHORT
        )
    }
}
