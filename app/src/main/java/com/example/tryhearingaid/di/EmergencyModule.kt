package com.example.tryhearingaid.di

import android.content.Context
import com.example.tryhearingaid.ai.AIAssistant
import com.example.tryhearingaid.classification.SoundClassifier
import com.example.tryhearingaid.data.PreferencesRepository
import com.example.tryhearingaid.emergency.AndroidSmsSender
import com.example.tryhearingaid.emergency.SosController
import com.example.tryhearingaid.emergency.SosCoordinator
import com.example.tryhearingaid.emergency.SosLocationProvider
import com.example.tryhearingaid.emergency.SosLocationSource
import com.example.tryhearingaid.emergency.SpeechOutput
import com.example.tryhearingaid.environmental.NarrationController
import com.example.tryhearingaid.environmental.SoundNarrator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

/**
 * Hilt module for the emergency SOS (Requirements 10.3, 10.4) and the spoken/on-screen sound
 * descriptions (Requirement 15).
 */
@Module
@InstallIn(SingletonComponent::class)
object EmergencyModule {

    @Provides
    @Singleton
    fun provideSosLocationSource(@ApplicationContext context: Context): SosLocationSource =
        SosLocationProvider(context)

    /**
     * The SOS reads the saved contacts at the moment it is triggered, so edits take effect
     * straight away.
     */
    @Provides
    @Singleton
    fun provideSosController(
        @ApplicationContext context: Context,
        preferences: PreferencesRepository,
        location: SosLocationSource
    ): SosController = SosController(
        contacts = { preferences.userPreferences.first().sosContacts },
        location = location,
        coordinator = SosCoordinator(AndroidSmsSender(context))
    )

    /** Awareness mode: sound detections become description cards and, if enabled, speech. */
    @Provides
    @Singleton
    fun provideNarrationController(
        classifier: SoundClassifier,
        assistant: AIAssistant,
        speech: SpeechOutput
    ): NarrationController = NarrationController(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        detections = classifier.detections,
        narrator = SoundNarrator(assistant),
        speak = speech::speak
    )
}
