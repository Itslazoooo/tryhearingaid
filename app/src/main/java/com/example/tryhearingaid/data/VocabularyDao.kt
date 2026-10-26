package com.example.tryhearingaid.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Data Access Object for vocabulary terms discovered during captioning sessions.
 *
 * Provides CRUD operations and Flow-based reactive queries for [VocabTermEntity],
 * supporting:
 * - Deduplication via [getTermByWord] (case-insensitive word lookup)
 * - Reactive [getAllTerms] stream consumed by the AI assistant and UI
 * - Bulk deletion for privacy data-wipe operations
 */
@Dao
interface VocabularyDao {

    // ── Insert ────────────────────────────────────────────────────────────────

    /**
     * Insert a new vocabulary term. If a term with the same [VocabTermEntity.id]
     * already exists it is replaced. Use [getTermByWord] first to avoid
     * word-level duplicates.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTerm(term: VocabTermEntity)

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Case-insensitive lookup by word. Returns the first matching term or null
     * when the word is not yet in the vocabulary. Used for deduplication before
     * requesting an AI definition.
     */
    @Query("SELECT * FROM vocab_term WHERE LOWER(word) = LOWER(:word) LIMIT 1")
    suspend fun getTermByWord(word: String): VocabTermEntity?

    /** Reactive stream of all vocabulary terms, ordered alphabetically. */
    @Query("SELECT * FROM vocab_term ORDER BY word ASC")
    fun getAllTerms(): Flow<List<VocabTermEntity>>

    /** Snapshot (non-Flow) of all terms for one-shot reads. */
    @Query("SELECT * FROM vocab_term ORDER BY word ASC")
    suspend fun getAllTermsSnapshot(): List<VocabTermEntity>

    /** Return all terms for a specific session. */
    @Query("SELECT * FROM vocab_term WHERE session_id = :sessionId ORDER BY word ASC")
    fun getTermsForSession(sessionId: UUID): Flow<List<VocabTermEntity>>

    // ── Deletion ──────────────────────────────────────────────────────────────

    /** Remove all vocabulary entries. Idempotent — safe to call on an empty table. */
    @Query("DELETE FROM vocab_term")
    suspend fun clearAll()

    /** Alias of [clearAll] for callers that prefer the name [deleteAll]. */
    @Query("DELETE FROM vocab_term")
    suspend fun deleteAll()

    @Query("DELETE FROM vocab_term WHERE session_id = :sessionId")
    suspend fun deleteTermsForSession(sessionId: UUID)
}
