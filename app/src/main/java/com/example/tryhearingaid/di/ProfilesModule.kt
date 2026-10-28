package com.example.tryhearingaid.di

import com.example.tryhearingaid.analytics.AnalyticsEngine
import com.example.tryhearingaid.analytics.AnalyticsEngineImpl
import com.example.tryhearingaid.audio.AudioEngine
import com.example.tryhearingaid.audio.Equalizer
import com.example.tryhearingaid.data.AnalyticsDao
import com.example.tryhearingaid.data.PreferencesRepository
import com.example.tryhearingaid.data.ProfileDao
import com.example.tryhearingaid.profiles.AudioTrackTonePlayer
import com.example.tryhearingaid.profiles.HearingTestEngine
import com.example.tryhearingaid.profiles.HearingTestEngineImpl
import com.example.tryhearingaid.profiles.ProfileManager
import com.example.tryhearingaid.profiles.ProfileManagerImpl
import com.example.tryhearingaid.profiles.TonePlayer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hilt bindings for hearing profiles, the hearing test and the exposure analytics. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProfilesBindings {
    @Binds
    abstract fun bindTonePlayer(impl: AudioTrackTonePlayer): TonePlayer
}

@Module
@InstallIn(SingletonComponent::class)
object ProfilesModule {

    /** Saved profiles are loaded, and the last active one re-applied, as soon as this is created. */
    @Provides
    @Singleton
    fun provideProfileManager(
        profileDao: ProfileDao,
        preferences: PreferencesRepository,
        equalizer: Equalizer,
        audioEngine: AudioEngine
    ): ProfileManager {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = ProfileManagerImpl(profileDao, preferences, equalizer, audioEngine, scope)
        scope.launch { runCatching { manager.restore() } }
        return manager
    }

    @Provides
    @Singleton
    fun provideHearingTestEngine(
        profileManager: ProfileManager,
        equalizer: Equalizer,
        tonePlayer: TonePlayer
    ): HearingTestEngine = HearingTestEngineImpl(profileManager, equalizer, tonePlayer)

    @Provides
    @Singleton
    fun provideAnalyticsEngine(
        audioEngine: AudioEngine,
        analyticsDao: AnalyticsDao
    ): AnalyticsEngine = AnalyticsEngineImpl(audioEngine, analyticsDao)
}
