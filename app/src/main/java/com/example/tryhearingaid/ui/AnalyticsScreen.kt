package com.example.tryhearingaid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tryhearingaid.data.AnalyticsDashboard
import com.example.tryhearingaid.data.DailyExposure
import com.example.tryhearingaid.ui.analytics.AnalyticsViewModel
import com.example.tryhearingaid.ui.analytics.DoseWarningLevel
import java.time.format.DateTimeFormatter

// ─── Screen entry point ───────────────────────────────────────────────────────

/**
 * Analytics dashboard screen.
 *
 * Displays:
 * - Current SPL arc gauge (Requirement 11.2)
 * - Daily dose progress bar with colour-coded risk level (Requirement 11.3 / 11.4)
 * - 7-day noise exposure trend chart drawn with Compose Canvas (Requirement 11.2)
 * - Peak level indicator (Requirement 11.2)
 * - "Generate Report" button (Requirement 11.6)
 */
@Composable
fun AnalyticsScreen(
    analyticsViewModel: AnalyticsViewModel = hiltViewModel()
) {
    val dashboard   by analyticsViewModel.dashboard.collectAsStateWithLifecycle()
    val warnLevel   by analyticsViewModel.warningLevel.collectAsStateWithLifecycle()

    AnalyticsScreenContent(
        dashboard    = dashboard,
        warnLevel    = warnLevel,
        onGenReport  = { analyticsViewModel.generateTrendReport() }
    )
}

// ─── Stateless content ────────────────────────────────────────────────────────

@Composable
private fun AnalyticsScreenContent(
    dashboard:   AnalyticsDashboard,
    warnLevel:   DoseWarningLevel,
    onGenReport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Row 1: Current SPL gauge + Peak level ─────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SplGaugeCard(
                splDb    = dashboard.currentSpl,
                modifier = Modifier.weight(1f)
            )
            PeakLevelCard(
                peakDb   = dashboard.peakLevel,
                modifier = Modifier.weight(1f)
            )
        }

        // ── Daily dose progress bar ───────────────────────────────────────────
        DailyDoseCard(
            dosePercent = dashboard.dailyDose,
            warnLevel   = warnLevel
        )

        // ── 7-day trend chart ─────────────────────────────────────────────────
        WeeklyTrendCard(
            trend = dashboard.weeklyTrend
        )

        // ── Generate Report button ────────────────────────────────────────────
        Button(
            onClick  = onGenReport,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Generate Report")
        }
    }
}

// ─── Current SPL gauge card ───────────────────────────────────────────────────

/**
 * Arc-style gauge showing the current output SPL.
 *
 * The arc sweeps 240 ° (−120 ° to +120 ° from 6 o'clock) and fills
 * proportionally based on SPL clamped to the expected range [40, 120] dB SPL.
 */
