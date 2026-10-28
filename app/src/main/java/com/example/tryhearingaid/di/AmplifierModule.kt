package com.example.tryhearingaid.di

import com.example.tryhearingaid.audio.AndroidHeadphoneDetector
import com.example.tryhearingaid.audio.HeadphoneDetector
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AmplifierModule {
    @Binds
    abstract fun bindHeadphoneDetector(impl: AndroidHeadphoneDetector): HeadphoneDetector
}
