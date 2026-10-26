package com.example.tryhearingaid.data

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ─── Enums ───────────────────────────────────────────────────────────────────

enum class EnvironmentPreset { DEFAULT, RESTAURANT, CLASSROOM, OUTDOORS, OFFICE }

enum class SoundCategory {
    DOORBELL, BABY_CRYING, FIRE_ALARM, CAR_HORN, DOG_BARKING,
    NAME_CALLED, KNOCK, PHONE_RINGING, WATER_RUNNING, GLASS_BREAKING,
    SIREN, SMOKE_ALARM, CROWD_CHEERING, BIRDS, THUNDER, SPEECH,
    CO_ALARM, FIRE_TRUCK_SIREN, AMBULANCE_SIREN, POLICE_SIREN, VERBAL_HELP
}

enum class AlertPriority { HIGH, LOW }
enum class AlertChannel { SCREEN_BANNER, VIBRATION, FLASHLIGHT, SMARTWATCH }
enum class FlashlightPattern { SHORT, LONG, SOS }

// ─── EQ Band ─────────────────────────────────────────────────────────────────

data class EqBand(val centerHz: Int, val gainDb: Float)

// ─── Audiogram ────────────────────────────────────────────────────────────────

data class Audiogram(
    val profileId: UUID,
    val leftEar: Map<Int, Int>,   // frequency Hz -> threshold dBHL
    val rightEar: Map<Int, Int>,
    val recordedAt: Instant
)

// ─── HearingProfile ───────────────────────────────────────────────────────────

data class HearingProfile(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val eqBands: List<EqBand>,
    val adaptiveAmplificationEnabled: Boolean,
    val noiseReductionPreset: EnvironmentPreset,
    val audiogram: Audiogram?,
    val createdAt: Instant,
    val updatedAt: Instant
)

// ─── CaptionSegment ───────────────────────────────────────────────────────────

data class CaptionSegment(
    val id: UUID = UUID.randomUUID(),
    val speakerLabel: String,
    val text: String,
    val confidence: Float,
    val timestampMs: Long,
    val isUncertain: Boolean // confidence < 0.70
)

// ─── DailyExposure ────────────────────────────────────────────────────────────

data class DailyExposure(
    val date: LocalDate,
    val averageSpl: Float,
    val peakSpl: Float,
    val dosePercent: Float,
    val isHighRisk: Boolean // dose >= 100%
)

// ─── StudyNotes / VocabTerm ───────────────────────────────────────────────────

data class VocabTerm(
    val word: String,
    val definition: String,
    val contextSnippet: String
)

data class StudyNotes(
    val sessionId: UUID,
    val title: String,
    val keyTopics: List<String>,
    val vocabularyTerms: List<VocabTerm>,
    val summary: String,
    val generatedAt: Instant
)

// ─── SoundAlert ───────────────────────────────────────────────────────────────

data class SoundAlert(
    val category: SoundCategory,
    val confidence: Float,
    val priority: AlertPriority,
    val description: String?,
    val detectedAtMs: Long
)

// ─── SoundDetection ───────────────────────────────────────────────────────────

data class SoundDetection(
    val category: SoundCategory,
    val confidence: Float,
    val timestampMs: Long
)

// ─── EmergencyEvent ───────────────────────────────────────────────────────────

data class EmergencyEvent(
    val category: SoundCategory,
    val confidence: Float,
    val detectedAtMs: Long
)

// ─── AnalyticsDashboard ───────────────────────────────────────────────────────

data class AnalyticsDashboard(
    val currentSpl: Float,
    val dailyDose: Float,
    val weeklyTrend: List<DailyExposure>,
    val peakLevel: Float
)

// ─── ImportantPoint ───────────────────────────────────────────────────────────

data class ImportantPoint(
    val text: String,
    val type: String, // e.g. "date", "action_item", "proper_noun"
    val timestampMs: Long
)

// ─── BluetoothDeviceInfo ──────────────────────────────────────────────────────

data class BluetoothDeviceInfo(
    val address: String,
    val name: String,
    val batteryPercent: Int?,
    val supportsDisplay: Boolean
)

// ─── TrendReport ──────────────────────────────────────────────────────────────

data class TrendReport(
    val averageDailyDosePercent: Float,
    val highRiskDayCount: Int,
    val maxPeakSpl: Float,
    val windowDays: Int
)