@Composable
private fun SplGaugeCard(splDb: Float, modifier: Modifier = Modifier) {
    val minSpl   = 40f
    val maxSpl   = 120f
    val fraction = ((splDb - minSpl) / (maxSpl - minSpl)).coerceIn(0f, 1f)

    // Colour shifts from green → yellow → red as level rises.
    val gaugeColor = when {
        fraction < 0.5f -> Color(0xFF4CAF50)   // green
        fraction < 0.75f -> Color(0xFFFFC107)  // amber
        else             -> Color(0xFFF44336)  // red
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier  = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier           = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text  = "Current SPL",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier         = Modifier.size(120.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokePx  = 14.dp.toPx()
                    val inset     = strokePx / 2f
                    val arcRect   = Size(size.width - strokePx, size.height - strokePx)
                    val startAngle = 150f   // start at ~8 o'clock
                    val sweepTotal = 240f   // total sweep of the gauge arc

                    // Track (background arc)
                    drawArc(
                        color       = trackColor,
                        startAngle  = startAngle,
                        sweepAngle  = sweepTotal,
                        useCenter   = false,
                        topLeft     = Offset(inset, inset),
                        size        = arcRect,
                        style       = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                    // Fill arc
                    drawArc(
                        color       = gaugeColor,
                        startAngle  = startAngle,
                        sweepAngle  = sweepTotal * fraction,
                        useCenter   = false,
                        topLeft     = Offset(inset, inset),
                        size        = arcRect,
                        style       = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text       = "%.1f".format(splDb),
                        fontSize   = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color      = gaugeColor
                    )
                    Text(
                        text  = "dB SPL",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

// ─── Peak level card ──────────────────────────────────────────────────────────

@Composable
private fun PeakLevelCard(peakDb: Float, modifier: Modifier = Modifier) {
    Card(
        modifier  = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text  = "Peak Level",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text       = "%.1f".format(peakDb),
                fontSize   = 28.sp,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.error
            )
            Text(
                text  = "dB SPL",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

// ─── Daily dose card ──────────────────────────────────────────────────────────

/**
 * Linear progress indicator showing NIOSH daily noise dose percentage with
 * colour-coded risk levels:
 * - Green  → NO_WARNING  (dose < 50 %)
 * - Amber  → MODERATE    (50 % ≤ dose < 100 %)
 * - Red    → HIGH        (dose ≥ 100 %)
 *
 * Requirement 11.3 / 11.4
 */
@Composable
private fun DailyDoseCard(
    dosePercent: Float,
    warnLevel: DoseWarningLevel
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val indicatorColor = when (warnLevel) {
        DoseWarningLevel.NO_WARNING -> Color(0xFF4CAF50)
        DoseWarningLevel.MODERATE   -> Color(0xFFFFC107)
        DoseWarningLevel.HIGH       -> Color(0xFFF44336)
    }
    val warningMessage = when (warnLevel) {
        DoseWarningLevel.MODERATE -> "Moderate noise exposure — consider reducing listening time."
        DoseWarningLevel.HIGH     -> "High noise exposure! Reduce volume or take a break now."
        DoseWarningLevel.NO_WARNING -> null
    }

    // Progress is clamped to [0, 1] for the indicator; the label shows the real %.
    val progress = (dosePercent / 100f).coerceIn(0f, 1f)

    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    text  = "Daily Noise Dose",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text       = "%.0f%%".format(dosePercent),
                    fontWeight = FontWeight.Bold,
                    color      = indicatorColor
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress         = { progress },
                modifier         = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
                color            = indicatorColor,
                trackColor       = trackColor
            )
            warningMessage?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = indicatorColor
                )
            }
        }
    }
}

// ─── 7-day trend chart ────────────────────────────────────────────────────────

/**
 * Draws a simple line chart of the last 7 days' [DailyExposure.dosePercent]
 * values using Compose [Canvas]. No third-party charting library is used.
 *
 * Requirement 11.2
 */
@Composable
private fun WeeklyTrendCard(trend: List<DailyExposure>) {
    val chartPoints = trend.takeLast(7)
    val lineColor   = MaterialTheme.colorScheme.primary
    val dotColor    = MaterialTheme.colorScheme.tertiary
    val axisColor   = MaterialTheme.colorScheme.onSurfaceVariant
    val highRiskLine = Color(0xFFF44336)
    val dateFormatter = DateTimeFormatter.ofPattern("M/d")

    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text  = "7-Day Noise Exposure Trend",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text  = "Daily dose %",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            if (chartPoints.isEmpty()) {
                Text(
                    text      = "No data available",
                    modifier  = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style     = MaterialTheme.typography.bodySmall
                )
            } else {
                // Chart canvas
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                ) {
                    val paddingLeft   = 16.dp.toPx()
                    val paddingRight  = 16.dp.toPx()
                    val paddingTop    = 12.dp.toPx()
                    val paddingBottom = 28.dp.toPx()  // leave room for date labels

                    val chartWidth  = size.width  - paddingLeft - paddingRight
                    val chartHeight = size.height - paddingTop  - paddingBottom

                    // Data bounds: always show 0–120 % on Y axis so the scale is stable.
                    val yMin = 0f
                    val yMax = 120f

                    val n = chartPoints.size

                    // Helper: map a dose value to a Y pixel (top = high dose).
                    fun doseToY(dose: Float): Float {
                        val clamped = dose.coerceIn(yMin, yMax)
                        return paddingTop + chartHeight * (1f - (clamped - yMin) / (yMax - yMin))
                    }

                    // Helper: map an index to an X pixel.
                    fun indexToX(i: Int): Float {
                        return if (n == 1) paddingLeft + chartWidth / 2f
                        else paddingLeft + i * chartWidth / (n - 1).toFloat()
                    }

                    // Draw 100 % reference line (safe limit).
                    val refY = doseToY(100f)
                    drawLine(
                        color       = highRiskLine.copy(alpha = 0.4f),
                        start       = Offset(paddingLeft, refY),
                        end         = Offset(paddingLeft + chartWidth, refY),
                        strokeWidth = 1.dp.toPx(),
                        cap         = StrokeCap.Round
                    )

                    // Draw connecting line segments.
                    for (i in 0 until n - 1) {
                        drawLine(
                            color       = lineColor,
                            start       = Offset(indexToX(i),     doseToY(chartPoints[i].dosePercent)),
                            end         = Offset(indexToX(i + 1), doseToY(chartPoints[i + 1].dosePercent)),
                            strokeWidth = 2.dp.toPx(),
                            cap         = StrokeCap.Round
                        )
                    }

                    // Draw data point dots.
                    for (i in chartPoints.indices) {
                        val cx = indexToX(i)
                        val cy = doseToY(chartPoints[i].dosePercent)
                        val dotR = 4.dp.toPx()

                        // Filled circle
                        drawCircle(
                            color  = dotColor,
                            radius = dotR,
                            center = Offset(cx, cy)
                        )
                        // Stroke ring
                        drawCircle(
                            color  = lineColor,
                            radius = dotR,
                            center = Offset(cx, cy),
                            style  = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }

                // X-axis date labels (drawn outside Canvas so text is measured by Compose).
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    chartPoints.forEach { point ->
                        Text(
                            text  = point.date.format(dateFormatter),
                            style = MaterialTheme.typography.labelSmall,
                            color = axisColor
                        )
                    }
                }
            }
        }
    }
}
