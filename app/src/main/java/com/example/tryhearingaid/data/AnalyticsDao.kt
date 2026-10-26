package com.example.tryhearingaid.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Data Access Object for daily hearing exposure analytics.
 *
 * Provides CRUD operations and Flow-based reactive queries for
 * [DailyExposureEntity], supporting:
 * - 90-day retention management ([deleteOlderThan])
 * - 30-day trend queries ([getLastNDays])
 * - Date-range queries for dashboard and reporting
 *
 * Note: [DailyExposureEntity.date] is stored as a [LocalDate] converted to a
 * comparable ISO-8601 string ("yyyy-MM-dd") by [HearingAidTypeConverters].
 * String comparison works correctly for ISO dates, so `>=`/`<=` operators on
 * the `date` column yield the expected chronological ordering.
 */
@Dao
interface AnalyticsDao {

    // ── Insert / upsert ───────────────────────────────────────────────────────

    /**
     * Insert or replace the record for a given date.
     * Using REPLACE strategy so each calendar day has exactly one row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(entity: DailyExposureEntity)

    // ── Point queries ─────────────────────────────────────────────────────────

    /** Return the exposure record for an exact [date], or null if absent. */
    @Query("SELECT * FROM daily_exposure WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: LocalDate): DailyExposureEntity?

    // ── Range / trend queries ─────────────────────────────────────────────────

    /**
     * Return the most recent [n] daily records on or after [fromDate], ordered
     * chronologically. Used for the 30-day trend report and 7-day dashboard chart.
     *
     * @param fromDate the earliest date to include (ISO-8601 string comparison)
     * @param n        maximum number of records to return
     */
    @Query(
        """
        SELECT * FROM daily_exposure
        WHERE date >= :fromDate
        ORDER BY date ASC
        LIMIT :n
        """
    )
    fun getLastNDays(fromDate: LocalDate, n: Int): Flow<List<DailyExposureEntity>>

    /**
     * Snapshot (non-Flow) version of [getLastNDays] for one-shot trend report
     * generation without a live subscription.
     */
    @Query(
        """
        SELECT * FROM daily_exposure
        WHERE date >= :fromDate
        ORDER BY date ASC
        LIMIT :n
        """
    )
    suspend fun getLastNDaysSnapshot(fromDate: LocalDate, n: Int): List<DailyExposureEntity>

    /** Return all records as a reactive Flow for the full analytics dashboard. */
    @Query("SELECT * FROM daily_exposure ORDER BY date ASC")
    fun getAllExposures(): Flow<List<DailyExposureEntity>>

    // ── Retention management ──────────────────────────────────────────────────

    /**
     * Delete records strictly older than [cutoffDate] to enforce the 90-day
     * retention policy.
     *
     * @param cutoffDate records with date < cutoffDate are removed
     */
    @Query("DELETE FROM daily_exposure WHERE date < :cutoffDate")
    suspend fun deleteOlderThan(cutoffDate: LocalDate)

    @Query("DELETE FROM daily_exposure")
    suspend fun deleteAll()
}
