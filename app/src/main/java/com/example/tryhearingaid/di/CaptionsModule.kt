package com.example.tryhearingaid.di

import com.example.tryhearingaid.ai.AIAssistant
import com.example.tryhearingaid.ai.AIAssistantImpl
import com.example.tryhearingaid.ai.FakeGeminiClient
import com.example.tryhearingaid.ai.FakeNetworkCallback
import com.example.tryhearingaid.ai.InMemoryVocabularyDao
import com.example.tryhearingaid.captions.CaptionEngine
import com.example.tryhearingaid.captions.FakeCaptionEngine
import com.example.tryhearingaid.captions.InMemoryTranscriptStore
import com.example.tryhearingaid.captions.TranscriptStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that provides [CaptionEngine], [TranscriptStore], and [AIAssistant] bindings.
 *
 * Currently wires [FakeCaptionEngine] and [InMemoryTranscriptStore] as stand-ins
 * until the production SpeechRecognizer-backed implementations are built.
 * [AIAssistant] is provided via [AIAssistantImpl] backed by [FakeGeminiClient] stubs
 * until the production Gemini clients are connected.
 *
 * Replace the `@Provides` bodies with `@Binds` + constructor injection once real
 * implementations are complete.
 */
@Module
@InstallIn(SingletonComponent::class)
object CaptionsModule {

    @Provides
    @Singleton
    fun provideCaptionEngine(): CaptionEngine = FakeCaptionEngine()

    @Provides
    @Singleton
    fun provideTranscriptStore(): TranscriptStore = InMemoryTranscriptStore()

    @Provides
    @Singleton
    fun provideAIAssistant(): AIAssistant = AIAssistantImpl(
        cloudClient = FakeGeminiClient(),
        onDeviceClient = FakeGeminiClient(),
        vocabularyDao = InMemoryVocabularyDao(),
        networkCallback = FakeNetworkCallback()
    )
}
