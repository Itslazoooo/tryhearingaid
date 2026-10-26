package com.example.tryhearingaid.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tryhearingaid.data.AnalyticsDashboard
import com.example.tryhearingaid.data.DailyExposure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

// ─── Warning level enum ───────────────────────────────────────────────────────

/**
 * Risk level derived from the NIOSH daily noise dose percentage.
 *
 * - [NO_WARNING]  — dose < 50 %
 * - [MODERATE]    — 50 % ≤ dose < 100 %
 * - [HIGH]        — dose ≥ 100 %
 *
 * Requirement 11.3, 11.4
 */
enum class DoseWarningLevel { NO_WARNING, MODERATE, HIGH }

// ─── ViewModel ────────────────────────────────────────────────────────────────

/**
 * Exposes analytics data to [AnalyticsScreen].
 *
 * In a real implementation the [AnalyticsDashboard] would be collected from the
 * [AnalyticsEngine] / [DataRepository] via a bound [AudioProcessingService].
 * For now the ViewModel seeds itself with a representative default state so the
 * UI can be built and tested in isolation; swap the `init` block for a real
 * repository call when the data layer is wired up.
 */
@HiltViewModel
class AnalyticsViewModel @Inject constructor() : ViewModel() {

    // ── Dashboard state ───────────────────────────────────────────────────────

    private val _dashboard = MutableStateFlow(defaultDashboard())
    /**
     * Snapshot of the analytics dashboard:
     * currentSpl, dailyDose, weeklyTrend, peakLevel.
     *
     * Requirement 11.2
     */
    val dashboard: StateFlow<AnalyticsDashboard> = _dashboard.asStateFlow()

    // ── Warning level ─────────────────────────────────────────────────────────

    private val _warningLevel = MutableStateFlow(DoseWarningLevel.NO_WARNING)
    /**
     * Derived from [dashboard.dailyDose] according to NIOSH thresholds.
     *
     * Requirement 11.3, 11.4
     */
    val warningLevel: StateFlow<DoseWarningLevel> = _warningLevel.asStateFlow()

    init {
        // Recompute warning level whenever the dashboard updates.
        viewModelScope.launch {
            _dashboard.collect { data ->
                _warningLevel.value = when {
                    data.dailyDose >= 100f -> DoseWarningLevel.HIGH
                    data.dailyDose >= 50f  -> DoseWarningLevel.MODERATE
                    else                   -> DoseWarningLevel.NO_WARNING
                }
            }
        }
    }

    // ── Intents ───────────────────────────────────────────────────────────────

    /**
     * User intent: generate a 30-day trend report.
     *
     * Requirement 11.6
     */
    fun generateTrendReport() {
        viewModelScope.launch {
            // TODO: delegate to AnalyticsEngine.generateTrendReport() and show result.
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun defaultDashboard(): AnalyticsDashboard {
        val today = LocalDate.now()
        val weeklyTrend = (6 downTo 0).map { daysAgo ->
            DailyExposure(
                date         = today.minusDays(daysAgo.toLong()),
                averageSpl   = (72f + daysAgo * 1.5f).coerceAtMost(85f),
                peakSpl      = (85f + daysAgo * 0.5f).coerceAtMost(95f),
                dosePercent  = (20f + daysAgo * 5f).coerceAtMost(110f),
                isHighRisk   = (20f + daysAgo * 5f) >= 100f
            )
        }
        return AnalyticsDashboard(
            currentSpl   = 72.4f,
            dailyDose    = 35f,
            weeklyTrend  = weeklyTrend,
            peakLevel    = 88.1f
        )
    }
}
