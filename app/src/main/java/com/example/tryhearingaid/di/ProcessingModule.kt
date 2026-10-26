package com.example.tryhearingaid.di

import com.example.tryhearingaid.classification.EmergencyDetector
import com.example.tryhearingaid.classification.EmergencyDetectorImpl
import com.example.tryhearingaid.classification.NotificationDispatcher
import com.example.tryhearingaid.classification.NotificationDispatcherImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that wires processing-layer interfaces to their implementations.
 *
 * - [EmergencyDetector] → [EmergencyDetectorImpl]
 * - [NotificationDispatcher] → [NotificationDispatcherImpl]
 *
 * [NotificationDispatcherImpl] depends on an optional [com.example.tryhearingaid.wearable.WearableBridge].
 * Because [WearableBridgeImpl] is not yet implemented (Task 15), the bridge dependency
 * is declared as nullable in [NotificationDispatcherImpl]'s constructor and defaults to null.
 * No additional binding is required here until Task 15 adds [WearableBridgeImpl].
 *
 * Installed in [SingletonComponent] so singleton instances are shared across the app.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProcessingModule {

    @Binds
    @Singleton
    abstract fun bindEmergencyDetector(impl: EmergencyDetectorImpl): EmergencyDetector

    @Binds
    @Singleton
    abstract fun bindNotificationDispatcher(impl: NotificationDispatcherImpl): NotificationDispatcher
}
