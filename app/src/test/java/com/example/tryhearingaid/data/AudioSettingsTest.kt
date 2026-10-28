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
            AudioSettings(sampleRate = rate).frameSize * 100 shouldBe rate // balanced = 10 ms frames
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

    @Test
    fun `raw audio is the default`() {
        AudioSettings().rawMode shouldBe true
        AudioSettings().toAudioConfig().rawMode shouldBe true
    }

    @Test
    fun `lower delay modes use smaller frames, fewer queued frames and smaller buffers`() {
        val modes = LatencyMode.entries
        modes.zipWithNext().forEach { (a, b) ->
            (a.estimatedDelayMs < b.estimatedDelayMs) shouldBe true
        }
        (LatencyMode.LOWEST.estimatedDelayMs < LatencyMode.SMOOTHEST.estimatedDelayMs) shouldBe true
        LatencyMode.LOWEST.prefillFrames shouldBe 0
    }

    @Test
    fun `every sample rate and delay mode gives a whole number of samples divisible for 16 kHz reduction`() {
        AudioSettings.SAMPLE_RATES.forEach { rate ->
            LatencyMode.entries.forEach { mode ->
                val frame = AudioSettings(sampleRate = rate, latency = mode).frameSize
                (frame * 1000) shouldBe (rate * mode.frameMs)
                (frame % (rate / 16_000)) shouldBe 0
            }
        }
    }

    @Test
    fun `changing delay or raw mode changes the engine settings so a running engine restarts`() {
        val base = AudioSettings()
        (base.toAudioConfig() == base.copy(latency = LatencyMode.LOW).toAudioConfig()) shouldBe false
        (base.toAudioConfig() == base.copy(rawMode = false).toAudioConfig()) shouldBe false
        (base.toAudioConfig() == base.copy(noiseStrength = 0.9f).toAudioConfig()) shouldBe true
    }
}
