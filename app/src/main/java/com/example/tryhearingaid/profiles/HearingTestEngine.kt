package com.example.tryhearingaid.profiles

import com.example.tryhearingaid.data.Audiogram
import com.example.tryhearingaid.data.HearingProfile
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * State of the in-progress hearing test.
 */
sealed class HearingTestState {
    object Idle : HearingTestState()
    data class InProgress(
        val currentFrequencyHz: Int,
        val currentLevelDbhl: Int,
        val ear: Ear
    ) : HearingTestState()
    data class Complete(
        val audiogram: Audiogram,
        val audiologistRecommended: Boolean
    ) : HearingTestState()
    data class Error(val message: String) : HearingTestState()
}

enum class Ear { LEFT, RIGHT }

/**
 * Manages the audiometric hearing test workflow.
 *
 * The test plays calibrated pure tones at the standard six frequencies
 * ({250, 500, 1000, 2000, 4000, 8000} Hz) from 0 dBHL to 80 dBHL in 5 dB
 * steps.  The user indicates when they can hear a tone; [recordResponse]
 * captures the threshold for that frequency/ear combination.
 */
interface HearingTestEngine {

    /** The six standard audiometric test frequencies in Hz. */
    val testFrequencies: List<Int>

    /** Presentation levels: 0, 5, 10, … 80 dBHL (17 values). */
    val testLevels: List<Int>

    /** Current test state. */
    val testState: StateFlow<HearingTestState>

    /** Whether an audiologist consultation is recommended. */
    val audiologistRecommended: StateFlow<Boolean>

    /** Begin the hearing test sequence. */
    suspend fun startTest(profileId: UUID)

    /**
     * Record that the user heard the tone currently being presented.
     * The engine advances to the next frequency/level automatically.
     */
    suspend fun recordResponse()

    /**
     * Record that the user did NOT hear the tone currently being presented.
     * The engine increases the level to the next step.
     */
    suspend fun recordNoResponse()

    /** Cancel the in-progress test and reset to [HearingTestState.Idle]. */
    suspend fun cancelTest()
}
