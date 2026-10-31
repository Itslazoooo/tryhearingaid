package com.example.tryhearingaid.di

import com.example.tryhearingaid.translation.MlKitTextTranslator
import com.example.tryhearingaid.translation.TextTranslator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class TranslationModule {
    @Binds
    abstract fun bindTextTranslator(impl: MlKitTextTranslator): TextTranslator
}
