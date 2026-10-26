package com.example.tryhearingaid.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room database for the Advanced Hearing Aid App.
 *
 * Registers all entities:
 * - [HearingProfileEntity] — top-level hearing profiles
 * - [EqBandEntity]         — 8 EQ bands per profile (FK → HearingProfile)
 * - [AudiogramEntity]      — audiogram measurements per profile (FK → HearingProfile)
 * - [TranscriptSession]    — caption/recording sessions
 * - [CaptionSegmentEntity] — individual transcribed segments (FK → TranscriptSession)
 * - [DailyExposureEntity]  — daily noise-exposure records
 * - [VocabTermEntity]      — vocabulary terms discovered during sessions (FK → TranscriptSession)
 *
 * Uses [HearingAidTypeConverters] for UUID, Instant, LocalDate, and JSON-serialised types.
 *
 * Version 1. Schema export is disabled for now and will be configured in a later task
 * once the export directory is wired into the build.
 */
@Database(
    entities = [
        HearingProfileEntity::class,
        EqBandEntity::class,
        AudiogramEntity::class,
        TranscriptSession::class,
        CaptionSegmentEntity::class,
        DailyExposureEntity::class,
        VocabTermEntity::class,
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(HearingAidTypeConverters::class)
abstract class HearingAidDatabase : RoomDatabase() {

    // ── DAO accessors ─────────────────────────────────────────────────────────

    abstract fun profileDao(): ProfileDao

    abstract fun transcriptDao(): TranscriptDao

    abstract fun analyticsDao(): AnalyticsDao

    abstract fun vocabularyDao(): VocabularyDao

    // ── Singleton factory ─────────────────────────────────────────────────────

    companion object {

        @Volatile
        private var INSTANCE: HearingAidDatabase? = null

        /**
         * Returns the singleton [HearingAidDatabase] instance, creating it on first call.
         *
         * Thread-safe via double-checked locking with [INSTANCE] marked `@Volatile`.
         *
         * @param context Application context used to locate the database file.
         */
        fun getInstance(context: Context): HearingAidDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): HearingAidDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                HearingAidDatabase::class.java,
                "hearing_aid_database"
            ).build()
    }
}
