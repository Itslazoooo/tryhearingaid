package com.example.tryhearingaid.ui.analytics

import io.kotest.matchers.shouldBe
import org.junit.Test

class AnalyticsWarningTest {
    @Test
    fun `warning level follows the NIOSH thresholds`() {
        AnalyticsViewModel.warningFor(0f) shouldBe DoseWarningLevel.NO_WARNING
        AnalyticsViewModel.warningFor(49.9f) shouldBe DoseWarningLevel.NO_WARNING
        AnalyticsViewModel.warningFor(50f) shouldBe DoseWarningLevel.MODERATE
        AnalyticsViewModel.warningFor(99.9f) shouldBe DoseWarningLevel.MODERATE
        AnalyticsViewModel.warningFor(100f) shouldBe DoseWarningLevel.HIGH
    }
}
