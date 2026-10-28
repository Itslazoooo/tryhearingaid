package com.example.tryhearingaid.classification

import com.example.tryhearingaid.data.AlertChannel
import com.example.tryhearingaid.data.FlashlightPattern
import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.data.UserPreferences
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Unit tests for [AlertSettings] (Requirements 9.3, 9.4, 9.5, 10.1). */
class AlertSettingsTest {

    @Test
    fun `default preferences enable every category and channel with the short flash`() {
        val settings = AlertSettings.from(UserPreferences())

        settings.categories shouldBe SoundCategory.entries.toSet()
        settings.channels shouldBe AlertChannel.entries.toSet()
        settings.flashlightPattern shouldBe FlashlightPattern.SHORT
    }

    @Test
    fun `saved names are turned back into the matching values`() {
        val prefs = UserPreferences(
            enabledSoundCategories = setOf("DOORBELL", "KNOCK"),
            alertChannels = setOf("VIBRATION"),
            flashlightPattern = "SOS"
        )

        val settings = AlertSettings.from(prefs)

        settings.categories shouldBe setOf(SoundCategory.DOORBELL, SoundCategory.KNOCK)
        settings.channels shouldBe setOf(AlertChannel.VIBRATION)
        settings.flashlightPattern shouldBe FlashlightPattern.SOS
    }

    @Test
    fun `names that are not recognised are ignored instead of failing`() {
        val prefs = UserPreferences(
            enabledSoundCategories = setOf("DOORBELL", "NOT_A_SOUND"),
            alertChannels = setOf("CARRIER_PIGEON"),
            flashlightPattern = "DISCO"
        )

        val settings = AlertSettings.from(prefs)

        settings.categories shouldBe setOf(SoundCategory.DOORBELL)
        settings.channels shouldBe emptySet<AlertChannel>()
        settings.flashlightPattern shouldBe FlashlightPattern.SHORT
    }

    @Test
    fun `the classifier always listens for life-safety sounds even if the user turned them off`() {
        val settings = AlertSettings.from(UserPreferences(enabledSoundCategories = emptySet()))

        settings.categories shouldBe emptySet<SoundCategory>()
        settings.classifierCategories.shouldContainAll(EmergencyDetectorImpl.LIFE_SAFETY_CATEGORIES)
    }

    @Test
    fun `the classifier also listens for the categories the user enabled`() {
        val settings = AlertSettings.from(UserPreferences(enabledSoundCategories = setOf("DOORBELL")))

        settings.classifierCategories.shouldContainAll(
            setOf(SoundCategory.DOORBELL) + EmergencyDetectorImpl.LIFE_SAFETY_CATEGORIES
        )
        (SoundCategory.BIRDS in settings.classifierCategories) shouldBe false
    }
}
