package com.example.tryhearingaid.data

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AudioSettingsTest {

    @Test
    fun `an unsupported sample rate and out of range strengths are corrected`() {
        val s = AudioSettings(sampleRate = 22_050, noiseStrength = 3f, voiceFocusStrength = -1f).sanitized()
        s.sampleRate shouldBe AudioSettings.DEFAULT_SAMPLE_RATE
        s.noiseStrength shouldBe 1f
        s.voiceFocusStrength shouldBe 0f
    }

    @Test
    fun `every offered sample rate is a multiple of 16 kHz and a 10 ms frame is a whole number`() {
        AudioSettings.SAMPLE_RATES.forEach { rate ->
            (rate % 16_000) shouldBe 0
            AudioSettings(sampleRate = rate).frameSize * 100 shouldBe rate
        }
    }

    @Test
    fun `default keeps the full range with voice focus off`() {
        val s = AudioSettings()
        s.sampleRate shouldBe 48_000
        s.voiceFocusEnabled shouldBe false
    }

    @Test
    fun `settings are saved and cleaned`() = runTest {
        val prefs = InMemoryPreferencesRepository()
        prefs.updateAudioSettings(
            AudioSettings(sampleRate = 32_000, noisePreset = EnvironmentPreset.RESTAURANT, noiseStrength = 0.8f)
        )
        val saved = prefs.userPreferences.first().audioSettings
        saved.sampleRate shouldBe 32_000
        saved.noisePreset shouldBe EnvironmentPreset.RESTAURANT
        saved.noiseStrength shouldBe 0.8f
    }
}
