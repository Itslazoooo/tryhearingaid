package com.example.tryhearingaid.profiles

import com.example.tryhearingaid.audio.AudioEngine
import com.example.tryhearingaid.audio.AudioConfig
import com.example.tryhearingaid.audio.AudioDevice
import com.example.tryhearingaid.audio.AudioEngineState
import com.example.tryhearingaid.audio.AudioProcessingStage
import com.example.tryhearingaid.audio.Equalizer
import com.example.tryhearingaid.data.Audiogram
import com.example.tryhearingaid.data.AudiogramEntity
import com.example.tryhearingaid.data.EqBand
import com.example.tryhearingaid.data.EqBandEntity
import com.example.tryhearingaid.data.EnvironmentPreset
import com.example.tryhearingaid.data.HearingProfile
import com.example.tryhearingaid.data.HearingProfileEntity
import com.example.tryhearingaid.data.InMemoryPreferencesRepository
import com.example.tryhearingaid.data.ProfileDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileManagerTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    /** Simple in-memory ProfileDao for unit tests. */
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

    /** Fake Equalizer that records band gain calls. */
    private class FakeEqualizer : Equalizer {
        private val _bands = MutableStateFlow<List<EqBand>>(
            listOf(
                EqBand(250, 0f), EqBand(500, 0f), EqBand(1000, 0f), EqBand(2000, 0f),
                EqBand(3000, 0f), EqBand(4000, 0f), EqBand(6000, 0f), EqBand(8000, 0f)
            )
        )
        override val bands: StateFlow<List<EqBand>> = _bands.asStateFlow()

        val appliedGains = mutableListOf<Pair<Int, Float>>() // bandIndex -> gain
        var audiogramApplied: Audiogram? = null

        override fun setBandGain(bandIndex: Int, gainDb: Float) {
            appliedGains.add(bandIndex to gainDb)
            val updated = _bands.value.toMutableList()
            if (bandIndex in updated.indices) {
                updated[bandIndex] = updated[bandIndex].copy(gainDb = gainDb.coerceIn(-20f, 40f))
                _bands.value = updated
            }
        }

        override fun applyAudiogram(audiogram: Audiogram) {
            audiogramApplied = audiogram
        }

        override fun reset() {
            _bands.value = _bands.value.map { it.copy(gainDb = 0f) }
        }

        private var _isEnabled: Boolean = true
        override val isEnabled: Boolean get() = _isEnabled
        override fun process(buffer: ShortArray, sampleRate: Int, channelCount: Int): ShortArray = buffer
        override fun process(buffer: ShortArray): ShortArray = buffer
        override fun setEnabled(enabled: Boolean) { _isEnabled = enabled }
    }

    /** Fake AudioEngine that records adaptive mode changes. */
    private class FakeAudioEngine : AudioEngine {
        private val _state = MutableStateFlow(AudioEngineState.IDLE)
        override val state: StateFlow<AudioEngineState> = _state.asStateFlow()
        override var currentGain: Float = 0f

        var adaptiveModeEnabled = false

        override fun start(config: AudioConfig) { _state.value = AudioEngineState.RUNNING }
        override fun stop() { _state.value = AudioEngineState.IDLE }
        override fun setGain(dB: Float) { currentGain = dB.coerceIn(0f, 40f) }
        override fun setAdaptiveMode(enabled: Boolean) { adaptiveModeEnabled = enabled }
        override fun addProcessingStage(stage: AudioProcessingStage) {}
        override fun getCurrentSplDb(): Float = 0f
        override fun routeOutput(device: AudioDevice) { /* no-op for tests */ }
    }

    // ── Test fixtures ─────────────────────────────────────────────────────────

    private lateinit var dao: FakeProfileDao
    private lateinit var prefs: InMemoryPreferencesRepository
    private lateinit var equalizer: FakeEqualizer
    private lateinit var audioEngine: FakeAudioEngine
    private lateinit var manager: ProfileManagerImpl

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        dao = FakeProfileDao()
        prefs = InMemoryPreferencesRepository()
        equalizer = FakeEqualizer()
        audioEngine = FakeAudioEngine()
        manager = ProfileManagerImpl(dao, prefs, equalizer, audioEngine)
    }

    private fun makeProfile(
        name: String = "Test",
        eqBands: List<EqBand> = listOf(
            EqBand(250, 5f), EqBand(500, 10f), EqBand(1000, 8f), EqBand(2000, 6f),
            EqBand(3000, 4f), EqBand(4000, 12f), EqBand(6000, 15f), EqBand(8000, 20f)
        ),
        adaptiveEnabled: Boolean = true,
        audiogram: Audiogram? = null
    ): HearingProfile {
        val now = Instant.now()
        return HearingProfile(
            id = UUID.randomUUID(),
            name = name,
            eqBands = eqBands,
            adaptiveAmplificationEnabled = adaptiveEnabled,
            noiseReductionPreset = EnvironmentPreset.DEFAULT,
            audiogram = audiogram,
            createdAt = now,
            updatedAt = now
        )
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Verify activating a profile pushes EQ bands to the equalizer.
     * The in-memory state update (no I/O) guarantees this happens well within 200 ms.
     */
    @Test
    fun activatingProfileAppliesEqBandsWithin200ms() = runTest {
        val profile = makeProfile()
        manager.saveProfile(profile)

        val startMs = System.currentTimeMillis()
        manager.activateProfile(profile.id)
        val elapsedMs = System.currentTimeMillis() - startMs

        // EQ gains applied
        assertEquals(profile.eqBands.size, equalizer.appliedGains.size)
        profile.eqBands.forEachIndexed { idx, band ->
            assertEquals(idx to band.gainDb, equalizer.appliedGains[idx])
        }

        // Within 200 ms
        assertTrue("activateProfile took ${elapsedMs} ms (> 200 ms)", elapsedMs <= 200L)

        // Active profile updated
        assertEquals(profile.id, manager.activeProfile.value?.id)
    }

    /**
     * Verify activating a profile also applies adaptive amplification setting.
     */
    @Test
    fun activatingProfileAppliesAdaptiveSettings() = runTest {
        val profileAdaptive = makeProfile(adaptiveEnabled = true)
        manager.saveProfile(profileAdaptive)
        manager.activateProfile(profileAdaptive.id)

        assertTrue(audioEngine.adaptiveModeEnabled)

        val profileNoAdaptive = makeProfile(adaptiveEnabled = false)
        manager.saveProfile(profileNoAdaptive)
        manager.activateProfile(profileNoAdaptive.id)

        assertFalse(audioEngine.adaptiveModeEnabled)
    }

    /**
     * Verify that 5 or more profiles can be saved and retrieved.
     */
    @Test
    fun canSaveFiveOrMoreProfiles() = runTest {
        val count = 6
        repeat(count) { i ->
            manager.saveProfile(makeProfile(name = "Profile $i"))
        }

        assertEquals(count, manager.profiles.value.size)
    }

    /**
     * Verify switching active profile applies the new profile's settings.
     */
    @Test
    fun profileSwitchAppliesNewSettings() = runTest {
        val profileA = makeProfile(
            name = "A",
            eqBands = List(8) { idx -> EqBand(listOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)[idx], 5f) },
            adaptiveEnabled = false
        )
        val profileB = makeProfile(
            name = "B",
            eqBands = List(8) { idx -> EqBand(listOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)[idx], 20f) },
            adaptiveEnabled = true
        )

        manager.saveProfile(profileA)
        manager.saveProfile(profileB)

        manager.activateProfile(profileA.id)
        assertEquals(profileA.id, manager.activeProfile.value?.id)
        assertFalse(audioEngine.adaptiveModeEnabled)

        equalizer.appliedGains.clear()

        manager.activateProfile(profileB.id)
        assertEquals(profileB.id, manager.activeProfile.value?.id)
        assertTrue(audioEngine.adaptiveModeEnabled)
        // All 8 band gains for profile B should have been applied
        assertEquals(8, equalizer.appliedGains.size)
        equalizer.appliedGains.forEach { (_, gain) ->
            assertEquals(20f, gain, 0.001f)
        }
    }

    /**
     * Verify that deleting a profile removes it and its child rows.
     */
    @Test
    fun deletingProfileRemovesItAndChildren() = runTest {
        val profile = makeProfile()
        manager.saveProfile(profile)

        assertEquals(1, manager.profiles.value.size)

        manager.deleteProfile(profile.id)

        assertEquals(0, manager.profiles.value.size)
        assertTrue(dao.bandStore[profile.id].isNullOrEmpty())
        assertTrue(dao.audiogramStore[profile.id].isNullOrEmpty())
    }

    /**
     * Verify deleting the active profile clears the active profile reference.
     */
    @Test
    fun deletingActiveProfileClearsActiveProfile() = runTest {
        val profile = makeProfile()
        manager.saveProfile(profile)
        manager.activateProfile(profile.id)

        assertEquals(profile.id, manager.activeProfile.value?.id)

        manager.deleteProfile(profile.id)

        assertNull(manager.activeProfile.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HearingTestEngineTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakeProfileManager : ProfileManager {
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

    private class FakeEqualizer : Equalizer {
        private val _bands = MutableStateFlow(
            listOf(
                EqBand(250, 0f), EqBand(500, 0f), EqBand(1000, 0f), EqBand(2000, 0f),
                EqBand(3000, 0f), EqBand(4000, 0f), EqBand(6000, 0f), EqBand(8000, 0f)
            )
        )
        override val bands: StateFlow<List<EqBand>> = _bands.asStateFlow()
        var audiogramApplied: Audiogram? = null

        override fun setBandGain(bandIndex: Int, gainDb: Float) {}
        override fun applyAudiogram(audiogram: Audiogram) { audiogramApplied = audiogram }
        override fun reset() {}
        private var _isEnabled: Boolean = true
        override val isEnabled: Boolean get() = _isEnabled
        override fun process(buffer: ShortArray, sampleRate: Int, channelCount: Int): ShortArray = buffer
        override fun process(buffer: ShortArray): ShortArray = buffer
        override fun setEnabled(enabled: Boolean) { _isEnabled = enabled }
    }

    // ── Test fixtures ─────────────────────────────────────────────────────────

    private lateinit var fakeProfileManager: FakeProfileManager
    private lateinit var fakeEqualizer: FakeEqualizer
    private lateinit var engine: HearingTestEngineImpl

    @Before
    fun setUp() {
        fakeProfileManager = FakeProfileManager()
        fakeEqualizer = FakeEqualizer()
        engine = HearingTestEngineImpl(fakeProfileManager, fakeEqualizer)
    }

    /**
     * Verify audiologist recommendation flag is set when any threshold > 40 dBHL.
     * We simulate the user not responding until 45 dBHL for the first frequency.
     */
    @Test
    fun audiologistFlagSetWhenThresholdExceeds40dBHL() = runTest {
        val profileId = UUID.randomUUID()
        engine.startTest(profileId)

        // For the first frequency (250 Hz), advance past 40 dBHL (levels: 0,5,10,15,20,25,30,35,40 → 9 no-responses, then recordResponse at level index 9 = 45 dBHL)
        // testLevels[9] = 45 dBHL
        repeat(9) { engine.recordNoResponse() } // no-responses at 0..40 dBHL
        engine.recordResponse() // heard at 45 dBHL

        // Respond "heard" immediately at 0 dBHL for all remaining frequencies
        val remainingFreqs = 5 + 6 // 5 left-ear remaining + 6 right-ear frequencies
        repeat(remainingFreqs) { engine.recordResponse() }

        assertTrue(engine.audiologistRecommended.value)
        assertTrue((engine.testState.value as? HearingTestState.Complete)?.audiologistRecommended == true)
    }

    /**
     * Verify audiologist flag is NOT set when all thresholds are ≤ 40 dBHL.
     */
    @Test
    fun audiologistFlagNotSetWhenAllThresholdsAtOrBelow40dBHL() = runTest {
        val profileId = UUID.randomUUID()
        engine.startTest(profileId)

        // Respond "heard" immediately at 0 dBHL for all 12 frequencies (6 left + 6 right)
        repeat(12) { engine.recordResponse() }

        assertFalse(engine.audiologistRecommended.value)
        assertFalse((engine.testState.value as? HearingTestState.Complete)?.audiologistRecommended == true)
    }

    /**
     * Verify that completing the test saves the profile and applies the audiogram.
     */
    @Test
    fun completedTestSavesProfileAndAppliesAudiogram() = runTest {
        val profileId = UUID.randomUUID()
        engine.startTest(profileId)

        // Respond "heard" at 0 dBHL for all 12 frequencies
        repeat(12) { engine.recordResponse() }

        assertTrue(engine.testState.value is HearingTestState.Complete)
        assertEquals(1, fakeProfileManager.savedProfiles.size)
        assertFalse(fakeProfileManager.savedProfiles.first().audiogram == null)

        // Audiogram applied to equalizer
        assertFalse(fakeEqualizer.audiogramApplied == null)
    }

    /**
     * Verify cancelling a test resets state to Idle.
     */
    @Test
    fun cancellingTestResetsToIdle() = runTest {
        val profileId = UUID.randomUUID()
        engine.startTest(profileId)

        assertTrue(engine.testState.value is HearingTestState.InProgress)

        engine.cancelTest()

        assertTrue(engine.testState.value is HearingTestState.Idle)
    }
}
