package com.example.tryhearingaid.profiles

import com.example.tryhearingaid.audio.Equalizer
import com.example.tryhearingaid.data.Audiogram
import com.example.tryhearingaid.data.EqBand
import com.example.tryhearingaid.data.EnvironmentPreset
import com.example.tryhearingaid.data.HearingProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.UUID

/**
 * Concrete implementation of [HearingTestEngine].
 *
 * Tone generation note: in a full implementation the engine drives
 * [AudioEngine] to play calibrated sine-wave tones. For unit / property
 * testing purposes the tone-playback side-effect is abstracted behind
 * [TonePlayer], which has a no-op default implementation so tests remain fast
 * and device-free.
 */
class HearingTestEngineImpl(
    private val profileManager: ProfileManager,
    private val equalizer: Equalizer,
    private val tonePlayer: TonePlayer = NoOpTonePlayer()
) : HearingTestEngine {

    // ── Constants ─────────────────────────────────────────────────────────────

    override val testFrequencies: List<Int> = listOf(250, 500, 1000, 2000, 4000, 8000)

    /** 0, 5, 10, … 80 dBHL → 17 levels */
    override val testLevels: List<Int> = (0..80 step 5).toList()

    // ── State ─────────────────────────────────────────────────────────────────

    private val _testState = MutableStateFlow<HearingTestState>(HearingTestState.Idle)
    override val testState: StateFlow<HearingTestState> = _testState.asStateFlow()

    private val _audiologistRecommended = MutableStateFlow(false)
    override val audiologistRecommended: StateFlow<Boolean> = _audiologistRecommended.asStateFlow()

    // ── Internal mutable test session ─────────────────────────────────────────

    private var currentProfileId: UUID = UUID.randomUUID()

    /** Thresholds recorded so far: ear → (frequencyHz → thresholdDbl) */
    private val leftThresholds = mutableMapOf<Int, Int>()
    private val rightThresholds = mutableMapOf<Int, Int>()

    /** Index into [testFrequencies] for the current frequency. */
    private var freqIndex = 0

    /** Index into [testLevels] for the current presentation level. */
    private var levelIndex = 0

    /** Which ear is currently being tested. */
    private var currentEar = Ear.LEFT

    // ── HearingTestEngine implementation ──────────────────────────────────────

    override suspend fun startTest(profileId: UUID) {
        currentProfileId = profileId
        leftThresholds.clear()
        rightThresholds.clear()
        freqIndex = 0
        levelIndex = 0
        currentEar = Ear.LEFT
        _audiologistRecommended.value = false
        presentCurrentTone()
    }

    override suspend fun recordResponse() {
        // The user can hear at the current level → this IS the threshold for this frequency/ear
        val frequency = testFrequencies[freqIndex]
        val threshold = testLevels[levelIndex]

        when (currentEar) {
            Ear.LEFT -> leftThresholds[frequency] = threshold
            Ear.RIGHT -> rightThresholds[frequency] = threshold
        }

        advance()
    }

    override suspend fun recordNoResponse() {
        // Increase presentation level
        val nextLevel = levelIndex + 1
        if (nextLevel >= testLevels.size) {
            // Maximum level reached without response — treat max (80 dBHL) as threshold
            recordResponseAtMaxLevel()
        } else {
            levelIndex = nextLevel
            presentCurrentTone()
        }
    }

    override suspend fun cancelTest() {
        _testState.value = HearingTestState.Idle
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** The user still cannot hear at 80 dBHL — record 80 dBHL as the threshold. */
    private suspend fun recordResponseAtMaxLevel() {
        val frequency = testFrequencies[freqIndex]
        when (currentEar) {
            Ear.LEFT -> leftThresholds[frequency] = testLevels.last()
            Ear.RIGHT -> rightThresholds[frequency] = testLevels.last()
        }
        advance()
    }

    /** Move to the next frequency/ear, or finish the test if all done. */
    private suspend fun advance() {
        freqIndex++
        levelIndex = 0 // restart at 0 dBHL for each new frequency

        if (freqIndex >= testFrequencies.size) {
            // Done with this ear
            if (currentEar == Ear.LEFT) {
                currentEar = Ear.RIGHT
                freqIndex = 0
                presentCurrentTone()
            } else {
                // Both ears complete → finalise
                finishTest()
            }
        } else {
            presentCurrentTone()
        }
    }

    private fun presentCurrentTone() {
        val freq = testFrequencies[freqIndex]
        val level = testLevels[levelIndex]
        tonePlayer.play(freq, level)
        _testState.value = HearingTestState.InProgress(freq, level, currentEar)
    }

    private suspend fun finishTest() {
        val now = Instant.now()

        val audiogram = Audiogram(
            profileId = currentProfileId,
            leftEar = leftThresholds.toMap(),
            rightEar = rightThresholds.toMap(),
            recordedAt = now
        )

        // Check audiologist recommendation: any threshold > 40 dBHL
        val allThresholds = leftThresholds.values + rightThresholds.values
        val recommend = allThresholds.any { it > 40 }
        _audiologistRecommended.value = recommend

        // Default EQ bands — the actual equalizer implementation fills them from audiogram
        val defaultBands = listOf(
            EqBand(250, 0f), EqBand(500, 0f), EqBand(1000, 0f), EqBand(2000, 0f),
            EqBand(3000, 0f), EqBand(4000, 0f), EqBand(6000, 0f), EqBand(8000, 0f)
        )

        val profile = HearingProfile(
            id = currentProfileId,
            name = "Hearing Test ${now.epochSecond}",
            eqBands = defaultBands,
            adaptiveAmplificationEnabled = true,
            noiseReductionPreset = EnvironmentPreset.DEFAULT,
            audiogram = audiogram,
            createdAt = now,
            updatedAt = now
        )

        // Persist the profile (saves audiogram as well)
        profileManager.saveProfile(profile)

        // Apply audiogram to equalizer
        equalizer.applyAudiogram(audiogram)

        _testState.value = HearingTestState.Complete(audiogram, recommend)
    }
}

// ─── TonePlayer abstraction ───────────────────────────────────────────────────

/**
 * Plays a calibrated pure tone at [frequencyHz] at the specified [levelDbhl].
 * A no-op implementation is used in tests; the real implementation drives
 * [AudioEngine] to produce a sine wave at the requested parameters.
 */
interface TonePlayer {
    fun play(frequencyHz: Int, levelDbhl: Int)
    fun stop()
}

class NoOpTonePlayer : TonePlayer {
    override fun play(frequencyHz: Int, levelDbhl: Int) { /* no-op for testing */ }
    override fun stop() { /* no-op */ }
}
