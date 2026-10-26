package com.example.tryhearingaid.audio

import com.example.tryhearingaid.audio.AudioConfig
import com.example.tryhearingaid.audio.AudioEngineState
import com.example.tryhearingaid.audio.AudioProcessingStage
import com.example.tryhearingaid.data.Audiogram
import com.example.tryhearingaid.data.AudiogramEntity
import com.example.tryhearingaid.data.EqBand
import com.example.tryhearingaid.data.EqBandEntity
import com.example.tryhearingaid.data.EnvironmentPreset
import com.example.tryhearingaid.data.HearingProfile
import com.example.tryhearingaid.data.HearingProfileEntity
import com.example.tryhearingaid.data.InMemoryPreferencesRepository
import com.example.tryhearingaid.data.ProfileDao
import com.example.tryhearingaid.profiles.ProfileManagerImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/**
 * Property-based tests for [EqualizerImpl].
 *
 * Feature: advanced-hearing-aid-app
 * Validates:
 * - Requirements 5.1, 5.2, 5.3, 5.4, 12.3, 12.4
 * - Requirements 6.1, 6.3, 6.4
 */
@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class EqualizerPropertyTest : FunSpec({

    /**
     * Property 3: Equalizer always has at least 8 bands in required frequency range.
     *
     * Validates: Requirements 5.1, 5.2
     */
    test("Property 3: Equalizer always has at least 8 bands in required frequency range") {
        // Run 100 times since this is a deterministic property (no random input needed)
        repeat(100) {
            val equalizer = EqualizerImpl()
            val bands = equalizer.bands.value

            // Feature: advanced-hearing-aid-app, Property 3: Equalizer always has at least 8 bands in required frequency range
            assert(bands.size >= 8) { "Expected at least 8 bands, got ${bands.size}" }

            // Verify all center frequencies are in the expected range
            bands.forEach { band ->
                assert(band.centerHz in 250..8000) {
                    "Center frequency ${band.centerHz} is outside range [250, 8000]"
                }
            }

            // Verify all required frequencies are present
            val requiredFreqs = setOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)
            val actualFreqs = bands.map { it.centerHz }.toSet()
            assert(requiredFreqs.all { it in actualFreqs }) {
                "Missing required frequencies. Required: $requiredFreqs, Actual: $actualFreqs"
            }
        }
    }

    /**
     * Property 4: Equalizer band gain is always within valid range.
     *
     * Generates random gain values in a wide range [-200, +200] dB and verifies
     * that after being set, the applied gain is always within [-20, +40] dB.
     *
     * Validates: Requirements 5.3, 5.4
     */
    test("Property 4: Equalizer band gain is always within valid range") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.float(-200f, 200f),
            Arb.int(0..7)  // band index
        ) { requestedGain, bandIndex ->
            val equalizer = EqualizerImpl()
            equalizer.setBandGain(bandIndex, requestedGain)

            val appliedGain = equalizer.bands.value[bandIndex].gainDb

            // Feature: advanced-hearing-aid-app, Property 4: Equalizer band gain is always within valid range
            assert(appliedGain >= EqualizerImpl.GAIN_MIN_DB) {
                "Applied gain $appliedGain is below min ${EqualizerImpl.GAIN_MIN_DB} for requested $requestedGain"
            }
            assert(appliedGain <= EqualizerImpl.GAIN_MAX_DB) {
                "Applied gain $appliedGain is above max ${EqualizerImpl.GAIN_MAX_DB} for requested $requestedGain"
            }
        }
    }

    /**
     * Property 5: Equalizer profile round-trip persistence.
     *
     * Generates a random 8-band EQ configuration, saves it via [ProfileManagerImpl],
     * retrieves the profile, and verifies that the loaded band gains are identical
     * to what was saved.
     *
     * Validates: Requirements 5.3, 12.3, 12.4
     */
    test("Property 5: Equalizer profile round-trip persistence") {
        // Feature: advanced-hearing-aid-app, Property 5: Equalizer profile round-trip persistence
        checkAll(
            PropTestConfig(iterations = 50),
            Arb.list(Arb.float(-20f, 40f), 8..8)
        ) { randomGains: List<Float> ->
            // Clamp gains to the valid EQ range (mirrors what EqualizerImpl.setBandGain does)
            val clampedGains = randomGains.map { it.coerceIn(EqualizerImpl.GAIN_MIN_DB, EqualizerImpl.GAIN_MAX_DB) }

            val freqs = EqualizerImpl.CENTER_FREQUENCIES_HZ
            val eqBands = freqs.mapIndexed { i, hz -> EqBand(hz, clampedGains[i]) }

            val now = Instant.now()
            val profile = HearingProfile(
                id = UUID.randomUUID(),
                name = "RoundTripProfile",
                eqBands = eqBands,
                adaptiveAmplificationEnabled = false,
                noiseReductionPreset = EnvironmentPreset.DEFAULT,
                audiogram = null,
                createdAt = now,
                updatedAt = now
            )

            // Save via ProfileManager (backed by in-memory DAO)
            val dao = FakeProfileDao()
            val prefs = InMemoryPreferencesRepository()
            val equalizer = EqualizerImpl()
            val audioEngine = FakeAudioEngine()
            val manager = ProfileManagerImpl(dao, prefs, equalizer, audioEngine)

            runTest {
                manager.saveProfile(profile)

                // Load: retrieve from ProfileManager's in-memory state
                val loadedProfile = manager.profiles.value.firstOrNull { it.id == profile.id }

                assert(loadedProfile != null) { "Profile not found after save" }
                assert(loadedProfile!!.eqBands.size == 8) {
                    "Retrieved profile should have 8 bands, got ${loadedProfile.eqBands.size}"
                }

                // Verify every band gain is identical to what was saved
                loadedProfile.eqBands.forEachIndexed { i, band ->
                    assert(abs(band.gainDb - eqBands[i].gainDb) < 0.01f) {
                        "Band $i gain mismatch: saved ${eqBands[i].gainDb}, retrieved ${band.gainDb}"
                    }
                }

                // Also verify the DAO persisted the same gains
                val persisted = dao.getEqBandsForProfileSnapshot(profile.id)
                assert(persisted.size == 8) {
                    "DAO should have 8 persisted bands, got ${persisted.size}"
                }
                persisted.sortedBy { it.bandIndex }.forEachIndexed { i, entity ->
                    assert(abs(entity.gainDb - eqBands[i].gainDb) < 0.01f) {
                        "DAO band $i gain mismatch: saved ${eqBands[i].gainDb}, persisted ${entity.gainDb}"
                    }
                }
            }
        }
    }

    /**
     * Property 6: Audiogram maps to proportional EQ gains.
     *
     * Generates random audiogram with varying thresholds and verifies that:
     * 1. Bands with higher thresholds get higher (or equal) gains
     * 2. Gains are proportional to thresholds (linear mapping: threshold * 0.5)
     *
     * Validates: Requirements 5.1, 5.2, 5.3, 5.4, 12.1, 12.2, 12.3, 12.4
     */
    test("Property 6: Audiogram maps to proportional EQ gains") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.list(Arb.int(0, 80), 8..8)
        ) { thresholds: List<Int> ->
            val equalizer = EqualizerImpl()

            // Build audiogram from thresholds
            val freqs = intArrayOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)
            val leftEar: Map<Int, Int> = freqs.zip(thresholds).toMap()

            val audiogram = Audiogram(
                profileId = UUID.randomUUID(),
                leftEar = leftEar,
                rightEar = emptyMap(),
                recordedAt = Instant.now()
            )

            equalizer.applyAudiogram(audiogram)
            val bands = equalizer.bands.value

            // Feature: advanced-hearing-aid-app, Property 6: Audiogram maps to proportional EQ gains
            // Verify proportionality: gain = threshold * 0.5 (clamped to [-20, 40])
            thresholds.forEachIndexed { i: Int, threshold: Int ->
                val expectedGain = (threshold * 0.5f).coerceIn(-20f, 40f)
                val actualGain = bands[i].gainDb

                assert(
                    abs(actualGain - expectedGain) < 0.01f
                ) {
                    "Band $i (${freqs[i]} Hz) threshold $threshold dBHL should map to " +
                            "$expectedGain dB (got $actualGain)"
                }
            }

            // Verify monotonicity: higher thresholds → higher or equal gains
            for (i in 0 until thresholds.size - 1) {
                if (thresholds[i] <= thresholds[i + 1]) {
                    assert(
                        bands[i].gainDb <= bands[i + 1].gainDb + 0.01f
                    ) {
                        "Band gain should be monotonic: " +
                                "threshold[${i}]=${thresholds[i]} → gain=${bands[i].gainDb}, " +
                                "threshold[${i + 1}]=${thresholds[i + 1]} → gain=${bands[i + 1].gainDb}"
                    }
                }
            }
        }
    }

    /**
     * Property 6b: Audiogram with mixed left/right ears produces averaged gains.
     *
     * Validates that when both ears have thresholds for a frequency, the
     * gain is computed from their average.
     *
     * Validates: Requirements 5.3, 5.4, 12.3, 12.4
     */
    test("Property 6b: Audiogram with mixed left/right ears produces averaged gains") {
        checkAll(
            PropTestConfig(iterations = 50),
            Arb.list(Arb.int(0, 80), 8..8),
            Arb.list(Arb.int(0, 80), 8..8)
        ) { leftThresholds: List<Int>, rightThresholds: List<Int> ->
            val equalizer = EqualizerImpl()

            val freqs = intArrayOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)
            val leftEar: Map<Int, Int> = freqs.zip(leftThresholds).toMap()
            val rightEar: Map<Int, Int> = freqs.zip(rightThresholds).toMap()

            val audiogram = Audiogram(
                profileId = UUID.randomUUID(),
                leftEar = leftEar,
                rightEar = rightEar,
                recordedAt = Instant.now()
            )

            equalizer.applyAudiogram(audiogram)
            val bands = equalizer.bands.value

            // Feature: advanced-hearing-aid-app, Property 6b: Mixed left/right ears averaging
            leftThresholds.forEachIndexed { i: Int, _: Int ->
                val avgThreshold = (leftThresholds[i] + rightThresholds[i]) / 2f
                val expectedGain = (avgThreshold * 0.5f).coerceIn(-20f, 40f)
                val actualGain = bands[i].gainDb

                assert(
                    abs(actualGain - expectedGain) < 0.01f
                ) {
                    "Band $i should average ears: " +
                            "left=${leftThresholds[i]}, right=${rightThresholds[i]}, " +
                            "avg=$avgThreshold → expected $expectedGain, got $actualGain"
                }
            }
        }
    }
})

