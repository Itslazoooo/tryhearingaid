package com.example.tryhearingaid.data

import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// DataRepository interface
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Aggregates all DAOs and the [PreferencesRepository] behind a single
 * domain-facing interface.  ViewModels and services depend only on this
 * interface, keeping them decoupled from Room and DataStore implementation
 * details.
 *
 * Flows exposed here are hot reactive streams backed by Room queries and
 * DataStore; collect them in a lifecycle-aware scope (e.g. via
 * `collectAsStateWithLifecycle` in Compose or `viewModelScope`).
 */
interface DataRepository {

    // ── Preferences ───────────────────────────────────────────────────────────

    /** Combined flow of all user preferences as a single [UserPreferences] snapshot. */
    val userPreferences: Flow<UserPreferences>

    // ── Transcripts ───────────────────────────────────────────────────────────

    /** Reactive stream of all transcript sessions ordered by start time descending. */
    val allSessions: Flow<List<TranscriptSession>>

    /** Reactive stream of all daily exposure records ordered by date ascending. */
    val allExposures: Flow<List<DailyExposureEntity>>

    /** Reactive stream of all vocabulary terms ordered alphabetically. */
    val allVocabTerms: Flow<List<VocabTermEntity>>

    // ── Global data wipe ──────────────────────────────────────────────────────

    /**
     * Deletes all user data from every store (profiles, transcripts, analytics,
     * vocabulary) and resets preferences to their defaults.
     *
     * Satisfies Property 32: after this call all data stores return empty
     * collections or null values.
     */
    suspend fun deleteAllData()

    // ── Sessions ──────────────────────────────────────────────────────────────

    /** Insert [session], replacing any existing row with the same primary key. */
    suspend fun insertSession(session: TranscriptSession)

    /** Update an existing [session] row. */
    suspend fun updateSession(session: TranscriptSession)

    /** Return a Flow that emits the session matching [id], or null if absent. */
    fun getSession(id: UUID): Flow<TranscriptSession?>

    // ── Caption segments ──────────────────────────────────────────────────────

    /**
     * Return a Flow of all segments for [sessionId] ordered by timestamp
     * ascending; emits a new list on every change.
     */
    fun getSegmentsForSession(sessionId: UUID): Flow<List<CaptionSegmentEntity>>

    /**
     * One-shot snapshot of all segments for [sessionId] ordered by timestamp
     * ascending.  Use for export operations that do not need a live subscription.
     */
    suspend fun getSegmentsForSessionSnapshot(sessionId: UUID): List<CaptionSegmentEntity>

    /** Insert [segment], replacing any existing row with the same primary key. */
    suspend fun insertSegment(segment: CaptionSegmentEntity)

    // ── Analytics / exposure ──────────────────────────────────────────────────

    /**
     * Insert or replace the exposure record for a given calendar day.
     * Using REPLACE strategy so each date has at most one row.
     */
    suspend fun insertOrReplaceExposure(entity: DailyExposureEntity)

    /** Return the exposure record for an exact [date], or null if absent. */
    suspend fun getExposureByDate(date: LocalDate): DailyExposureEntity?

    /**
     * Return a Flow of up to [n] records on or after [fromDate] ordered
     * chronologically.  Used for the 7-day dashboard chart and 30-day trend.
     */
    fun getLastNDays(fromDate: LocalDate, n: Int): Flow<List<DailyExposureEntity>>

    /**
     * Snapshot (non-Flow) version of [getLastNDays] for one-shot trend report
     * generation.
     */
    suspend fun getLastNDaysSnapshot(fromDate: LocalDate, n: Int): List<DailyExposureEntity>

    /**
     * Delete exposure records strictly older than [cutoffDate] to enforce
     * the 90-day retention policy (Property 19).
     */
    suspend fun deleteExposuresOlderThan(cutoffDate: LocalDate)

    // ── Vocabulary ────────────────────────────────────────────────────────────

    /** Insert [term], replacing any existing row with the same primary key. */
    suspend fun insertVocabTerm(term: VocabTermEntity)

    /**
     * Case-insensitive lookup by [word].  Returns the first matching term or
     * null when the word is not yet in the vocabulary.
     */
    suspend fun getVocabTermByWord(word: String): VocabTermEntity?

