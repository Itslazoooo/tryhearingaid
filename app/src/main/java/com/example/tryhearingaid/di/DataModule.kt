package com.example.tryhearingaid.di

import android.content.Context
import com.example.tryhearingaid.data.AnalyticsDao
import com.example.tryhearingaid.data.DataRepository
import com.example.tryhearingaid.data.DataRepositoryImpl
import com.example.tryhearingaid.data.DataStorePreferencesRepository
import com.example.tryhearingaid.data.HearingAidDatabase
import com.example.tryhearingaid.data.LocalUserFiles
import com.example.tryhearingaid.data.LocalUserFilesImpl
import com.example.tryhearingaid.data.PreferencesRepository
import com.example.tryhearingaid.data.ProfileDao
import com.example.tryhearingaid.data.TranscriptDao
import com.example.tryhearingaid.data.VocabularyDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for everything the app stores: the Room database and its DAOs, the DataStore
 * preferences, the plain files (recordings and exports), and the [DataRepository] that
 * aggregates them (Requirements 5.3, 7.4, 11.5, 12.4, 16.4, 16.5).
 *
 * Note that the AI assistant's vocabulary still uses its own in-memory store (see
 * [CaptionsModule]); [VocabularyDao] provided here is the Room one.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HearingAidDatabase =
        HearingAidDatabase.getInstance(context)

    @Provides
    fun provideProfileDao(db: HearingAidDatabase): ProfileDao = db.profileDao()

    @Provides
    fun provideTranscriptDao(db: HearingAidDatabase): TranscriptDao = db.transcriptDao()

    @Provides
    fun provideAnalyticsDao(db: HearingAidDatabase): AnalyticsDao = db.analyticsDao()

    @Provides
    fun provideVocabularyDao(db: HearingAidDatabase): VocabularyDao = db.vocabularyDao()

    /** One instance only: DataStore must not be opened twice for the same file. */
    @Provides
    @Singleton
    fun providePreferencesRepository(@ApplicationContext context: Context): PreferencesRepository =
        DataStorePreferencesRepository(context)

    @Provides
    @Singleton
    fun provideLocalUserFiles(@ApplicationContext context: Context): LocalUserFiles =
        LocalUserFilesImpl(filesDir = context.filesDir, cacheDir = context.cacheDir)

    @Provides
    @Singleton
    fun provideDataRepository(
        db: HearingAidDatabase,
        preferences: PreferencesRepository,
        localFiles: LocalUserFiles
    ): DataRepository = DataRepositoryImpl(db, preferences, localFiles)
}