// ── Fakes used by Property 5 ──────────────────────────────────────────────────

private class FakeProfileDao : ProfileDao {
    val profileStore = mutableMapOf<UUID, HearingProfileEntity>()
    val bandStore = mutableMapOf<UUID, MutableList<EqBandEntity>>()
    val audiogramStore = mutableMapOf<UUID, MutableList<AudiogramEntity>>()
    private val flow = MutableStateFlow<List<HearingProfileEntity>>(emptyList())

    override fun getAllProfiles(): Flow<List<HearingProfileEntity>> = flow
    override fun getProfileById(id: UUID) = flowOf(profileStore[id])
    override suspend fun getProfileByIdSnapshot(id: UUID) = profileStore[id]

    override suspend fun insertProfile(profile: HearingProfileEntity) {
        profileStore[profile.id] = profile
        flow.value = profileStore.values.toList()
    }

    override suspend fun updateProfile(profile: HearingProfileEntity) {
        profileStore[profile.id] = profile
        flow.value = profileStore.values.toList()
    }

    override suspend fun deleteProfile(id: UUID) {
        profileStore.remove(id)
        flow.value = profileStore.values.toList()
    }

    override suspend fun deleteAllProfiles() {
        profileStore.clear()
        flow.value = emptyList()
    }

    override suspend fun insertEqBands(bands: List<EqBandEntity>) {
        bands.forEach { band ->
            bandStore.getOrPut(band.profileId) { mutableListOf() }.add(band)
        }
    }