    /**
     * Remove all vocabulary entries.  Idempotent — safe to call on an empty
     * table (Property 25).
     */
    suspend fun clearAllVocabTerms()
}

// ─────────────────────────────────────────────────────────────────────────────
// DataRepositoryImpl
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Production implementation that delegates every operation to the appropriate
 * Room DAO obtained from [db] or to [preferencesRepository].
 *
 * @param db                    The Room database singleton.
 * @param preferencesRepository The DataStore-backed preferences repository.
 */
class DataRepositoryImpl(
    private val db: HearingAidDatabase,
    private val preferencesRepository: PreferencesRepository,
) : DataRepository {

    // ── Preferences ───────────────────────────────────────────────────────────

    override val userPreferences: Flow<UserPreferences>
        get() = preferencesRepository.userPreferences

    // ── Aggregate flows ───────────────────────────────────────────────────────

    override val allSessions: Flow<List<TranscriptSession>>
        get() = db.transcriptDao().getAllSessions()

    override val allExposures: Flow<List<DailyExposureEntity>>
        get() = db.analyticsDao().getAllExposures()

    override val allVocabTerms: Flow<List<VocabTermEntity>>
        get() = db.vocabularyDao().getAllTerms()

    // ── Global data wipe ──────────────────────────────────────────────────────

    override suspend fun deleteAllData() {
        // Profiles (cascades to eq_band and audiogram via FK ON DELETE CASCADE)
        db.profileDao().deleteAllProfiles()
        // Transcripts and their caption segments (CASCADE handles segments)
        db.transcriptDao().deleteAllSessions()
        // Analytics
        db.analyticsDao().deleteAll()
        // Vocabulary
        db.vocabularyDao().clearAll()
        // DataStore preferences
        preferencesRepository.clearAll()
    }

    // ── Sessions ──────────────────────────────────────────────────────────────

    override suspend fun insertSession(session: TranscriptSession) =
        db.transcriptDao().insertSession(session)

    override suspend fun updateSession(session: TranscriptSession) =
        db.transcriptDao().updateSession(session)

    override fun getSession(id: UUID): Flow<TranscriptSession?> =
        db.transcriptDao().getSession(id)

    // ── Caption segments ──────────────────────────────────────────────────────

    override fun getSegmentsForSession(sessionId: UUID): Flow<List<CaptionSegmentEntity>> =
        db.transcriptDao().getSegmentsForSession(sessionId)

    override suspend fun getSegmentsForSessionSnapshot(sessionId: UUID): List<CaptionSegmentEntity> =
        db.transcriptDao().getSegmentsForSessionSnapshot(sessionId)

    override suspend fun insertSegment(segment: CaptionSegmentEntity) =
        db.transcriptDao().insertSegment(segment)

    // ── Analytics / exposure ──────────────────────────────────────────────────

    override suspend fun insertOrReplaceExposure(entity: DailyExposureEntity) =
        db.analyticsDao().insertOrReplace(entity)

    override suspend fun getExposureByDate(date: LocalDate): DailyExposureEntity? =
        db.analyticsDao().getByDate(date)

    override fun getLastNDays(fromDate: LocalDate, n: Int): Flow<List<DailyExposureEntity>> =
        db.analyticsDao().getLastNDays(fromDate, n)

    override suspend fun getLastNDaysSnapshot(fromDate: LocalDate, n: Int): List<DailyExposureEntity> =
        db.analyticsDao().getLastNDaysSnapshot(fromDate, n)

    override suspend fun deleteExposuresOlderThan(cutoffDate: LocalDate) =
        db.analyticsDao().deleteOlderThan(cutoffDate)

    // ── Vocabulary ────────────────────────────────────────────────────────────

    override suspend fun insertVocabTerm(term: VocabTermEntity) =
        db.vocabularyDao().insertTerm(term)

    override suspend fun getVocabTermByWord(word: String): VocabTermEntity? =
        db.vocabularyDao().getTermByWord(word)

    override suspend fun clearAllVocabTerms() =
        db.vocabularyDao().clearAll()
}
