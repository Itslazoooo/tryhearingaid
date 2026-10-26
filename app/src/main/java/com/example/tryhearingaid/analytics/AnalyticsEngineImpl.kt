package com.example.tryhearingaid.analytics

import com.example.tryhearingaid.audio.AudioEngine
import com.example.tryhearingaid.audio.AudioEngineState
import com.example.tryhearingaid.data.AnalyticsDao
import com.example.tryhearingaid.data.AnalyticsDashboard
import com.example.tryhearingaid.data.DailyExposure
import com.example.tryhearingaid.data.DailyExposureEntity
import com.example.tryhearingaid.data.TrendReport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * Production implementation of [AnalyticsEngine].
 *
 * ### NIOSH dose formula
 *   dose% = 100 × Σ(Cᵢ / Tᵢ)
 *   Tᵢ (hours) = 8 × 2^((85 − Lᵢ) / 3)
 *
 * SPL measurements are polled from [AudioEngine.getCurrentSplDb] at 1-second
 * intervals while the engine is RUNNING.  Each 1-second interval contributes
 * Cᵢ = 1/3600 h at the measured level Lᵢ.
 *
 * ### Persistence
 * A [DailyExposureEntity] is upserted every [PERSIST_INTERVAL_SEC] seconds so
 * that exposure data survives app restarts.  On startup the engine reloads
 * today's record (if any) to resume accumulation.
 *
 * ### Retention
 * Records older than 90 days are deleted on startup and once per day thereafter.
 *
 * Requirements 11.1 – 11.6
 */
