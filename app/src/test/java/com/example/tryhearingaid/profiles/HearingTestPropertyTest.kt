package com.example.tryhearingaid.profiles

import com.example.tryhearingaid.audio.Equalizer
import com.example.tryhearingaid.data.Audiogram
import com.example.tryhearingaid.data.EqBand
import com.example.tryhearingaid.data.HearingProfile
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

// ─── Shared fakes for property tests ─────────────────────────────────────────

private class PropFakeProfileManager : ProfileManager {
    private val _profiles = MutableStateFlow<List<HearingProfile>>(emptyList())
    override val profiles: StateFlow<List<HearingProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow<HearingProfile?>(null)
    override val activeProfile: StateFlow<HearingProfile?> = _activeProfile.asStateFlow()

    val savedProfiles = mutableListOf<HearingProfile>()

    override suspend fun saveProfile(profile: HearingProfile) {
        savedProfiles.add(profile)
        _profiles.value = savedProfiles.toList()
    }

    override suspend fun activateProfile(profileId: UUID) {}
    override suspend fun deleteProfile(profileId: UUID) {}
}

private class PropFakeEqualizer : Equalizer {
    private val _bands = MutableStateFlow(
        listOf(
            EqBand(250, 0f), EqBand(500, 0f), EqBand(1000, 0f), EqBand(2000, 0f),
            EqBand(3000, 0f), EqBand(4000, 0f), EqBand(6000, 0f), EqBand(8000, 0f)
        )
    )
    override val bands: StateFlow<List<EqBand>> = _bands.asStateFlow()
    var lastAudiogram: Audiogram? = null

    override fun setBandGain(bandIndex: Int, gainDb: Float) {}
    override fun applyAudiogram(audiogram: Audiogram) { lastAudiogram = audiogram }
    override fun reset() {}
    override fun process(buffer: ShortArray) = buffer
    override fun process(buffer: ShortArray, sampleRate: Int, channelCount: Int) = buffer
    private var _isEnabled: Boolean = true
    override val isEnabled: Boolean get() = _isEnabled
    override fun setEnabled(enabled: Boolean) { _isEnabled = enabled }
}

// ─── Property 21 ──────────────────────────────────────────────────────────────
// Feature: advanced-hearing-aid-app, Property 21: Hearing test frequencies and level steps are correct

@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class HearingTestFrequenciesPropertyTest : FreeSpec({

    "Property 21: Hearing test frequencies and level steps are correct" {
        // Feature: advanced-hearing-aid-app, Property 21: Hearing test frequencies and level steps are correct

        // A HearingTestEngineImpl must always expose the exact 6 standard audiometric
        // frequencies and the standard 17 presentation levels (0..80 in 5 dB steps)
        // regardless of how it is constructed or which profile ID is used.

        val expectedFrequencies = listOf(250, 500, 1000, 2000, 4000, 8000)
        val expectedLevels = (0..80 step 5).toList() // [0, 5, 10, …, 80] — 17 levels

        checkAll(
            PropTestConfig(iterations = 100),
            Arb.int(1, 1000) // arbitrary "seed" — frequencies/levels are invariant
        ) { _ ->
            val profileManager = PropFakeProfileManager()
            val equalizer = PropFakeEqualizer()
            val engine = HearingTestEngineImpl(profileManager, equalizer)

            // Frequencies
            engine.testFrequencies shouldBe expectedFrequencies
            engine.testFrequencies shouldHaveSize 6

            // Levels
            engine.testLevels shouldBe expectedLevels
            engine.testLevels shouldHaveSize 17
            engine.testLevels.first() shouldBe 0
            engine.testLevels.last() shouldBe 80

            // Consecutive levels differ by exactly 5 dBHL
            engine.testLevels.zipWithNext().forEach { (a, b) ->
                (b - a) shouldBe 5
            }
        }
    }
})

