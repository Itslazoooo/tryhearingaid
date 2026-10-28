package com.example.tryhearingaid.emergency

import com.example.tryhearingaid.data.SoundCategory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.Test

class EmergencyAlertTextTest {

    @Test
    fun `every sound category has a readable title`() {
        SoundCategory.entries.forEach { EmergencyAlertText.title(it).shouldNotBeBlank() }
    }

    @Test
    fun `life safety sounds have clear titles`() {
        EmergencyAlertText.title(SoundCategory.SMOKE_ALARM) shouldBe "Fire alarm"
        EmergencyAlertText.title(SoundCategory.CO_ALARM) shouldBe "Carbon monoxide alarm"
        EmergencyAlertText.title(SoundCategory.POLICE_SIREN) shouldBe "Emergency siren"
    }

    @Test
    fun `other categories fall back to a tidy name`() {
        EmergencyAlertText.title(SoundCategory.BABY_CRYING) shouldBe "Baby crying"
    }

    @Test
    fun `confidence is shown as a percentage`() {
        EmergencyAlertText.detail(0.874f) shouldBe "Detected with 87% confidence"
    }
}