@Singleton
class AnalyticsEngineImpl @Inject constructor(
    private val audioEngine: AudioEngine,
    private val analyticsDao: AnalyticsDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AnalyticsEngine {

    // ── Scope ─────────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // ── Public StateFlow / SharedFlow ─────────────────────────────────────────

    private val _currentSplDb = MutableStateFlow(0f)
    override val currentSplDb: StateFlow<Float> = _currentSplDb.asStateFlow()

    private val _dailyDosePercent = MutableStateFlow(0f)
    override val dailyDosePercent: StateFlow<Float> = _dailyDosePercent.asStateFlow()

    private val _dashboardData = MutableStateFlow(emptyDashboard())
    override val dashboardData: StateFlow<AnalyticsDashboard> = _dashboardData.asStateFlow()

    private val _doseWarnings = MutableSharedFlow<DoseWarningEvent>(extraBufferCapacity = 8)
    override val doseWarnings: SharedFlow<DoseWarningEvent> = _doseWarnings.asSharedFlow()

    // ── Internal state ────────────────────────────────────────────────────────

    /** Accumulated NIOSH dose in percent for the current calendar day. */
    @Volatile private var accumulatedDosePercent: Double = 0.0

    /** Running peak SPL observed today. */
    @Volatile private var peakSplToday: Float = 0f

    /** Sum of SPL values accumulated for today's average computation. */
    @Volatile private var splSum: Double = 0.0

    /** Number of SPL samples taken today (for average computation). */
    @Volatile private var splSampleCount: Long = 0L

    /** The calendar date for which dose is currently being accumulated. */
    @Volatile private var currentDay: LocalDate = LocalDate.now()

    /**
     * Warning flags — reset each calendar day.
     * Tracks whether a threshold crossing has already been emitted for the current day
     * to avoid repeated warnings at exactly 50 % or 100 %.
     */
    @Volatile private var moderateWarningEmitted: Boolean = false
    @Volatile private var highWarningEmitted: Boolean = false

    /** Active polling job; cancelled and restarted whenever the engine state changes. */
    private var pollingJob: Job? = null

    companion object {
        /** Duration of each SPL measurement interval (one sample per second). */
        private const val POLL_INTERVAL_MS = 1_000L

        /** How many seconds between persistence writes. */
        private const val PERSIST_INTERVAL_SEC = 30

        /** Retention window in days. */
        private const val RETENTION_DAYS = 90L

        /** NIOSH reference level in dB SPL (85 dB). */
        private const val NIOSH_REFERENCE_DB = 85.0

        /** NIOSH permissible duration at reference level (8 hours). */
        private const val NIOSH_REF_DURATION_HOURS = 8.0

        /** NIOSH exchange rate exponent base (3 dB doubling). */
        private const val NIOSH_EXCHANGE_RATE = 3.0

        /** Minimum SPL (dB) for which dose contribution is computed. Very quiet
         *  levels (< ~70 dB) have near-infinite permissible time; contributions
         *  below this floor are effectively zero and not accumulated. */
        private const val MIN_SPL_FOR_DOSE = 70.0

        /** Moderate-risk dose threshold (%). Requirement 11.3 */
        private const val MODERATE_WARNING_THRESHOLD = 50.0

        /** High-risk dose threshold (%). Requirement 11.4 */
        private const val HIGH_WARNING_THRESHOLD = 100.0

        /** Number of days to include in weekly trend (7 days). Requirement 11.2 */
        private const val WEEKLY_TREND_DAYS = 7
    }

    // ── Initialization ────────────────────────────────────────────────────────

    init {
        scope.launch {
            // Purge stale records and reload today's data on startup.
            purgeOldRecords()
            reloadTodayRecord()

            // Watch AudioEngine state; start/stop polling accordingly.
            audioEngine.state
                .catch { /* ignore state-flow errors */ }
                .collect { engineState ->
                    when (engineState) {
                        AudioEngineState.RUNNING -> startPolling()
                        else                     -> stopPolling()
                    }
                }
        }

        // Daily maintenance: purge old records once every 24 hours.
        scope.launch {
            while (true) {
                delay(24L * 60L * 60L * 1_000L) // 24 h
                purgeOldRecords()
            }
        }
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            var secondsSinceLastPersist = 0
            while (true) {
                delay(POLL_INTERVAL_MS)

                // Roll over accumulator at midnight.
                val today = LocalDate.now()
                if (today != currentDay) {
                    persistTodayRecord(currentDay)
                    rolloverDay(today)
                    secondsSinceLastPersist = 0
                }

                val splDb = audioEngine.getCurrentSplDb()
                _currentSplDb.value = splDb

                // Accumulate dose — one second = 1/3600 hours.
                if (splDb >= MIN_SPL_FOR_DOSE) {
                    val tPermissibleHours = permissibleDurationHours(splDb.toDouble())
                    val contribution = (1.0 / 3600.0) / tPermissibleHours * 100.0
                    accumulatedDosePercent += contribution
                }

                // Track peak.
                if (splDb > peakSplToday) peakSplToday = splDb

                // Accumulate SPL for daily average.
                splSum += splDb
                splSampleCount++

                val dose = accumulatedDosePercent.toFloat()
                _dailyDosePercent.value = dose

                // Emit warnings on threshold crossings.
                checkAndEmitWarnings(dose)

                // Refresh dashboard.
                updateDashboard()

                // Periodic persistence.
                secondsSinceLastPersist++
                if (secondsSinceLastPersist >= PERSIST_INTERVAL_SEC) {
                    persistTodayRecord(currentDay)
                    secondsSinceLastPersist = 0
                }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // ── NIOSH formula ─────────────────────────────────────────────────────────

    /**
     * Compute the permissible exposure duration (in hours) for a given SPL [levelDb].
     *
     *   Tᵢ = 8 h × 2^((85 − Lᵢ) / 3)
     *
     * Requirement 11.1
     */
    internal fun permissibleDurationHours(levelDb: Double): Double =
        NIOSH_REF_DURATION_HOURS * 2.0.pow((NIOSH_REFERENCE_DB - levelDb) / NIOSH_EXCHANGE_RATE)

    /**
     * Compute dose% for a sequence of (splDb, durationSeconds) pairs.
     *
     *   dose% = 100 × Σ(Cᵢ / Tᵢ)
     *   Tᵢ (hours) = 8 × 2^((85 − Lᵢ) / 3)
     *
     * Only levels ≥ [MIN_SPL_FOR_DOSE] contribute.
     *
     * Requirement 11.1
     */
    internal fun computeDosePercent(measurements: List<Pair<Float, Double>>): Double {
        var dose = 0.0
        for ((splDb, durationSec) in measurements) {
            if (splDb >= MIN_SPL_FOR_DOSE) {
                val tHours = permissibleDurationHours(splDb.toDouble())
                dose += (durationSec / 3600.0) / tHours * 100.0
            }
        }
        return dose
    }

    // ── Warning emission ──────────────────────────────────────────────────────

    private fun checkAndEmitWarnings(dosePercent: Float) {
        if (!moderateWarningEmitted && dosePercent >= MODERATE_WARNING_THRESHOLD) {
            moderateWarningEmitted = true
            _doseWarnings.tryEmit(DoseWarningEvent(DoseWarningLevel.MODERATE))
        }
        if (!highWarningEmitted && dosePercent >= HIGH_WARNING_THRESHOLD) {
            highWarningEmitted = true
            _doseWarnings.tryEmit(DoseWarningEvent(DoseWarningLevel.HIGH))
        }
    }

    // ── Dashboard update ──────────────────────────────────────────────────────

    private suspend fun updateDashboard() {
        val weeklyTrend = fetchWeeklyTrend()
        _dashboardData.value = AnalyticsDashboard(
            currentSpl  = _currentSplDb.value,
            dailyDose   = _dailyDosePercent.value,
            weeklyTrend = weeklyTrend,
            peakLevel   = peakSplToday,
        )
    }

    private suspend fun fetchWeeklyTrend(): List<DailyExposure> {
        val fromDate = LocalDate.now().minusDays(WEEKLY_TREND_DAYS.toLong() - 1)
        return analyticsDao.getLastNDaysSnapshot(fromDate, WEEKLY_TREND_DAYS)
            .map { it.toDomainModel() }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private suspend fun persistTodayRecord(date: LocalDate) {
        val avgSpl = if (splSampleCount > 0) (splSum / splSampleCount).toFloat() else 0f
        val dose   = accumulatedDosePercent.toFloat()
        val entity = DailyExposureEntity(
            date         = date,
            averageSpl   = avgSpl,
            peakSpl      = peakSplToday,
            dosePercent  = dose,
            isHighRisk   = dose >= HIGH_WARNING_THRESHOLD.toFloat(),
        )
        analyticsDao.insertOrReplace(entity)
    }

    /** Reload today's existing record so dose accumulation survives restarts. */
    private suspend fun reloadTodayRecord() {
        val today    = LocalDate.now()
        currentDay   = today
        val existing = analyticsDao.getByDate(today) ?: return

        accumulatedDosePercent = existing.dosePercent.toDouble()
        peakSplToday           = existing.peakSpl
        // We cannot reconstruct splSum/splSampleCount from the stored average accurately
        // without sample count, so start a fresh running average from this point.
        splSum                 = existing.averageSpl.toDouble()
        splSampleCount         = 1L

        val dose = accumulatedDosePercent.toFloat()
        _dailyDosePercent.value = dose

        // Re-arm warnings so we don't double-fire on the same day.
        moderateWarningEmitted = dose >= MODERATE_WARNING_THRESHOLD.toFloat()
        highWarningEmitted     = dose >= HIGH_WARNING_THRESHOLD.toFloat()
    }

    /** Reset all per-day accumulators when crossing midnight into [newDay]. */
    private fun rolloverDay(newDay: LocalDate) {
        currentDay             = newDay
        accumulatedDosePercent = 0.0
        peakSplToday           = 0f
        splSum                 = 0.0
        splSampleCount         = 0L
        moderateWarningEmitted = false
        highWarningEmitted     = false
    }

    // ── Retention management ──────────────────────────────────────────────────

    /** Delete records strictly older than [RETENTION_DAYS] days. Requirement 11.5 */
    private suspend fun purgeOldRecords() {
        val cutoff = LocalDate.now().minusDays(RETENTION_DAYS)
        analyticsDao.deleteOlderThan(cutoff)
    }

    // ── Trend report ──────────────────────────────────────────────────────────

    /**
     * Generate a summary for the past [days] calendar days.
     * Requirement 11.6
     */
    override suspend fun generateTrendReport(days: Int): TrendReport {
        val fromDate = LocalDate.now().minusDays(days.toLong() - 1)
        val records  = analyticsDao.getLastNDaysSnapshot(fromDate, days)

        val avgDose      = if (records.isEmpty()) 0f
                           else records.map { it.dosePercent }.average().toFloat()
        val highRiskDays = records.count { it.isHighRisk }
        val maxPeak      = records.maxOfOrNull { it.peakSpl } ?: 0f

        return TrendReport(
            averageDailyDosePercent = avgDose,
            highRiskDayCount        = highRiskDays,
            maxPeakSpl              = maxPeak,
            windowDays              = days,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun emptyDashboard() = AnalyticsDashboard(
        currentSpl  = 0f,
        dailyDose   = 0f,
        weeklyTrend = emptyList(),
        peakLevel   = 0f,
    )

    private fun DailyExposureEntity.toDomainModel() = DailyExposure(
        date        = date,
        averageSpl  = averageSpl,
        peakSpl     = peakSpl,
        dosePercent = dosePercent,
        isHighRisk  = isHighRisk,
    )
}
