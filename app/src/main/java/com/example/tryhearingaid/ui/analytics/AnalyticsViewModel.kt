package com.example.tryhearingaid.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tryhearingaid.data.AnalyticsDashboard
import com.example.tryhearingaid.analytics.AnalyticsEngine
import com.example.tryhearingaid.data.TrendReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
class AnalyticsViewModel @Inject constructor(
    private val engine: AnalyticsEngine
) : ViewModel() {

    /** Live dashboard from the analytics engine (Requirement 11.2). */
    val dashboard: StateFlow<AnalyticsDashboard> = engine.dashboardData

    /** NIOSH warning level for today's dose (Requirements 11.3, 11.4). */
    val warningLevel: StateFlow<DoseWarningLevel> = engine.dashboardData
        .map { warningFor(it.dailyDose) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), warningFor(engine.dashboardData.value.dailyDose))

    private val _trendReport = MutableStateFlow<TrendReport?>(null)
    /** The most recent 30-day report, or null until one has been requested. */
    val trendReport: StateFlow<TrendReport?> = _trendReport.asStateFlow()

    /** User intent: generate a 30-day trend report (Requirement 11.6). */
    fun generateTrendReport() {
        viewModelScope.launch {
            _trendReport.value = runCatching { engine.generateTrendReport(30) }.getOrNull()
        }
    }

    companion object {
        fun warningFor(dailyDosePercent: Float): DoseWarningLevel = when {
            dailyDosePercent >= 100f -> DoseWarningLevel.HIGH
            dailyDosePercent >= 50f -> DoseWarningLevel.MODERATE
            else -> DoseWarningLevel.NO_WARNING
        }
    }
}
