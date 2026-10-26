package com.example.tryhearingaid.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Data Access Object for transcript sessions and their caption segments.
 *
 * Provides CRUD operations and Flow-based reactive queries for:
 * - [TranscriptSession] (one row per captioning/recording session)
 * - [CaptionSegmentEntity] (one row per transcribed caption segment)
 */
@Dao
interface TranscriptDao {

    // ── TranscriptSession ─────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: TranscriptSession)

    @Update
    suspend fun updateSession(session: TranscriptSession)

    @Query("SELECT * FROM transcript_session WHERE id = :id")
    fun getSession(id: UUID): Flow<TranscriptSession?>

    @Query("SELECT * FROM transcript_session ORDER BY started_at DESC")
    fun getAllSessions(): Flow<List<TranscriptSession>>

    @Query("DELETE FROM transcript_session")
    suspend fun deleteAllSessions()

    // ── CaptionSegmentEntity ──────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: CaptionSegmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<CaptionSegmentEntity>)

    /**
     * Returns all segments for the given session ordered by their timestamp,
     * emitting a new list whenever segments are added or updated.
     */
    @Query("SELECT * FROM caption_segment WHERE session_id = :sessionId ORDER BY timestamp_ms ASC")
    fun getSegmentsForSession(sessionId: UUID): Flow<List<CaptionSegmentEntity>>

    /**
     * Snapshot query (non-Flow) for export operations that need a one-shot read.
     */
    @Query("SELECT * FROM caption_segment WHERE session_id = :sessionId ORDER BY timestamp_ms ASC")
    suspend fun getSegmentsForSessionSnapshot(sessionId: UUID): List<CaptionSegmentEntity>

    /**
     * Retrieve a single session record snapshot (non-Flow) for one-shot reads.
     */
    @Query("SELECT * FROM transcript_session WHERE id = :sessionId")
    suspend fun getSessionSnapshot(sessionId: UUID): TranscriptSession?

    @Query("DELETE FROM caption_segment")
    suspend fun deleteAllSegments()

    @Query("DELETE FROM caption_segment WHERE session_id = :sessionId")
    suspend fun deleteSegmentsForSession(sessionId: UUID)
}
