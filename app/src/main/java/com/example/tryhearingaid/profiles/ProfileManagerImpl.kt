package com.example.tryhearingaid.profiles

import com.example.tryhearingaid.audio.AudioEngine
import com.example.tryhearingaid.audio.Equalizer
import com.example.tryhearingaid.data.AudiogramEntity
import com.example.tryhearingaid.data.Audiogram
import com.example.tryhearingaid.data.EqBand
import com.example.tryhearingaid.data.EqBandEntity
import com.example.tryhearingaid.data.EnvironmentPreset
import com.example.tryhearingaid.data.HearingProfile
import com.example.tryhearingaid.data.HearingProfileEntity
import com.example.tryhearingaid.data.PreferencesRepository
import com.example.tryhearingaid.data.ProfileDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Concrete implementation of [ProfileManager] backed by [ProfileDao] and
 * [PreferencesRepository].
 *
 * In-memory state ([_profiles], [_activeProfile]) is the source of truth during
 * a session and is updated synchronously by each mutating operation. The DB and
 * DataStore are written asynchronously for durability. This ensures that callers
 * observe the new state immediately without waiting for persistence I/O.
 *
 * The [activateProfile] contract (push EQ + adaptive settings to AudioEngine
 * within 200 ms) is satisfied because [Equalizer.setBandGain] and
 * [AudioEngine.setAdaptiveMode] are synchronous in-memory operations. The only
 * I/O work is the DataStore write for `activeProfileId`, which is fire-and-
 * forget after the in-memory state is updated.
 */
class ProfileManagerImpl(
    private val profileDao: ProfileDao,
    private val preferencesRepository: PreferencesRepository,
    private val equalizer: Equalizer,
    private val audioEngine: AudioEngine,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : ProfileManager {

    private val _profiles = MutableStateFlow<List<HearingProfile>>(emptyList())
    override val profiles: StateFlow<List<HearingProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow<HearingProfile?>(null)
    override val activeProfile: StateFlow<HearingProfile?> = _activeProfile.asStateFlow()

    // ── ProfileManager implementation ─────────────────────────────────────────

    override suspend fun saveProfile(profile: HearingProfile) {
        // 1. Update in-memory list immediately
        val mutable = _profiles.value.toMutableList()
        val idx = mutable.indexOfFirst { it.id == profile.id }
        if (idx >= 0) mutable[idx] = profile else mutable.add(profile)
        _profiles.value = mutable.toList()

        // 2. If this profile is currently active, refresh in-memory active reference
        if (_activeProfile.value?.id == profile.id) {
            _activeProfile.value = profile
        }

        // 3. Persist to Room (all DB work via upsert pattern)
        persistProfile(profile)
    }

    override suspend fun activateProfile(profileId: UUID) {
        val profile = _profiles.value.firstOrNull { it.id == profileId }
            ?: return  // Profile not found — no-op

        // 1. Update in-memory active profile immediately (no I/O latency)
        _activeProfile.value = profile

        // 2. Push EQ bands to equalizer (synchronous, in-memory)
        profile.eqBands.forEachIndexed { idx, band ->
            equalizer.setBandGain(idx, band.gainDb)
        }

        // 3. Apply adaptive amplification setting (synchronous, in-memory)
        audioEngine.setAdaptiveMode(profile.adaptiveAmplificationEnabled)

        // 4. Persist active profile id to DataStore (async, does not block the 200 ms window)
        externalScope.launch {
            preferencesRepository.updateActiveProfileId(profileId)
        }
    }

    override suspend fun deleteProfile(profileId: UUID) {
        // 1. Update in-memory state immediately
        _profiles.value = _profiles.value.filter { it.id != profileId }

        // 2. Clear active profile if it was the deleted one
        if (_activeProfile.value?.id == profileId) {
            _activeProfile.value = null
            externalScope.launch {
                preferencesRepository.updateActiveProfileId(null)
            }
        }

        // 3. Remove from DB (child rows first for foreign-key safety)
        profileDao.deleteEqBandsForProfile(profileId)
        profileDao.deleteAudiogramForProfile(profileId)
        profileDao.deleteProfile(profileId)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun persistProfile(profile: HearingProfile) {
        val entity = HearingProfileEntity(
            id = profile.id,
            name = profile.name,
            adaptiveEnabled = profile.adaptiveAmplificationEnabled,
            noisePreset = profile.noiseReductionPreset.name,
            createdAt = profile.createdAt,
            updatedAt = profile.updatedAt
        )

        // Upsert: delete existing EQ/audiogram rows, re-insert
        profileDao.deleteEqBandsForProfile(profile.id)
        profileDao.deleteAudiogramForProfile(profile.id)

        val existing = profileDao.getProfileByIdSnapshot(profile.id)
        if (existing != null) {
            profileDao.updateProfile(entity)
        } else {
            profileDao.insertProfile(entity)
        }

        // Persist EQ bands
        val bandEntities = profile.eqBands.mapIndexed { idx, band ->
            EqBandEntity(
                profileId = profile.id,
                bandIndex = idx,
                centerHz = band.centerHz,
                gainDb = band.gainDb
            )
        }
        profileDao.insertEqBands(bandEntities)

        // Persist audiogram if present
        profile.audiogram?.let { audiogram ->
            val rows = mutableListOf<AudiogramEntity>()
            audiogram.leftEar.forEach { (freq, threshold) ->
                rows += AudiogramEntity(profile.id, "LEFT", freq, threshold, audiogram.recordedAt)
            }
            audiogram.rightEar.forEach { (freq, threshold) ->
                rows += AudiogramEntity(profile.id, "RIGHT", freq, threshold, audiogram.recordedAt)
            }
            profileDao.insertAudiogramRows(rows)
        }
    }

    /**
     * Load the full [HearingProfile] for the given [entity] by fetching its EQ bands
     * and audiogram rows from the DAO. Useful when restoring state from DB on init.
     */
    private suspend fun loadFullProfile(entity: HearingProfileEntity): HearingProfile {
        val bandEntities = profileDao.getEqBandsForProfileSnapshot(entity.id)
        val eqBands = bandEntities
            .sortedBy { it.bandIndex }
            .map { EqBand(it.centerHz, it.gainDb) }

        val audiogramRows = profileDao.getAudiogramForProfileSnapshot(entity.id)
        val audiogram: Audiogram? = if (audiogramRows.isEmpty()) null else {
            val leftEar = audiogramRows
                .filter { it.ear == "LEFT" }
                .associate { it.frequencyHz to it.thresholdDbl }
            val rightEar = audiogramRows
                .filter { it.ear == "RIGHT" }
                .associate { it.frequencyHz to it.thresholdDbl }
            val recordedAt = audiogramRows.first().recordedAt
            Audiogram(entity.id, leftEar, rightEar, recordedAt)
        }

        return HearingProfile(
            id = entity.id,
            name = entity.name,
            eqBands = eqBands,
            adaptiveAmplificationEnabled = entity.adaptiveEnabled,
            noiseReductionPreset = runCatching { EnvironmentPreset.valueOf(entity.noisePreset) }
                .getOrDefault(EnvironmentPreset.DEFAULT),
            audiogram = audiogram,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt
        )
    }
}
