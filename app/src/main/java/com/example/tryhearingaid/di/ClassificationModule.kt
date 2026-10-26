package com.example.tryhearingaid.di

import com.example.tryhearingaid.classification.SoundClassifier
import com.example.tryhearingaid.classification.SoundClassifierImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that binds [SoundClassifier] to [SoundClassifierImpl].
 *
 * Installed in [SingletonComponent] so the same [SoundClassifierImpl] instance
 * is shared across the app lifetime — matching the foreground-service model.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ClassificationModule {

    @Binds
    @Singleton
    abstract fun bindSoundClassifier(impl: SoundClassifierImpl): SoundClassifier
}
