package com.example.tryhearingaid.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// HearingProfile entity
// Stores the top-level profile row. The EQ bands are stored separately in
// EqBandEntity (one row per band) because there are always 8 fixed bands.
// The audiogram is also stored separately in AudiogramEntity.
// ─────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "hearing_profile")
data class HearingProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: UUID,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "adaptive_enabled")
    val adaptiveEnabled: Boolean,

    /** Stored as the enum name string, e.g. "RESTAURANT". */
    @ColumnInfo(name = "noise_preset")
    val noisePreset: String,

    /** Epoch millis — converted by HearingAidTypeConverters. */
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant
)

// ─────────────────────────────────────────────────────────────────────────────
// EqBandEntity
// One row per EQ band per profile. The bandIndex uniquely identifies a band
// within a profile (0–7).
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "eq_band",
    primaryKeys = ["profile_id", "band_index"],
    foreignKeys = [
        ForeignKey(
            entity = HearingProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("profile_id")]
)
data class EqBandEntity(
    @ColumnInfo(name = "profile_id")
    val profileId: UUID,

    @ColumnInfo(name = "band_index")
    val bandIndex: Int,

    @ColumnInfo(name = "center_hz")
    val centerHz: Int,

    @ColumnInfo(name = "gain_db")
    val gainDb: Float
)

// ─────────────────────────────────────────────────────────────────────────────
// AudiogramEntity
// One row per (profileId, ear, frequencyHz) triple. The ear column is "LEFT"
// or "RIGHT". Frequencies tested: 250, 500, 1000, 2000, 4000, 8000 Hz.
// Threshold range: 0–80 dBHL in 5 dB steps.
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "audiogram",
    primaryKeys = ["profile_id", "ear", "frequency_hz"],
    foreignKeys = [
        ForeignKey(
            entity = HearingProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("profile_id")]
)
data class AudiogramEntity(
    @ColumnInfo(name = "profile_id")
    val profileId: UUID,

    /** "LEFT" or "RIGHT" */
    @ColumnInfo(name = "ear")
    val ear: String,

    @ColumnInfo(name = "frequency_hz")
    val frequencyHz: Int,

    @ColumnInfo(name = "threshold_dbl")
    val thresholdDbl: Int,

    /** Epoch millis — converted by HearingAidTypeConverters. */
    @ColumnInfo(name = "recorded_at")
    val recordedAt: Instant
)

// ─────────────────────────────────────────────────────────────────────────────
// TranscriptSession entity
// Represents a single captioning/recording session.
// audioFilePath is non-null only when classroom mode records audio.
// ─────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "transcript_session")
data class TranscriptSession(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: UUID,

    @ColumnInfo(name = "started_at")
    val startedAt: Instant,

    @ColumnInfo(name = "ended_at")
    val endedAt: Instant?,

    @ColumnInfo(name = "language_code")
    val languageCode: String,

    /** Local file path to the recorded audio; null when audio recording is off. */
    @ColumnInfo(name = "audio_file_path")
    val audioFilePath: String?
)

// ─────────────────────────────────────────────────────────────────────────────
// CaptionSegmentEntity
// One row per transcribed segment. Linked to a TranscriptSession.
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "caption_segment",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptSession::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("session_id")]
)
data class CaptionSegmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: UUID,

    @ColumnInfo(name = "session_id")
    val sessionId: UUID,

    @ColumnInfo(name = "speaker_label")
    val speakerLabel: String,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "confidence")
    val confidence: Float,

    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long,

    /** true when confidence < 0.70 */
    @ColumnInfo(name = "is_uncertain")
    val isUncertain: Boolean
)

// ─────────────────────────────────────────────────────────────────────────────
// DailyExposureEntity
// One row per calendar day. date is stored as ISO-8601 string "yyyy-MM-dd".
// isHighRisk is true when dosePercent >= 100%.
// ─────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "daily_exposure")
data class DailyExposureEntity(
    /** ISO-8601 date string used as primary key, e.g. "2024-06-15". */
    @PrimaryKey
    @ColumnInfo(name = "date")
    val date: LocalDate,

    @ColumnInfo(name = "average_spl")
    val averageSpl: Float,

    @ColumnInfo(name = "peak_spl")
    val peakSpl: Float,

    /** NIOSH dose percentage: 100% = 8 h @ 85 dB SPL. */
    @ColumnInfo(name = "dose_percent")
    val dosePercent: Float,

    /** true when dosePercent >= 100% */
    @ColumnInfo(name = "is_high_risk")
    val isHighRisk: Boolean
)

// ─────────────────────────────────────────────────────────────────────────────
// VocabTermEntity
// Vocabulary terms discovered during sessions, with AI-generated definitions.
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "vocab_term",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptSession::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("session_id")]
)
data class VocabTermEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: UUID,

    @ColumnInfo(name = "session_id")
    val sessionId: UUID,

    @ColumnInfo(name = "word")
    val word: String,

    @ColumnInfo(name = "definition")
    val definition: String,

    @ColumnInfo(name = "context_snippet")
    val contextSnippet: String
)