    override fun getEqBandsForProfile(profileId: UUID): Flow<List<EqBandEntity>> =
        flowOf(bandStore[profileId]?.toList() ?: emptyList())

    override suspend fun getEqBandsForProfileSnapshot(profileId: UUID): List<EqBandEntity> =
        bandStore[profileId]?.toList() ?: emptyList()

    override suspend fun deleteEqBandsForProfile(profileId: UUID) {
        bandStore.remove(profileId)
    }

    override suspend fun insertAudiogramEntry(entry: AudiogramEntity) {
        audiogramStore.getOrPut(entry.profileId) { mutableListOf() }.add(entry)
    }

    override suspend fun insertAudiogramRows(rows: List<AudiogramEntity>) {
        rows.forEach { row ->
            audiogramStore.getOrPut(row.profileId) { mutableListOf() }.add(row)
        }
    }

    override fun getAudiogramForProfile(profileId: UUID): Flow<List<AudiogramEntity>> =
        flowOf(audiogramStore[profileId]?.toList() ?: emptyList())

    override suspend fun getAudiogramForProfileSnapshot(profileId: UUID): List<AudiogramEntity> =
        audiogramStore[profileId]?.toList() ?: emptyList()

    override suspend fun deleteAudiogramForProfile(profileId: UUID) {
        audiogramStore.remove(profileId)
    }
}

private class FakeAudioEngine : AudioEngine {
    private val _state = MutableStateFlow(AudioEngineState.IDLE)
    override val state: StateFlow<AudioEngineState> = _state.asStateFlow()
    override var currentGain: Float = 0f
    override fun start(config: AudioConfig) { _state.value = AudioEngineState.RUNNING }
    override fun stop() { _state.value = AudioEngineState.IDLE }
    override fun setGain(dB: Float) { currentGain = dB.coerceIn(0f, 40f) }
    override fun setAdaptiveMode(enabled: Boolean) {}
    override fun addProcessingStage(stage: AudioProcessingStage) {}
    override fun getCurrentSplDb(): Float = 0f
    override fun routeOutput(device: AudioDevice) { /* no-op for tests */ }
}
