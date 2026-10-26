package com.example.tryhearingaid.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Data Access Object for hearing profiles and related entities.
 *
 * Provides CRUD operations and Flow-based reactive queries for:
 * - [HearingProfileEntity] (top-level profile rows)
 * - [EqBandEntity] (8 EQ bands per profile)
 * - [AudiogramEntity] (hearing threshold measurements per profile)
 */
@Dao
interface ProfileDao {

    // ── HearingProfile ────────────────────────────────────────────────────────

    @Query("SELECT * FROM hearing_profile ORDER BY updated_at DESC")
    fun getAllProfiles(): Flow<List<HearingProfileEntity>>

    @Query("SELECT * FROM hearing_profile WHERE id = :id")
    fun getProfileById(id: UUID): Flow<HearingProfileEntity?>

    /** One-shot snapshot for callers that need a suspend result rather than a Flow. */
    @Query("SELECT * FROM hearing_profile WHERE id = :id LIMIT 1")
    suspend fun getProfileByIdSnapshot(id: UUID): HearingProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: HearingProfileEntity)

    @Update
    suspend fun updateProfile(profile: HearingProfileEntity)

    @Query("DELETE FROM hearing_profile WHERE id = :id")
    suspend fun deleteProfile(id: UUID)

    @Query("DELETE FROM hearing_profile")
    suspend fun deleteAllProfiles()

    // ── EqBandEntity ──────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEqBands(bands: List<EqBandEntity>)

    @Query("SELECT * FROM eq_band WHERE profile_id = :profileId ORDER BY band_index ASC")
    fun getEqBandsForProfile(profileId: UUID): Flow<List<EqBandEntity>>

    /** One-shot snapshot for callers that need a suspend result rather than a Flow. */
    @Query("SELECT * FROM eq_band WHERE profile_id = :profileId ORDER BY band_index ASC")
    suspend fun getEqBandsForProfileSnapshot(profileId: UUID): List<EqBandEntity>

    @Query("DELETE FROM eq_band WHERE profile_id = :profileId")
    suspend fun deleteEqBandsForProfile(profileId: UUID)

    // ── AudiogramEntity ───────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudiogramEntry(entry: AudiogramEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudiogramRows(rows: List<AudiogramEntity>)

    @Query("SELECT * FROM audiogram WHERE profile_id = :profileId ORDER BY ear ASC, frequency_hz ASC")
    fun getAudiogramForProfile(profileId: UUID): Flow<List<AudiogramEntity>>

    /** One-shot snapshot for callers that need a suspend result rather than a Flow. */
    @Query("SELECT * FROM audiogram WHERE profile_id = :profileId ORDER BY ear ASC, frequency_hz ASC")
    suspend fun getAudiogramForProfileSnapshot(profileId: UUID): List<AudiogramEntity>

    @Query("DELETE FROM audiogram WHERE profile_id = :profileId")
    suspend fun deleteAudiogramForProfile(profileId: UUID)
}
