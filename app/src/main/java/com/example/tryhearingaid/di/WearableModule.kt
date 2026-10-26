package com.example.tryhearingaid.di

import com.example.tryhearingaid.wearable.WearableBridge
import com.example.tryhearingaid.wearable.WearableBridgeImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module that binds [WearableBridge] to [WearableBridgeImpl].
 *
 * [WearableBridgeImpl] is itself `@Singleton`, so every consumer (the audio service,
 * the notification dispatcher, the settings screen) receives the same instance and
 * therefore sees the same connected-device state. Without this binding
 * `NotificationDispatcherImpl` silently received no bridge and smartwatch alerts
 * were dropped.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WearableModule {

    @Binds
    abstract fun bindWearableBridge(impl: WearableBridgeImpl): WearableBridge
}