// ─── Property 22 ──────────────────────────────────────────────────────────────
// Feature: advanced-hearing-aid-app, Property 22: Hearing test threshold round-trip

@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class HearingTestThresholdRoundTripPropertyTest : FreeSpec({

    "Property 22: Hearing test threshold round-trip" {
        // Feature: advanced-hearing-aid-app, Property 22: Hearing test threshold round-trip
        // Arb.int(0, 16): index × 5 = dBHL; record threshold; retrieve audiogram; assert same value.

        // We test a single frequency (first frequency, left ear) to keep the test
        // focused. The engine advances automatically after each response, so we
        // skip the rest with immediate responses.

        checkAll(
            PropTestConfig(iterations = 100),
            Arb.int(0, 16) // level index → 0..80 dBHL in steps of 5
        ) { levelIndex ->
            val targetDbhl = levelIndex * 5  // e.g. index 5 → 25 dBHL

            val profileManager = PropFakeProfileManager()
            val equalizer = PropFakeEqualizer()
            val engine = HearingTestEngineImpl(profileManager, equalizer)

            val profileId = UUID.randomUUID()
            engine.startTest(profileId)

            // Advance to the target level by recording no-responses
            repeat(levelIndex) { engine.recordNoResponse() }

            // Record the response at the target level (this is the threshold for freq[0], LEFT ear)
            engine.recordResponse()

            // Immediately respond to all subsequent frequencies/levels with "heard at 0 dBHL"
            // so the test completes (5 remaining left-ear freqs + 6 right-ear freqs = 11 responses)
            repeat(11) { engine.recordResponse() }

            // Test must be complete
            val state = engine.testState.value
            state shouldNotBe null
            (state is HearingTestState.Complete).shouldBeTrue()

            val audiogram = (state as HearingTestState.Complete).audiogram

            // The threshold for the first frequency (250 Hz) on the LEFT ear must equal targetDbhl
            val recordedThreshold = audiogram.leftEar[250]
            recordedThreshold shouldBe targetDbhl
        }
    }
})

// ─── Property 23 ──────────────────────────────────────────────────────────────
// Feature: advanced-hearing-aid-app, Property 23: Profiles with threshold > 40 dBHL set audiologist recommendation

@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class AudiologistRecommendationPropertyTest : FreeSpec({

    "Property 23: Profiles with threshold > 40 dBHL set audiologist recommendation" {
        // Feature: advanced-hearing-aid-app, Property 23: Profiles with threshold > 40 dBHL set audiologist recommendation
        // Arb.int(41, 80): any threshold > 40 dBHL for one frequency → audiologistRecommended == true.

        checkAll(
            PropTestConfig(iterations = 100),
            Arb.int(41, 80) // threshold > 40 dBHL (must be a multiple of 5 to be reachable)
        ) { rawThreshold ->
            // Round up to the nearest 5-dB step (the engine only records level multiples of 5)
            val threshold = ((rawThreshold + 4) / 5) * 5  // e.g. 41 → 45, 45 → 45, 80 → 80
            val levelIndex = threshold / 5                 // level index: 45/5 = 9, 80/5 = 16

            val profileManager = PropFakeProfileManager()
            val equalizer = PropFakeEqualizer()
            val engine = HearingTestEngineImpl(profileManager, equalizer)

            val profileId = UUID.randomUUID()
            engine.startTest(profileId)

            // Advance to the threshold level for the FIRST frequency (250 Hz, LEFT ear)
            repeat(levelIndex) { engine.recordNoResponse() }
            engine.recordResponse() // heard at 'threshold' dBHL

            // Complete remaining frequencies with 0 dBHL thresholds
            repeat(11) { engine.recordResponse() }

            // After test completion, audiologistRecommended must be true
            engine.audiologistRecommended.value.shouldBeTrue()

            val state = engine.testState.value
            (state is HearingTestState.Complete).shouldBeTrue()
            (state as HearingTestState.Complete).audiologistRecommended.shouldBeTrue()
        }
    }
})
