package com.example.tryhearingaid.analytics

import com.example.tryhearingaid.data.AnalyticsDashboard
import com.example.tryhearingaid.data.DailyExposure
import com.example.tryhearingaid.data.TrendReport
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks, aggregates, and evaluates hearing exposure data in real time.
 *
 * Subscribes to the [AudioEngine]'s SPL measurements and computes the NIOSH
 * daily noise dose using the 85 dB / 3 dB exchange-rate formula:
 *
 *   dose% = 100 × Σ(Cᵢ / Tᵢ)
 *   Tᵢ = 8 h × 2^((85 − Lᵢ) / 3)
 *
 * Requirements 11.1, 11.2, 11.3, 11.4, 11.5, 11.6
 */
interface AnalyticsEngine {

    /**
     * Live output SPL in dB, mirrored from [AudioEngine.getCurrentSplDb()].
     * Requirement 11.2
     */
    val currentSplDb: StateFlow<Float>

    /**
     * Accumulated NIOSH dose for today expressed as a percentage (0 = no
     * exposure, 100 = full permissible dose at 85 dB for 8 h).
     * Requirement 11.1
     */
    val dailyDosePercent: StateFlow<Float>

    /**
     * Full analytics dashboard snapshot updated whenever SPL or dose changes.
     * Requirement 11.2
     */
    val dashboardData: StateFlow<AnalyticsDashboard>

    /**
     * Emits a [DoseWarningEvent] whenever the dose crosses a threshold:
     *   - [DoseWarningLevel.MODERATE] when dose first reaches 50 %
     *   - [DoseWarningLevel.HIGH]     when dose first reaches 100 %
     *
     * Requirements 11.3, 11.4
     */
    val doseWarnings: SharedFlow<DoseWarningEvent>

    /**
     * Generate a summary of the past [days] days (default 30).
     * Returns average daily dose, count of high-risk days, and max peak SPL.
     * Requirement 11.6
     */
    suspend fun generateTrendReport(days: Int = 30): TrendReport
}

// ─── Warning event ────────────────────────────────────────────────────────────

/**
 * Carries the warning level so subscribers (e.g. [AnalyticsViewModel]) can
 * display the correct message without re-deriving the level.
 */
data class DoseWarningEvent(val level: DoseWarningLevel)

// ─── Warning level enum (canonical definition) ────────────────────────────────

/**
 * Risk level derived from the NIOSH daily noise dose percentage.
 *
 * - [NO_WARNING] — dose < 50 %
 * - [MODERATE]   — 50 % ≤ dose < 100 %
 * - [HIGH]       — dose ≥ 100 %
 *
 * Requirements 11.3, 11.4
 */
enum class DoseWarningLevel { NO_WARNING, MODERATE, HIGH }
