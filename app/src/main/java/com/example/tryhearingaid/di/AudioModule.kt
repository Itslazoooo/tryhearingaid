package com.example.tryhearingaid.di

import com.example.tryhearingaid.audio.AudioEngine
import com.example.tryhearingaid.audio.AudioEngineImpl
import com.example.tryhearingaid.audio.DirectionalFilter
import com.example.tryhearingaid.audio.DirectionalFilterImpl
import com.example.tryhearingaid.audio.Equalizer
import com.example.tryhearingaid.audio.EqualizerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that binds [AudioEngine], [Equalizer], and [DirectionalFilter] to their
 * concrete implementations.
 *
 * Installed in [SingletonComponent] so the same instances are shared across the app's
 * lifetime — matching the foreground-service ownership model.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {

    @Binds
    @Singleton
    abstract fun bindAudioEngine(impl: AudioEngineImpl): AudioEngine

    @Binds
    @Singleton
    abstract fun bindEqualizer(impl: EqualizerImpl): Equalizer

    @Binds
    @Singleton
    abstract fun bindDirectionalFilter(impl: DirectionalFilterImpl): DirectionalFilter
}
